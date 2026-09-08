package com.pdflearning.backend.dataport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;

public final class Neo4jGraphStore implements AutoCloseable {
    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jGraphStore(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    public void replaceRagDocument(
            String userId,
            String documentId,
            List<RagChunk> chunks,
            List<RagEdge> nextEdges,
            List<RagEdge> similarEdges) {
        try (var session = driver.session(sessionConfig)) {
            session.executeWriteWithoutResult(tx -> {
                var scope = Values.parameters("userId", userId, "documentId", documentId);
                tx.run("""
                        MATCH (chunk:RagChunk {user_id: $userId, document_id: $documentId})
                        DETACH DELETE chunk
                        """, scope).consume();
                tx.run("""
                        UNWIND $chunks AS item
                        CREATE (:RagChunk {
                            user_id: $userId,
                            document_id: $documentId,
                            chunk_id: item.chunk_id,
                            chunk_index: item.chunk_index,
                            page: item.page,
                            section: item.section
                        })
                        """, Values.parameters(
                        "userId", userId,
                        "documentId", documentId,
                        "chunks", graphChunks(chunks))).consume();
                writeRagEdges(tx, userId, documentId, "NEXT_CHUNK", nextEdges);
                writeRagEdges(tx, userId, documentId, "SIMILAR_TO", similarEdges);
            });
        }
    }

    public List<String> expandRag(
            String userId,
            List<String> readyDocumentIds,
            List<String> seedChunkIds,
            int limit) {
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> tx.run("""
                            MATCH (seed:RagChunk {user_id: $userId})-[:NEXT_CHUNK|SIMILAR_TO]-(candidate:RagChunk {user_id: $userId})
                            WHERE seed.chunk_id IN $seedIds
                              AND seed.document_id IN $documentIds
                              AND candidate.document_id IN $documentIds
                            RETURN DISTINCT candidate.chunk_id AS chunk_id
                            LIMIT $limit
                            """, Values.parameters(
                            "userId", userId,
                            "seedIds", seedChunkIds,
                            "documentIds", readyDocumentIds,
                            "limit", limit))
                    .list(record -> record.get("chunk_id").asString()));
        }
    }

    public void replaceSemanticMemory(
            String userId,
            String memoryId,
            List<Map<String, String>> entities,
            List<Map<String, String>> relations) {
        try (var session = driver.session(sessionConfig)) {
            session.executeWriteWithoutResult(tx -> {
                removeMemoryRelations(tx, userId, memoryId);
                tx.run("""
                        MATCH (memory:LongMemory {user_id: $userId, memory_id: $memoryId})
                        OPTIONAL MATCH (memory)-[old:MENTIONS]->()
                        DELETE old
                        """, Values.parameters("userId", userId, "memoryId", memoryId)).consume();
                tx.run("""
                        MERGE (memory:LongMemory {user_id: $userId, memory_id: $memoryId})
                        WITH memory
                        UNWIND $entities AS item
                        MERGE (entity:MemoryEntity {user_id: $userId, name: item.name})
                        SET entity.type = item.type
                        MERGE (memory)-[:MENTIONS]->(entity)
                        """, Values.parameters(
                        "userId", userId,
                        "memoryId", memoryId,
                        "entities", entities)).consume();
                tx.run("""
                        UNWIND $relations AS item
                        MATCH (left:MemoryEntity {user_id: $userId, name: item.subject})
                        MATCH (right:MemoryEntity {user_id: $userId, name: item.object})
                        MERGE (left)-[relation:RELATED {name: item.relation}]->(right)
                        SET relation.memory_ids = CASE
                            WHEN $memoryId IN coalesce(relation.memory_ids, []) THEN relation.memory_ids
                            ELSE coalesce(relation.memory_ids, []) + $memoryId
                        END
                        """, Values.parameters(
                        "userId", userId,
                        "memoryId", memoryId,
                        "relations", relations)).consume();
                deleteOrphanMemoryEntities(tx, userId);
            });
        }
    }

    public void deleteMemory(String userId, String memoryId) {
        try (var session = driver.session(sessionConfig)) {
            session.executeWriteWithoutResult(tx -> {
                removeMemoryRelations(tx, userId, memoryId);
                tx.run("""
                        MATCH (memory:LongMemory {user_id: $userId, memory_id: $memoryId})
                        DETACH DELETE memory
                        """, Values.parameters("userId", userId, "memoryId", memoryId)).consume();
                deleteOrphanMemoryEntities(tx, userId);
            });
        }
    }

    @Override
    public void close() {
        driver.close();
    }

    private static List<Map<String, Object>> graphChunks(List<RagChunk> chunks) {
        var values = new ArrayList<Map<String, Object>>();
        for (var chunk : chunks) {
            var item = new HashMap<String, Object>();
            item.put("chunk_id", chunk.chunkId());
            item.put("chunk_index", chunk.chunkIndex());
            item.put("page", chunk.page());
            item.put("section", section(chunk));
            values.add(item);
        }
        return values;
    }

    private static String section(RagChunk chunk) {
        return java.util.stream.Stream.of(chunk.h1(), chunk.h2(), chunk.h3())
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " > " + right)
                .orElse(null);
    }

    private static void writeRagEdges(
            org.neo4j.driver.TransactionContext tx,
            String userId,
            String documentId,
            String relationship,
            List<RagEdge> edges) {
        if (edges.isEmpty()) {
            return;
        }
        var query = """
                UNWIND $edges AS item
                MATCH (left:RagChunk {user_id: $userId, document_id: $documentId, chunk_id: item.from})
                MATCH (right:RagChunk {user_id: $userId, document_id: $documentId, chunk_id: item.to})
                MERGE (left)-[edge:%s]->(right)
                SET edge.score = item.score
                """.formatted(relationship);
        var values = edges.stream().map(edge -> Map.<String, Object>of(
                "from", edge.from(),
                "to", edge.to(),
                "score", edge.score() == null ? 0.0 : edge.score())).toList();
        tx.run(query, Values.parameters(
                "userId", userId,
                "documentId", documentId,
                "edges", values)).consume();
    }

    private static void removeMemoryRelations(
            org.neo4j.driver.TransactionContext tx,
            String userId,
            String memoryId) {
        tx.run("""
                MATCH (left:MemoryEntity {user_id: $userId})-[relation:RELATED]->(right:MemoryEntity {user_id: $userId})
                WHERE $memoryId IN coalesce(relation.memory_ids, [])
                SET relation.memory_ids = [id IN relation.memory_ids WHERE id <> $memoryId]
                WITH relation
                WHERE size(relation.memory_ids) = 0
                DELETE relation
                """, Values.parameters("userId", userId, "memoryId", memoryId)).consume();
    }

    private static void deleteOrphanMemoryEntities(
            org.neo4j.driver.TransactionContext tx,
            String userId) {
        tx.run("""
                MATCH (entity:MemoryEntity {user_id: $userId})
                WHERE NOT (entity)<-[:MENTIONS]-(:LongMemory {user_id: $userId})
                DETACH DELETE entity
                """, Values.parameters("userId", userId)).consume();
    }
}
