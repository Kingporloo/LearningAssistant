package com.pdflearning.backend.dataport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RagDataPort {
    public record BuildCommand(
            String userId,
            String requestId,
            String documentId,
            String buildStatus,
            String message,
            List<RagChunk> chunks,
            List<RagEdge> nextEdges,
            List<RagEdge> similarEdges) {
        public BuildCommand {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            nextEdges = nextEdges == null ? List.of() : List.copyOf(nextEdges);
            similarEdges = similarEdges == null ? List.of() : List.copyOf(similarEdges);
        }
    }

    private final MySqlDataStore mysql;
    private final MilvusRagIndex milvus;
    private final Neo4jGraphStore graphStore;
    private final int vectorDimension;

    public RagDataPort(
            MySqlDataStore mysql,
            MilvusRagIndex milvus,
            Neo4jGraphStore graphStore,
            int vectorDimension) {
        this.mysql = mysql;
        this.milvus = milvus;
        this.graphStore = graphStore;
        this.vectorDimension = requireVectorDimension(vectorDimension);
    }

    public Map<String, Object> search(String userId, List<Double> queryVector, int limit) {
        validateSearch(userId, queryVector, limit);
        var state = mysql.ragState(userId);
        var readyDocuments = mysql.readyDocumentIds(userId);
        if (readyDocuments.isEmpty()) {
            return unavailableState(state);
        }
        var scored = milvus.search(userId, readyDocuments, queryVector, null, limit);
        return results(userId, readyDocuments, scored, "知识库中没有找到候选内容。");
    }

    public Map<String, Object> graphSearch(
            String userId,
            List<String> seedChunkIds,
            List<Double> queryVector,
            int limit) {
        validateSearch(userId, queryVector, limit);
        if (seedChunkIds == null || seedChunkIds.isEmpty()) {
            throw new IllegalArgumentException("seed_chunk_ids 不能为空");
        }
        seedChunkIds.forEach(id -> requireText(id, "seed_chunk_id"));
        var readyDocuments = mysql.readyDocumentIds(userId);
        if (readyDocuments.isEmpty()) {
            return unavailableState(mysql.ragState(userId));
        }
        var candidateIds = graphStore.expandRag(
                userId, readyDocuments, List.copyOf(seedChunkIds), Math.max(limit * 4, limit));
        var scored = milvus.search(userId, readyDocuments, queryVector, candidateIds, limit);
        return results(userId, readyDocuments, scored, "没有可用的图扩展候选。");
    }

    public Map<String, Object> replaceDocument(BuildCommand command) {
        validateBuild(command);
        mysql.setRagDocumentStatus(
                command.userId(), command.documentId(), command.requestId(), "building", "正在写入索引。");
        try {
            milvus.replaceDocument(command.userId(), command.documentId(), command.chunks());
            graphStore.replaceRagDocument(
                    command.userId(),
                    command.documentId(),
                    command.chunks(),
                    command.nextEdges(),
                    command.similarEdges());
            var status = command.chunks().isEmpty() ? "empty" : "ready";
            mysql.setRagDocumentStatus(
                    command.userId(), command.documentId(), command.requestId(), status, command.message());
            return Map.of(
                    "status", status,
                    "document_id", command.documentId(),
                    "chunk_count", command.chunks().size(),
                    "message", command.message() == null ? "RAG 文档已完成入库。" : command.message());
        } catch (RuntimeException exception) {
            mysql.setRagDocumentStatus(
                    command.userId(), command.documentId(), command.requestId(), "failed", exception.getMessage());
            return Map.of(
                    "status", "error",
                    "document_id", command.documentId(),
                    "message", "RAG 文档入库失败: " + exception.getMessage());
        }
    }

    private Map<String, Object> results(
            String userId,
            List<String> readyDocuments,
            List<MilvusRagIndex.ScoredId> scored,
            String emptyMessage) {
        if (scored.isEmpty()) {
            return Map.of("status", "no_match", "results", List.of(), "message", emptyMessage);
        }
        var ids = scored.stream().map(MilvusRagIndex.ScoredId::chunkId).toList();
        var chunks = milvus.readChunks(userId, readyDocuments, ids);
        var results = new ArrayList<Map<String, Object>>();
        for (var item : scored) {
            var chunk = chunks.get(item.chunkId());
            if (chunk != null) {
                results.add(chunkResult(chunk, item.score()));
            }
        }
        return results.isEmpty()
                ? Map.of(
                        "status", "no_match",
                        "results", List.of(),
                        "message", "索引候选未通过用户和正文归属回查。")
                : Map.of(
                        "status", "ok",
                        "results", results,
                        "message", "找到 " + results.size() + " 条 RAG 候选。");
    }

    private static Map<String, Object> chunkResult(RagChunk chunk, double score) {
        var result = new LinkedHashMap<String, Object>();
        result.put("chunk_id", chunk.chunkId());
        result.put("document_id", chunk.documentId());
        result.put("chunk_index", chunk.chunkIndex());
        result.put("text", chunk.text());
        result.put("source", chunk.source());
        result.put("file_type", chunk.fileType());
        if (chunk.page() != null) {
            result.put("page", chunk.page());
        }
        if (chunk.h1() != null) {
            result.put("h1", chunk.h1());
        }
        if (chunk.h2() != null) {
            result.put("h2", chunk.h2());
        }
        if (chunk.h3() != null) {
            result.put("h3", chunk.h3());
        }
        result.put("similarity", score);
        return result;
    }

    private static Map<String, Object> unavailableState(MySqlDataStore.RagState state) {
        if (state.building() > 0) {
            return Map.of(
                    "status", "not_ready", "results", List.of(), "message", "知识库正在构建。");
        }
        if (state.failed() > 0) {
            return Map.of(
                    "status", "error", "results", List.of(), "message", "知识库构建失败，当前没有可检索文档。");
        }
        return Map.of(
                "status", "empty", "results", List.of(), "message", "当前用户的知识库没有可检索内容。");
    }

    private void validateSearch(String userId, List<Double> vector, int limit) {
        requireText(userId, "user_id");
        validateVector(vector);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
    }

    private void validateBuild(BuildCommand command) {
        requireText(command.userId(), "user_id");
        requireText(command.requestId(), "request_id");
        requireText(command.documentId(), "document_id");
        if (!("ok".equals(command.buildStatus()) || "empty".equals(command.buildStatus()))) {
            throw new IllegalArgumentException("只接受 Python 构建成功的 ok 或 empty 结果");
        }
        if ("ok".equals(command.buildStatus()) && command.chunks().isEmpty()) {
            throw new IllegalArgumentException("ok 构建结果必须包含分块");
        }
        if ("empty".equals(command.buildStatus()) && !command.chunks().isEmpty()) {
            throw new IllegalArgumentException("empty 构建结果不能包含分块");
        }

        var chunkIds = new HashSet<String>();
        for (var chunk : command.chunks()) {
            if (!command.documentId().equals(chunk.documentId())) {
                throw new IllegalArgumentException("分块 document_id 与构建目标不一致");
            }
            requireText(chunk.chunkId(), "chunk_id");
            requireText(chunk.text(), "chunk.text");
            requireText(chunk.source(), "chunk.source");
            requireText(chunk.fileType(), "chunk.file_type");
            if (!chunkIds.add(chunk.chunkId())) {
                throw new IllegalArgumentException("构建结果存在重复 chunk_id");
            }
            validateVector(chunk.vector());
        }
        validateEdges(command.nextEdges(), chunkIds);
        validateEdges(command.similarEdges(), chunkIds);
    }

    private static void validateEdges(List<RagEdge> edges, HashSet<String> chunkIds) {
        for (var edge : edges) {
            if (!chunkIds.contains(edge.from()) || !chunkIds.contains(edge.to())) {
                throw new IllegalArgumentException("RAG 图关系只能引用本次文档中的分块");
            }
        }
    }

    private void validateVector(List<Double> vector) {
        if (vector == null || vector.isEmpty()
                || vector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("向量必须是非空有限数值数组");
        }
        if (vector.size() != vectorDimension) {
            throw new IllegalArgumentException(
                    "向量维度必须是 " + vectorDimension + "，实际为 " + vector.size());
        }
    }

    private static int requireVectorDimension(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("向量维度必须是正整数");
        }
        return value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
