package com.pdflearning.backend.dataport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MilvusRagIndex {
    public record ScoredId(String chunkId, double score) {
    }

    private static final List<String> CHUNK_FIELDS = List.of(
            "user_id", "chunk_id", "document_id", "chunk_index", "text", "source", "file_type",
            "page", "h1", "h2", "h3");

    private final String collection;
    private final String database;
    private final ObjectMapper mapper;
    private final JsonHttpClient http;

    public MilvusRagIndex(
            String baseUrl,
            String collection,
            String database,
            String token,
            Duration timeout) {
        this(baseUrl, collection, database, token, timeout, HttpClient.newHttpClient(), new ObjectMapper());
    }

    MilvusRagIndex(
            String baseUrl,
            String collection,
            String database,
            String token,
            Duration timeout,
            HttpClient client,
            ObjectMapper mapper) {
        this.collection = collectionName(collection);
        this.database = database == null ? "" : database;
        this.mapper = mapper;
        this.http = new JsonHttpClient(baseUrl, "Authorization", bearer(token), timeout, client, mapper);
    }

    public List<ScoredId> search(
            String userId,
            List<String> readyDocumentIds,
            List<Double> queryVector,
            List<String> allowedChunkIds,
            int limit) {
        if (readyDocumentIds.isEmpty() || (allowedChunkIds != null && allowedChunkIds.isEmpty())) {
            return List.of();
        }
        var body = requestBody();
        body.set("data", mapper.createArrayNode().add(mapper.valueToTree(queryVector)));
        body.put("annsField", "vector");
        body.put("limit", limit);
        body.put("filter", scopedFilter(userId, readyDocumentIds, allowedChunkIds));
        body.set("outputFields", mapper.valueToTree(List.of("chunk_id")));
        body.set("searchParams", mapper.valueToTree(Map.of("metricType", "COSINE", "params", Map.of())));

        var response = requireOk(http.request("POST", "/v2/vectordb/entities/search", body), "查询");
        var results = new ArrayList<ScoredId>();
        for (var item : response.path("data")) {
            var chunkId = item.path("chunk_id").asText(item.path("id").asText(""));
            if (!chunkId.isBlank()) {
                results.add(new ScoredId(chunkId, item.path("distance").asDouble()));
            }
        }
        return List.copyOf(results);
    }

    public Map<String, RagChunk> readChunks(
            String userId,
            List<String> readyDocumentIds,
            List<String> chunkIds) {
        if (readyDocumentIds.isEmpty() || chunkIds.isEmpty()) {
            return Map.of();
        }
        var body = requestBody();
        body.put("filter", scopedFilter(userId, readyDocumentIds, chunkIds));
        body.set("outputFields", mapper.valueToTree(CHUNK_FIELDS));
        body.put("limit", chunkIds.size());

        var response = requireOk(http.request("POST", "/v2/vectordb/entities/query", body), "正文回查");
        var result = new HashMap<String, RagChunk>();
        for (var item : response.path("data")) {
            if (!userId.equals(item.path("user_id").asText())) {
                continue;
            }
            var chunk = chunk(item);
            if (chunkIds.contains(chunk.chunkId()) && readyDocumentIds.contains(chunk.documentId())) {
                result.put(chunk.chunkId(), chunk);
            }
        }
        return Map.copyOf(result);
    }

    public void replaceDocument(String userId, String documentId, List<RagChunk> chunks) {
        deleteDocument(userId, documentId);
        if (chunks.isEmpty()) {
            return;
        }
        var body = requestBody();
        var data = mapper.createArrayNode();
        for (var chunk : chunks) {
            var item = mapper.createObjectNode();
            item.put("chunk_id", chunk.chunkId());
            item.put("user_id", userId);
            item.put("document_id", documentId);
            item.put("chunk_index", chunk.chunkIndex());
            item.put("text", chunk.text());
            item.put("source", chunk.source());
            item.put("file_type", chunk.fileType());
            putNullable(item, "page", chunk.page());
            putNullable(item, "h1", chunk.h1());
            putNullable(item, "h2", chunk.h2());
            putNullable(item, "h3", chunk.h3());
            item.set("vector", mapper.valueToTree(chunk.vector()));
            data.add(item);
        }
        body.set("data", data);
        requireOk(http.request("POST", "/v2/vectordb/entities/insert", body), "写入");
    }

    public void deleteDocument(String userId, String documentId) {
        var body = requestBody();
        body.put("filter", "user_id == " + literal(userId) + " and document_id == " + literal(documentId));
        requireOk(http.request("POST", "/v2/vectordb/entities/delete", body), "删除文档");
    }

    private ObjectNode requestBody() {
        var body = mapper.createObjectNode();
        body.put("collectionName", collection);
        if (!database.isBlank()) {
            body.put("dbName", database);
        }
        return body;
    }

    private String scopedFilter(String userId, List<String> documentIds, List<String> chunkIds) {
        var filter = "user_id == " + literal(userId) + " and document_id in " + literals(documentIds);
        if (chunkIds != null) {
            filter += " and chunk_id in " + literals(chunkIds);
        }
        return filter;
    }

    private JsonNode requireOk(JsonNode response, String operation) {
        if (response.path("code").asInt(-1) != 0) {
            throw new DataPortException("Milvus " + operation + "失败: " + response);
        }
        return response;
    }

    private RagChunk chunk(JsonNode item) {
        return new RagChunk(
                item.path("chunk_id").asText(),
                item.path("document_id").asText(),
                item.path("chunk_index").asInt(),
                item.path("text").asText(),
                item.path("source").asText(),
                item.path("file_type").asText(),
                item.hasNonNull("page") ? item.get("page").asInt() : null,
                nullableText(item, "h1"),
                nullableText(item, "h2"),
                nullableText(item, "h3"),
                List.of());
    }

    private static String nullableText(JsonNode item, String name) {
        return item.hasNonNull(name) ? item.get(name).asText() : null;
    }

    private static void putNullable(ObjectNode item, String name, Object value) {
        if (value instanceof Integer integer) {
            item.put(name, integer);
        } else if (value instanceof String text) {
            item.put(name, text);
        }
    }

    static String literal(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Milvus 过滤值不能为空");
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String literals(List<String> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Milvus in 过滤列表不能为空");
        }
        return values.stream().map(MilvusRagIndex::literal).reduce((a, b) -> a + "," + b)
                .map(value -> "[" + value + "]")
                .orElseThrow();
    }

    private static String collectionName(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Milvus collection 名称无效");
        }
        return value;
    }

    private static String bearer(String token) {
        return token == null || token.isBlank() ? null : "Bearer " + token;
    }
}
