package com.learningassistant.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.learningassistant.backend.dataport.AgentRunDataPort;
import com.learningassistant.backend.dataport.ChatDataPort;
import com.learningassistant.backend.dataport.ContextSummaryDataPort;
import com.learningassistant.backend.dataport.SessionLedgerDataPort;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;

/** 从 Java 持久化数据构造 Python Agent 所需的可信上下文快照。 */
final class AgentContextSnapshotFactory {
    record Snapshot(
            ArrayNode recentHistory,
            ArrayNode executionHistory,
            String historyCursor,
            JsonNode sessionSummary,
            JsonNode sessionLedger) {
    }

    private static final class ExecutionGroup {
        private final int modelStep;
        private final StringBuilder assistantContent = new StringBuilder();
        private final LinkedHashMap<String, ObjectNode> calls = new LinkedHashMap<>();
        private final LinkedHashMap<String, ObjectNode> results = new LinkedHashMap<>();

        private ExecutionGroup(int modelStep) {
            this.modelStep = modelStep;
        }
    }

    private final ChatDataPort chats;
    private final AgentRunDataPort runs;
    private final ContextSummaryDataPort summaries;
    private final SessionLedgerDataPort ledgers;
    private final ObjectMapper mapper;

    AgentContextSnapshotFactory(
            ChatDataPort chats,
            AgentRunDataPort runs,
            ContextSummaryDataPort summaries,
            SessionLedgerDataPort ledgers,
            ObjectMapper mapper) {
        this.chats = chats;
        this.runs = runs;
        this.summaries = summaries;
        this.ledgers = ledgers;
        this.mapper = mapper;
    }

    Snapshot load(String userId, String sessionId) {
        var full = chats.readDialogueHistory(userId, sessionId, null);
        String historyCursor = lastMessageId(full);
        var storedSummary = summaries.find(userId, sessionId).orElse(null);
        ChatDataPort.HistorySlice history = full;
        if (storedSummary != null && storedSummary.usable()) {
            history = chats.readDialogueHistory(
                    userId, sessionId, storedSummary.throughMessageId());
            if (!history.cursorFound()) {
                storedSummary = unusable(storedSummary, "summary_dialogue_cursor_missing");
                history = full;
            }
        }
        if (historyCursor == null && storedSummary != null) {
            historyCursor = storedSummary.historyCursor();
        }

        var coveredToolRefs = storedSummary != null && storedSummary.usable()
                ? coveredToolRefs(storedSummary.sourceRefs())
                : java.util.Set.<String>of();
        return new Snapshot(
                history(history.turns()),
                executionHistory(userId, full.turns(), coveredToolRefs),
                historyCursor,
                storedSummary == null ? null : summary(storedSummary),
                ledgers.find(userId, sessionId).map(this::ledger).orElse(null));
    }

    AgentRunRequest runRequest(
            Snapshot snapshot,
            String userId,
            String sessionId,
            String requestId,
            String messageId,
            String message,
            AgentRunRequest.AgentConfig config,
            AgentRunRequest.RuntimeConfig runtimeConfig) {
        return new AgentRunRequest(
                userId,
                sessionId,
                requestId,
                messageId,
                message,
                snapshot.recentHistory(),
                snapshot.executionHistory(),
                snapshot.historyCursor(),
                snapshot.sessionSummary(),
                snapshot.sessionLedger(),
                config,
                runtimeConfig);
    }

    AgentCompactRequest compactRequest(
            Snapshot snapshot,
            String userId,
            String sessionId,
            String requestId,
            AgentRunRequest.AgentConfig config,
            AgentRunRequest.RuntimeConfig runtimeConfig) {
        return new AgentCompactRequest(
                userId,
                sessionId,
                requestId,
                snapshot.recentHistory(),
                snapshot.executionHistory(),
                snapshot.historyCursor(),
                snapshot.sessionSummary(),
                snapshot.sessionLedger(),
                config,
                runtimeConfig);
    }

    private ArrayNode history(java.util.List<ChatDataPort.DialogueTurn> turns) {
        var result = mapper.createArrayNode();
        for (var turn : turns) {
            var item = mapper.createObjectNode();
            item.set("user_message", message(turn.userMessage()));
            item.set("assistant_message", message(turn.assistantMessage()));
            result.add(item);
        }
        return result;
    }

    private ArrayNode executionHistory(
            String userId,
            java.util.List<ChatDataPort.DialogueTurn> turns,
            java.util.Set<String> coveredToolRefs) {
        var result = mapper.createArrayNode();
        for (var turn : turns) {
            String requestId = turn.assistantMessage().requestId();
            var groups = new TreeMap<Integer, ExecutionGroup>();
            var callGroups = new LinkedHashMap<String, ExecutionGroup>();
            for (var event : runs.readEvents(userId, requestId)) {
                JsonNode root;
                try {
                    root = mapper.readTree(event.eventJson());
                } catch (Exception exception) {
                    throw new IllegalStateException("数据库中的 Agent 事件无效", exception);
                }
                var payload = root.path("payload");
                switch (event.eventType()) {
                    case "text_delta" -> group(groups, payload).assistantContent
                            .append(payload.path("delta").asText(""));
                    case "tool_started" -> {
                        var group = group(groups, payload);
                        String rawCallId = payload.path("tool_call_id").asText("");
                        if (rawCallId.isBlank()) {
                            continue;
                        }
                        var call = mapper.createObjectNode();
                        call.put("tool_call_id", namespacedCallId(requestId, rawCallId));
                        call.put("name", payload.path("name").asText(""));
                        var arguments = payload.get("arguments");
                        call.set("arguments", arguments != null && arguments.isObject()
                                ? arguments.deepCopy()
                                : mapper.createObjectNode());
                        group.calls.put(rawCallId, call);
                        callGroups.put(rawCallId, group);
                    }
                    case "tool_finished" -> {
                        String rawCallId = payload.path("tool_call_id").asText("");
                        var group = callGroups.get(rawCallId);
                        if (group == null) {
                            continue;
                        }
                        var toolResult = mapper.createObjectNode();
                        toolResult.put("tool_call_id", namespacedCallId(requestId, rawCallId));
                        toolResult.put("name", payload.path("name").asText(""));
                        toolResult.put("content", payload.path("content").asText(""));
                        toolResult.put("outcome", payload.path("outcome").asText("completed"));
                        if (payload.hasNonNull("business_status")) {
                            toolResult.put("business_status", payload.path("business_status").asText());
                        } else {
                            toolResult.putNull("business_status");
                        }
                        group.results.put(rawCallId, toolResult);
                    }
                    default -> {
                    }
                }
            }
            for (var group : groups.values()) {
                if (group.calls.isEmpty()
                        || !group.calls.keySet().equals(group.results.keySet())
                        || group.calls.keySet().stream()
                                .map(call -> namespacedCallId(requestId, call))
                                .anyMatch(coveredToolRefs::contains)) {
                    continue;
                }
                var item = mapper.createObjectNode();
                item.put("group_id", requestId + ":" + group.modelStep);
                item.put("assistant_content", group.assistantContent.toString());
                var calls = item.putArray("tool_calls");
                group.calls.values().forEach(calls::add);
                var results = item.putArray("tool_results");
                group.results.values().forEach(results::add);
                result.add(item);
            }
        }
        return result;
    }

    private static ExecutionGroup group(
            Map<Integer, ExecutionGroup> groups,
            JsonNode payload) {
        int modelStep = payload.path("model_step").asInt(1);
        return groups.computeIfAbsent(modelStep, ExecutionGroup::new);
    }

    private JsonNode message(ChatDataPort.MessageData message) {
        var value = mapper.createObjectNode();
        value.put("message_id", message.messageId());
        value.put("content", message.content());
        value.put("created_at", message.createdAt().toString());
        return value;
    }

    private JsonNode summary(ContextSummaryDataPort.StoredSummary summary) {
        var value = mapper.createObjectNode();
        value.put("version", summary.version());
        value.put("text", summary.text());
        putNullable(value, "through_message_id", summary.throughMessageId());
        value.set("source_refs", summary.sourceRefs().deepCopy());
        value.put("usable", summary.usable());
        putNullable(value, "invalid_reason", summary.invalidReason());
        return value;
    }

    private JsonNode ledger(SessionLedgerDataPort.StoredLedger ledger) {
        var value = mapper.createObjectNode();
        value.put("version", ledger.version());
        putNullable(value, "compacted_through_message_id", ledger.compactedThroughMessageId());
        value.set("entries", ledger.entries().deepCopy());
        return value;
    }

    private static String lastMessageId(ChatDataPort.HistorySlice history) {
        if (history.turns().isEmpty()) {
            return null;
        }
        return history.turns().getLast().assistantMessage().messageId();
    }

    private static ContextSummaryDataPort.StoredSummary unusable(
            ContextSummaryDataPort.StoredSummary summary,
            String reason) {
        return new ContextSummaryDataPort.StoredSummary(
                summary.version(), summary.text(), summary.throughMessageId(),
                summary.historyCursor(), summary.sourceRefs(), false, reason);
    }

    private static java.util.Set<String> coveredToolRefs(JsonNode refs) {
        var result = new LinkedHashSet<String>();
        for (var ref : refs) {
            if ("tool_event".equals(ref.path("kind").asText())
                    && ref.path("ref_id").isTextual()) {
                result.add(ref.path("ref_id").asText());
            }
        }
        return result;
    }

    private static String namespacedCallId(String requestId, String callId) {
        return requestId + ":" + callId;
    }

    private static void putNullable(ObjectNode value, String name, String content) {
        if (content == null) {
            value.putNull(name);
        } else {
            value.put(name, content);
        }
    }
}
