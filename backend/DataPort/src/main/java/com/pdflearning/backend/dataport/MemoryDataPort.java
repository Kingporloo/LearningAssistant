package com.pdflearning.backend.dataport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MemoryDataPort {
    public record Source(String sessionId, String messageId) {
    }

    public record Entity(String name, String type) {
    }

    public record Relation(String subject, String relation, String object) {
    }

    public record Graph(List<Entity> entities, List<Relation> relations) {
        public Graph {
            entities = entities == null ? List.of() : List.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
        }
    }

    public record StoreCommand(
            String userId,
            String requestId,
            String operationId,
            String memoryId,
            String memoryType,
            String content,
            double importance,
            List<Double> vector,
            Source source,
            OffsetDateTime eventTime) {
    }

    public record CompleteGraphCommand(
            String userId,
            String memoryId,
            int revision,
            String graphStatus,
            String graphError,
            Graph graph) {
    }

    public record Query(
            String userId,
            String memoryType,
            List<Double> queryVector,
            int limit) {
    }

    public record ForgetCommand(
            String userId,
            String requestId,
            String operationId,
            String memoryId,
            String memoryType) {
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final MySqlDataStore mysql;
    private final QdrantMemoryIndex qdrant;
    private final Neo4jGraphStore graphStore;
    private final ObjectMapper mapper;
    private final int vectorDimension;
    private final Object[] memoryLocks = new Object[64];

    public MemoryDataPort(
            MySqlDataStore mysql,
            QdrantMemoryIndex qdrant,
            Neo4jGraphStore graphStore,
            int vectorDimension) {
        this(mysql, qdrant, graphStore, new ObjectMapper(), vectorDimension);
    }

    MemoryDataPort(
            MySqlDataStore mysql,
            QdrantMemoryIndex qdrant,
            Neo4jGraphStore graphStore,
            ObjectMapper mapper,
            int vectorDimension) {
        this.mysql = mysql;
        this.qdrant = qdrant;
        this.graphStore = graphStore;
        this.mapper = mapper;
        this.vectorDimension = requireVectorDimension(vectorDimension);
        for (int i = 0; i < memoryLocks.length; i++) {
            memoryLocks[i] = new Object();
        }
    }

    public Map<String, Object> query(Query query) {
        validateQuery(query);
        var scored = qdrant.query(
                query.userId(), query.memoryType(), query.queryVector(), query.limit());
        if (scored.isEmpty()) {
            return response("not_found", "未找到相关长期记忆。", List.of());
        }

        var scoreById = new HashMap<String, Double>();
        var ids = new ArrayList<String>();
        for (var item : scored) {
            ids.add(item.memoryId());
            scoreById.putIfAbsent(item.memoryId(), item.score());
        }
        var byId = new HashMap<String, MemoryRecord>();
        for (var memory : mysql.readActiveMemories(query.userId(), query.memoryType(), ids)) {
            byId.put(memory.memoryId(), memory);
        }

        var results = new ArrayList<Map<String, Object>>();
        for (var id : ids) {
            var memory = byId.get(id);
            if (memory != null) {
                results.add(memoryResult(memory, scoreById.get(id)));
            }
        }
        return results.isEmpty()
                ? response("not_found", "未找到仍然有效的相关长期记忆。", results)
                : response("ok", "找到 " + results.size() + " 条相关长期记忆。", results);
    }

    public Map<String, Object> store(StoreCommand command) {
        validateStore(command);
        boolean correction = command.memoryId() != null;
        var memoryId = correction ? requireUuid(command.memoryId()) : UUID.randomUUID().toString();
        synchronized (memoryLock(command.userId(), memoryId)) {
            var claim = mysql.beginMemoryStore(
                    command.userId(),
                    command.requestId(),
                    command.operationId(),
                    memoryId,
                    command.memoryType(),
                    command.content(),
                    command.importance(),
                    command.source().sessionId(),
                    command.source().messageId(),
                    correction ? null : command.eventTime(),
                    json(new Graph(List.of(), List.of())),
                    correction);
            if (!claim.claimed()) {
                return parse(claim.responseJson());
            }

            Map<String, Object> result;
            String indexStatus;
            try {
                qdrant.upsert(command.userId(), memoryId, command.memoryType(), command.vector());
                result = new LinkedHashMap<>();
                result.put("status", "ok");
                result.put("memory_id", memoryId);
                result.put("memory_type", command.memoryType());
                result.put("importance", command.importance());
                result.put("revision", claim.revision());
                result.put("index_status", "ok");
                result.put("graph_status", graphStatus(command.memoryType()));
                result.put("message", correction ? "长期记忆已纠正。" : "长期记忆已保存。");
                indexStatus = "ok";
            } catch (RuntimeException exception) {
                result = new LinkedHashMap<>();
                result.put("status", "error");
                result.put("memory_id", memoryId);
                result.put("memory_type", command.memoryType());
                result.put("revision", claim.revision());
                result.put("index_status", "error");
                result.put("graph_status", graphStatus(command.memoryType()));
                result.put("message", "长期记忆正文已保存，但检索索引写入失败: " + exception.getMessage());
                indexStatus = "error";
            }
            mysql.completeOperation(
                    command.userId(), command.requestId(), command.operationId(), json(result), indexStatus);
            return result;
        }
    }

    public Map<String, Object> forget(ForgetCommand command) {
        validateForget(command);
        var memoryId = requireUuid(command.memoryId());
        synchronized (memoryLock(command.userId(), memoryId)) {
            var claim = mysql.beginMemoryForget(
                    command.userId(),
                    command.requestId(),
                    command.operationId(),
                    memoryId,
                    command.memoryType());
            if (!claim.claimed()) {
                return parse(claim.responseJson());
            }

            Map<String, Object> result;
            String indexStatus;
            try {
                qdrant.delete(command.userId(), memoryId);
                graphStore.deleteMemory(command.userId(), memoryId);
                result = new LinkedHashMap<>();
                result.put("status", "ok");
                result.put("memory_id", memoryId);
                result.put("memory_type", command.memoryType());
                result.put("deleted", true);
                result.put("index_status", "deleted");
                result.put("message", "长期记忆已删除。");
                indexStatus = "deleted";
            } catch (RuntimeException exception) {
                result = new LinkedHashMap<>();
                result.put("status", "error");
                result.put("memory_id", memoryId);
                result.put("memory_type", command.memoryType());
                result.put("deleted", true);
                result.put("index_status", "error");
                result.put("message", "长期记忆正文已删除，但索引清理失败: " + exception.getMessage());
                indexStatus = "error";
            }
            mysql.completeOperation(
                    command.userId(), command.requestId(), command.operationId(), json(result), indexStatus);
            return result;
        }
    }

    public Map<String, Object> claimSemanticGraph() {
        var job = mysql.claimSemanticGraphJob();
        if (job.isEmpty()) {
            return Map.of("status", "not_found", "message", "没有待处理的语义记忆图谱任务。");
        }
        var value = job.get();
        return Map.of(
                "status", "ok",
                "job", Map.of(
                        "user_id", value.userId(),
                        "memory_id", value.memoryId(),
                        "content", value.content(),
                        "revision", value.revision()));
    }

    public Map<String, Object> completeSemanticGraph(CompleteGraphCommand command) {
        validateCompleteGraph(command);
        var memoryId = requireUuid(command.memoryId());
        synchronized (memoryLock(command.userId(), memoryId)) {
            if (!mysql.isCurrentSemanticGraphJob(command.userId(), memoryId, command.revision())) {
                return Map.of(
                        "status", "stale",
                        "memory_id", memoryId,
                        "revision", command.revision(),
                        "message", "任务对应的记忆已被纠正或删除。"
                );
            }
            var graph = command.graph() == null ? new Graph(List.of(), List.of()) : command.graph();
            if ("ok".equals(command.graphStatus())) {
                graphStore.replaceSemanticMemory(
                        command.userId(), memoryId,
                        entityMaps(graph.entities()), relationMaps(graph.relations()));
            }
            boolean completed = mysql.completeSemanticGraphJob(
                    command.userId(), memoryId, command.revision(), json(graph),
                    command.graphStatus(), command.graphError());
            if (!completed) {
                return Map.of(
                        "status", "stale",
                        "memory_id", memoryId,
                        "revision", command.revision(),
                        "message", "任务完成时记忆版本已经变化。"
                );
            }
            return Map.of(
                    "status", "ok",
                    "memory_id", memoryId,
                    "revision", command.revision(),
                    "graph_status", command.graphStatus(),
                    "message", "语义记忆图谱任务已完成。"
            );
        }
    }

    public Map<String, Object> recoverSemanticGraphs() {
        int recovered = mysql.recoverSemanticGraphJobs();
        return Map.of(
                "status", "ok",
                "recovered", recovered,
                "message", "已恢复 " + recovered + " 个中断的语义记忆图谱任务。"
        );
    }

    private static Map<String, Object> memoryResult(MemoryRecord memory, double score) {
        var result = new LinkedHashMap<String, Object>();
        result.put("memory_id", memory.memoryId());
        result.put("memory_type", memory.memoryType());
        result.put("content", memory.content());
        result.put("importance", memory.importance());
        result.put("status", memory.status());
        result.put("score", score);
        result.put("created_at", memory.createdAt());
        if (memory.eventTime() != null) {
            result.put("event_time", memory.eventTime());
        }
        result.put("source", Map.of(
                "session_id", memory.sourceSessionId(),
                "message_id", memory.sourceMessageId()));
        return result;
    }

    private static Map<String, Object> response(
            String status,
            String message,
            List<Map<String, Object>> results) {
        return Map.of("status", status, "message", message, "results", results);
    }

    private void validateStore(StoreCommand command) {
        requireText(command.userId(), "user_id");
        requireText(command.requestId(), "request_id");
        requireText(command.operationId(), "operation_id");
        requireLongTermType(command.memoryType(), false);
        requireText(command.content(), "content");
        if (!Double.isFinite(command.importance()) || command.importance() < 0 || command.importance() > 1) {
            throw new IllegalArgumentException("importance 必须是 0 到 1 的数值");
        }
        validateVector(command.vector(), "vector");
        if (command.source() == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        requireText(command.source().sessionId(), "source.session_id");
        requireText(command.source().messageId(), "source.message_id");
    }

    private void validateQuery(Query query) {
        requireText(query.userId(), "user_id");
        requireLongTermType(query.memoryType(), true);
        validateVector(query.queryVector(), "query_vector");
        if (query.limit() < 1 || query.limit() > 20) {
            throw new IllegalArgumentException("limit 必须在 1 到 20 之间");
        }
    }

    private static void validateForget(ForgetCommand command) {
        requireText(command.userId(), "user_id");
        requireText(command.requestId(), "request_id");
        requireText(command.operationId(), "operation_id");
        requireLongTermType(command.memoryType(), false);
    }

    private static void validateCompleteGraph(CompleteGraphCommand command) {
        requireText(command.userId(), "user_id");
        if (command.revision() < 1) {
            throw new IllegalArgumentException("revision 必须是正整数");
        }
        if (!("ok".equals(command.graphStatus())
                || "error".equals(command.graphStatus())
                || "skipped".equals(command.graphStatus()))) {
            throw new IllegalArgumentException("graph_status 必须是 ok、error 或 skipped");
        }
        if ("error".equals(command.graphStatus())) {
            requireText(command.graphError(), "graph_error");
        }
    }

    private static void requireLongTermType(String memoryType, boolean allowAll) {
        if (!("semantic".equals(memoryType)
                || "episodic".equals(memoryType)
                || (allowAll && "all".equals(memoryType)))) {
            throw new IllegalArgumentException("memory_type 必须是 semantic、episodic"
                    + (allowAll ? " 或 all" : ""));
        }
    }

    private void validateVector(List<Double> vector, String name) {
        if (vector == null || vector.isEmpty() || vector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException(name + " 必须是非空有限数值数组");
        }
        if (vector.size() != vectorDimension) {
            throw new IllegalArgumentException(
                    name + " 维度必须是 " + vectorDimension + "，实际为 " + vector.size());
        }
    }

    private static int requireVectorDimension(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("向量维度必须是正整数");
        }
        return value;
    }

    private static String requireUuid(String value) {
        requireText(value, "memory_id");
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("长期记忆 memory_id 必须是 UUID", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private List<Map<String, String>> entityMaps(List<Entity> entities) {
        return entities.stream()
                .filter(entity -> entity.name() != null && !entity.name().isBlank())
                .map(entity -> Map.of(
                        "name", entity.name(),
                        "type", entity.type() == null ? "" : entity.type()))
                .toList();
    }

    private List<Map<String, String>> relationMaps(List<Relation> relations) {
        return relations.stream()
                .filter(relation -> relation.subject() != null
                        && relation.relation() != null
                        && relation.object() != null)
                .map(relation -> Map.of(
                        "subject", relation.subject(),
                        "relation", relation.relation(),
                        "object", relation.object()))
                .toList();
    }

    private Object memoryLock(String userId, String memoryId) {
        int index = Math.floorMod(Objects.hash(userId, memoryId), memoryLocks.length);
        return memoryLocks[index];
    }

    private static String graphStatus(String memoryType) {
        return "semantic".equals(memoryType) ? "pending" : "skipped";
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new DataPortException("无法序列化数据服务结果", exception);
        }
    }

    private Map<String, Object> parse(String value) {
        try {
            return mapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            throw new DataPortException("MySQL 中的幂等操作结果不是有效 JSON", exception);
        }
    }
}
