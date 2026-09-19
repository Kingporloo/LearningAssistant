package com.learningassistant.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.learningassistant.backend.dataport.ContextSummaryDataPort;
import com.learningassistant.backend.dataport.MemoryDataPort;
import com.learningassistant.backend.dataport.RagDataPort;
import com.learningassistant.backend.dataport.SessionArchiveDataPort;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class DataPortRequestHandler {
    enum Operation {
        RAG_SEARCH,
        RAG_GRAPH,
        MEMORY_QUERY,
        MEMORY_STORE,
        MEMORY_FORGET,
        HISTORY_SEARCH,
        HISTORY_READ,
        CONTEXT_SUMMARY_STORE
    }

    enum WorkerOperation {
        MEMORY_GRAPH_CLAIM,
        MEMORY_GRAPH_COMPLETE,
        MEMORY_GRAPH_RECOVER
    }

    private final ContextSummaryDataPort summaries;
    private final MemoryDataPort memory;
    private final RagDataPort rag;
    private final SessionArchiveDataPort archive;

    DataPortRequestHandler(
            MemoryDataPort memory,
            RagDataPort rag,
            ContextSummaryDataPort summaries,
            SessionArchiveDataPort archive) {
        this.memory = memory;
        this.rag = rag;
        this.summaries = summaries;
        this.archive = archive;
    }

    Map<String, Object> handle(
            Operation operation,
            InternalRequestContext context,
            JsonNode body) {
        if (!body.isObject()) {
            throw new IllegalArgumentException("请求正文必须是 JSON 对象");
        }
        return switch (operation) {
            case RAG_SEARCH -> rag.search(
                    context.userId(), vector(body, "query_vector"), integer(body, "limit"));
            case RAG_GRAPH -> rag.graphSearch(
                    context.userId(), strings(body, "seed_chunk_ids"),
                    vector(body, "query_vector"), integer(body, "limit"));
            case MEMORY_QUERY -> memory.query(memoryQuery(context, body));
            case MEMORY_STORE -> memory.store(memoryStore(context, body));
            case MEMORY_FORGET -> memory.forget(memoryForget(context, body));
            case HISTORY_SEARCH -> historySearch(context, body);
            case HISTORY_READ -> historyRead(context, body);
            case CONTEXT_SUMMARY_STORE -> summaries.store(contextSummary(context, body));
        };
    }

    private Map<String, Object> historySearch(
            InternalRequestContext context,
            JsonNode body) {
        var hits = archive.search(
                context.userId(), context.sessionId(),
                requiredText(body, "history_cursor"),
                requiredText(body, "query"),
                integer(body, "top_k"));
        var results = new ArrayList<Map<String, Object>>();
        for (var hit : hits) {
            results.add(Map.of(
                    "message_id", hit.messageId(),
                    "span_id", hit.spanId(),
                    "role", hit.role(),
                    "preview", hit.preview(),
                    "score", hit.score(),
                    "created_at", hit.createdAt()));
        }
        return Map.of(
                "status", results.isEmpty() ? "not_found" : "ok",
                "results", results,
                "message", results.isEmpty() ? "未找到相关历史原文。" : "找到相关历史原文。");
    }

    private Map<String, Object> historyRead(
            InternalRequestContext context,
            JsonNode body) {
        var refs = new ArrayList<SessionArchiveDataPort.Ref>();
        for (var item : array(body, "refs")) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("refs 的元素必须是对象");
            }
            refs.add(new SessionArchiveDataPort.Ref(
                    requiredText(item, "message_id"),
                    requiredText(item, "span_id")));
        }
        var values = archive.read(
                context.userId(), context.sessionId(),
                requiredText(body, "history_cursor"), refs);
        var results = new ArrayList<Map<String, Object>>();
        for (var value : values) {
            results.add(Map.of(
                    "message_id", value.messageId(),
                    "span_id", value.spanId(),
                    "role", value.role(),
                    "content", value.content(),
                    "created_at", value.createdAt()));
        }
        return Map.of(
                "status", results.isEmpty() ? "not_found" : "ok",
                "results", results,
                "message", results.isEmpty() ? "历史原文不存在。" : "历史原文读取成功。");
    }

    Map<String, Object> handleWorker(WorkerOperation operation, JsonNode body) {
        if (!body.isObject()) {
            throw new IllegalArgumentException("请求正文必须是 JSON 对象");
        }
        return switch (operation) {
            case MEMORY_GRAPH_CLAIM -> memory.claimSemanticGraph();
            case MEMORY_GRAPH_COMPLETE -> memory.completeSemanticGraph(memoryGraphComplete(body));
            case MEMORY_GRAPH_RECOVER -> memory.recoverSemanticGraphs();
        };
    }

    static ContextSummaryDataPort.StoreCommand contextSummary(
            InternalRequestContext context,
            JsonNode body) {
        return new ContextSummaryDataPort.StoreCommand(
                context.userId(),
                context.sessionId(),
                context.requestId(),
                requiredText(body, "operation_id"),
                nonNegativeInteger(body, "base_version"),
                optionalText(body, "history_cursor"),
                optionalText(body, "through_message_id"),
                sourceRefs(body),
                requiredText(body, "text"));
    }

    static MemoryDataPort.Query memoryQuery(
            InternalRequestContext context,
            JsonNode body) {
        if (!"user".equals(requiredText(body, "scope"))) {
            throw new IllegalArgumentException("scope 只允许为 user");
        }
        return new MemoryDataPort.Query(
                context.userId(),
                requiredText(body, "memory_type"),
                vector(body, "query_vector"),
                integer(body, "limit"));
    }

    static MemoryDataPort.StoreCommand memoryStore(
            InternalRequestContext context,
            JsonNode body) {
        var messageId = context.requireMessageId();
        var source = object(body, "source");
        if (!context.sessionId().equals(requiredText(source, "session_id"))
                || !messageId.equals(requiredText(source, "message_id"))) {
            throw new IllegalArgumentException("source 必须与可信会话和消息上下文一致");
        }
        return new MemoryDataPort.StoreCommand(
                context.userId(),
                context.requestId(),
                requiredText(body, "operation_id"),
                optionalText(body, "memory_id"),
                requiredText(body, "memory_type"),
                requiredText(body, "content"),
                number(body, "importance"),
                vector(body, "vector"),
                new MemoryDataPort.Source(context.sessionId(), messageId),
                optionalTime(body, "event_time"));
    }

    static MemoryDataPort.CompleteGraphCommand memoryGraphComplete(JsonNode body) {
        return new MemoryDataPort.CompleteGraphCommand(
                requiredText(body, "user_id"),
                requiredText(body, "memory_id"),
                positiveInteger(body, "revision"),
                requiredText(body, "graph_status"),
                optionalText(body, "graph_error"),
                graph(body.get("graph")));
    }

    static MemoryDataPort.ForgetCommand memoryForget(
            InternalRequestContext context,
            JsonNode body) {
        return new MemoryDataPort.ForgetCommand(
                context.userId(),
                context.requestId(),
                requiredText(body, "operation_id"),
                requiredText(body, "memory_id"),
                requiredText(body, "memory_type"));
    }

    private static MemoryDataPort.Graph graph(JsonNode node) {
        if (node == null || node.isNull()) {
            return new MemoryDataPort.Graph(List.of(), List.of());
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("graph 必须是 JSON 对象");
        }
        var entities = new ArrayList<MemoryDataPort.Entity>();
        for (var item : array(node, "entities")) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("graph.entities 的元素必须是对象");
            }
            var type = optionalText(item, "type");
            entities.add(new MemoryDataPort.Entity(
                    requiredText(item, "name"), type == null ? "" : type));
        }
        var relations = new ArrayList<MemoryDataPort.Relation>();
        for (var item : array(node, "relations")) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("graph.relations 的元素必须是对象");
            }
            relations.add(new MemoryDataPort.Relation(
                    requiredText(item, "subject"),
                    requiredText(item, "relation"),
                    requiredText(item, "object")));
        }
        return new MemoryDataPort.Graph(entities, relations);
    }

    private static JsonNode object(JsonNode body, String name) {
        var value = body.get(name);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(name + " 必须是 JSON 对象");
        }
        return value;
    }

    private static List<JsonNode> array(JsonNode body, String name) {
        var value = body.get(name);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(name + " 必须是数组");
        }
        var result = new ArrayList<JsonNode>();
        value.forEach(result::add);
        return result;
    }

    private static List<Double> vector(JsonNode body, String name) {
        var values = array(body, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " 必须是非空数组");
        }
        var result = new ArrayList<Double>(values.size());
        for (var value : values) {
            if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                throw new IllegalArgumentException(name + " 只能包含有限数值");
            }
            result.add(value.doubleValue());
        }
        return List.copyOf(result);
    }

    private static List<String> strings(JsonNode body, String name) {
        var values = array(body, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " 必须是非空数组");
        }
        var result = new ArrayList<String>(values.size());
        for (var value : values) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException(name + " 只能包含非空字符串");
            }
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode body, String name) {
        var value = optionalText(body, name);
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static String optionalText(JsonNode body, String name) {
        var value = body.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(name + " 必须是非空字符串或 null");
        }
        return value.textValue();
    }

    private static int integer(JsonNode body, String name) {
        var value = body.get(name);
        if (value == null || !value.canConvertToInt() || !value.isIntegralNumber()) {
            throw new IllegalArgumentException(name + " 必须是整数");
        }
        return value.intValue();
    }

    private static int nonNegativeInteger(JsonNode body, String name) {
        int value = integer(body, name);
        if (value < 0) {
            throw new IllegalArgumentException(name + " 不能小于 0");
        }
        return value;
    }

    private static int positiveInteger(JsonNode body, String name) {
        int value = integer(body, name);
        if (value < 1) {
            throw new IllegalArgumentException(name + " 必须是正整数");
        }
        return value;
    }

    private static JsonNode sourceRefs(JsonNode body) {
        var refs = body.get("source_refs");
        if (refs == null || !refs.isArray()) {
            throw new IllegalArgumentException("source_refs 必须是数组");
        }
        for (var ref : refs) {
            if (!ref.isObject()) {
                throw new IllegalArgumentException("source_refs 的元素必须是对象");
            }
        }
        return refs.deepCopy();
    }

    private static double number(JsonNode body, String name) {
        var value = body.get(name);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(name + " 必须是有限数值");
        }
        return value.doubleValue();
    }

    private static OffsetDateTime optionalTime(JsonNode body, String name) {
        var value = optionalText(body, name);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(name + " 必须是带时区的 ISO-8601 时间", exception);
        }
    }
}
