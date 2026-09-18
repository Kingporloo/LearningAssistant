package com.pdflearning.backend.dataport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class RealDataStoresIT {
    @Test
    void usesRealStoresAndKeepsUsersIsolated() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_REAL_DATASTORE_TESTS")));
        int dimension = Integer.parseInt(System.getenv("EMBEDDING_VECTOR_DIMENSION"));
        String suffix = UUID.randomUUID().toString();
        String userA = "integration-a-" + suffix;
        String userB = "integration-b-" + suffix;
        String documentId = "document-" + suffix;
        String buildRequestId = "build-" + suffix;
        String firstChunkId = documentId + ":000000";
        String secondChunkId = documentId + ":000001";
        List<Double> firstVector = vector(dimension, 0);
        List<Double> secondVector = vector(dimension, 1);

        try (var resources = DataPortResources.fromEnvironment()) {
            var readiness = resources.readiness();
            assertTrue(readiness.ready(), () -> "存储未就绪: " + readiness.components());

            resources.documents().create(new DocumentDataPort.DocumentData(
                    documentId,
                    userA,
                    buildRequestId,
                    "integration.md",
                    16,
                    "/tmp/integration.md",
                    "/tmp/integration.md",
                    "converting",
                    null,
                    null,
                    null,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    null));
            var chunks = List.of(
                    chunk(firstChunkId, documentId, 0, "真实存储集成测试第一段", firstVector),
                    chunk(secondChunkId, documentId, 1, "真实存储集成测试第二段", secondVector));
            var built = resources.rag().replaceDocument(new RagDataPort.BuildCommand(
                    userA,
                    buildRequestId,
                    documentId,
                    "ok",
                    "integration test",
                    chunks,
                    List.of(new RagEdge(firstChunkId, secondChunkId, null)),
                    List.of()));
            assertEquals("ready", built.get("status"));

            Map<String, Object> ragResult = awaitStatus(
                    () -> resources.rag().search(userA, firstVector, 5), "ok");
            assertEquals("ok", ragResult.get("status"));
            assertEquals("empty", resources.rag().search(userB, firstVector, 5).get("status"));
            assertEquals(
                    "ok",
                    awaitStatus(
                            () -> resources.rag().graphSearch(
                                    userA, List.of(firstChunkId), secondVector, 5),
                            "ok").get("status"));

            String memoryRequestId = "memory-" + suffix;
            var stored = resources.memory().store(new MemoryDataPort.StoreCommand(
                    userA,
                    memoryRequestId,
                    "store-" + suffix,
                    null,
                    "semantic",
                    "用户正在验证真实数据库链路",
                    0.8,
                    firstVector,
                    new MemoryDataPort.Source("session-" + suffix, "message-" + suffix),
                    null));
            assertEquals("ok", stored.get("status"));
            String memoryId = stored.get("memory_id").toString();
            try {
                assertEquals(
                        "ok",
                        awaitStatus(
                                () -> resources.memory().query(new MemoryDataPort.Query(
                                        userA, "semantic", firstVector, 5)),
                                "ok").get("status"));
                assertEquals(
                        "not_found",
                        resources.memory().query(new MemoryDataPort.Query(
                                userB, "semantic", firstVector, 5)).get("status"));
            } finally {
                resources.memory().forget(new MemoryDataPort.ForgetCommand(
                        userA,
                        "forget-" + suffix,
                        "forget-op-" + suffix,
                        memoryId,
                        "semantic"));
            }
            resources.rag().deleteDocument(userA, documentId);
        }
    }

    private static RagChunk chunk(
            String chunkId,
            String documentId,
            int index,
            String text,
            List<Double> vector) {
        return new RagChunk(
                chunkId,
                documentId,
                index,
                text,
                "integration.md",
                "md",
                1,
                "Integration",
                null,
                null,
                vector);
    }

    private static List<Double> vector(int dimension, int activeIndex) {
        var values = new ArrayList<Double>(dimension);
        for (int index = 0; index < dimension; index++) {
            values.add(index == activeIndex ? 1.0 : 0.0);
        }
        return List.copyOf(values);
    }

    private static Map<String, Object> awaitStatus(Query operation, String expected)
            throws Exception {
        Map<String, Object> result = Map.of();
        for (int attempt = 0; attempt < 20; attempt++) {
            result = operation.run();
            if (expected.equals(result.get("status"))) {
                return result;
            }
            Thread.sleep(250);
        }
        return result;
    }

    @FunctionalInterface
    private interface Query {
        Map<String, Object> run();
    }
}
