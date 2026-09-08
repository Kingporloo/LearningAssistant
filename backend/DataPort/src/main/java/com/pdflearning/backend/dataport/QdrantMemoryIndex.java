package com.pdflearning.backend.dataport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class QdrantMemoryIndex {
    public record ScoredId(String memoryId, double score) {
    }

    private final String collection;
    private final ObjectMapper mapper;
    private final JsonHttpClient http;

    public QdrantMemoryIndex(String baseUrl, String collection, String apiKey, Duration timeout) {
        this(baseUrl, collection, apiKey, timeout, HttpClient.newHttpClient(), new ObjectMapper());
    }

    QdrantMemoryIndex(
            String baseUrl,
            String collection,
            String apiKey,
            Duration timeout,
            HttpClient client,
            ObjectMapper mapper) {
        this.collection = collectionName(collection);
        this.mapper = mapper;
        this.http = new JsonHttpClient(baseUrl, "api-key", apiKey, timeout, client, mapper);
    }

    public List<ScoredId> query(
            String userId,
            String memoryType,
            List<Double> vector,
            int limit) {
        var body = mapper.createObjectNode();
        body.set("query", mapper.valueToTree(vector));
        body.set("filter", filter(userId, memoryType, null));
        body.put("limit", limit);
        body.put("with_payload", false);
        body.put("with_vector", false);

        var response = http.request("POST", path("/points/query"), body);
        if (!"ok".equals(response.path("status").asText())) {
            throw new DataPortException("Qdrant 查询失败: " + response);
        }
        JsonNode points = response.path("result").path("points");
        if (!points.isArray() && response.path("result").isArray()) {
            points = response.path("result");
        }
        var results = new ArrayList<ScoredId>();
        if (points.isArray()) {
            for (var point : points) {
                if (!point.hasNonNull("id")) {
                    continue;
                }
                results.add(new ScoredId(point.get("id").asText(), point.path("score").asDouble()));
            }
        }
        return List.copyOf(results);
    }

    public void upsert(
            String userId,
            String memoryId,
            String memoryType,
            List<Double> vector) {
        var payload = mapper.createObjectNode();
        payload.put("user_id", userId);
        payload.put("memory_id", memoryId);
        payload.put("memory_type", memoryType);
        payload.put("status", "active");

        var point = mapper.createObjectNode();
        point.put("id", memoryId);
        point.set("vector", mapper.valueToTree(vector));
        point.set("payload", payload);

        var body = mapper.createObjectNode();
        body.set("points", mapper.createArrayNode().add(point));
        requireOk(http.request("PUT", path("/points?wait=true"), body), "写入");
    }

    public void delete(String userId, String memoryId) {
        var body = mapper.createObjectNode();
        body.set("filter", filter(userId, null, memoryId));
        requireOk(http.request("POST", path("/points/delete?wait=true"), body), "删除");
    }

    private ObjectNode filter(String userId, String memoryType, String memoryId) {
        var must = mapper.createArrayNode();
        must.add(match("user_id", userId));
        must.add(match("status", "active"));
        if (memoryType != null && !"all".equals(memoryType)) {
            must.add(match("memory_type", memoryType));
        }
        if (memoryId != null) {
            must.add(match("memory_id", memoryId));
        }
        var filter = mapper.createObjectNode();
        filter.set("must", must);
        return filter;
    }

    private ObjectNode match(String key, String value) {
        var condition = mapper.createObjectNode();
        condition.put("key", key);
        condition.set("match", mapper.createObjectNode().put("value", value));
        return condition;
    }

    private void requireOk(JsonNode response, String operation) {
        if (!"ok".equals(response.path("status").asText())) {
            throw new DataPortException("Qdrant " + operation + "失败: " + response);
        }
    }

    private String path(String suffix) {
        return "/collections/" + collection + suffix;
    }

    private static String collectionName(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Qdrant collection 名称无效");
        }
        return value;
    }
}
