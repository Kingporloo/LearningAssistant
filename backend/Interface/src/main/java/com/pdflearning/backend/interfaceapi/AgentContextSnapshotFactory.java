package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.pdflearning.backend.dataport.ChatDataPort;
import com.pdflearning.backend.dataport.ContextSummaryDataPort;

/** 从 Java 持久化数据构造 Python Agent 所需的可信上下文快照。 */
final class AgentContextSnapshotFactory {
    record Snapshot(
            ArrayNode recentHistory,
            String historyCursor,
            JsonNode sessionSummary) {
    }

    private final ChatDataPort chats;
    private final ContextSummaryDataPort summaries;
    private final ObjectMapper mapper;

    AgentContextSnapshotFactory(
            ChatDataPort chats,
            ContextSummaryDataPort summaries,
            ObjectMapper mapper) {
        this.chats = chats;
        this.summaries = summaries;
        this.mapper = mapper;
    }

    Snapshot load(String userId, String sessionId) {
        var storedSummary = summaries.find(userId, sessionId).orElse(null);
        ChatDataPort.HistorySlice history;
        if (storedSummary == null) {
            history = chats.readDialogueHistory(userId, sessionId, null);
            return new Snapshot(history(history.turns()), null, null);
        }

        history = chats.readDialogueHistory(
                userId, sessionId, storedSummary.throughMessageId());
        if (!history.cursorFound()) {
            var full = chats.readDialogueHistory(userId, sessionId, null);
            return new Snapshot(history(full.turns()), null, null);
        }
        return new Snapshot(
                history(history.turns()),
                storedSummary.historyCursor(),
                summary(storedSummary));
    }

    AgentRunRequest runRequest(
            Snapshot snapshot,
            String userId,
            String sessionId,
            String requestId,
            String messageId,
            String message,
            AgentRunRequest.AgentConfig config) {
        return new AgentRunRequest(
                userId,
                sessionId,
                requestId,
                messageId,
                message,
                snapshot.recentHistory(),
                mapper.createArrayNode(),
                snapshot.historyCursor(),
                snapshot.sessionSummary(),
                null,
                config);
    }

    AgentCompactRequest compactRequest(
            Snapshot snapshot,
            String userId,
            String sessionId,
            String requestId,
            AgentRunRequest.AgentConfig config) {
        return new AgentCompactRequest(
                userId,
                sessionId,
                requestId,
                snapshot.recentHistory(),
                mapper.createArrayNode(),
                snapshot.historyCursor(),
                snapshot.sessionSummary(),
                null,
                config);
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
        if (summary.throughMessageId() == null) {
            value.putNull("through_message_id");
        } else {
            value.put("through_message_id", summary.throughMessageId());
        }
        value.set("source_refs", summary.sourceRefs().deepCopy());
        return value;
    }
}
