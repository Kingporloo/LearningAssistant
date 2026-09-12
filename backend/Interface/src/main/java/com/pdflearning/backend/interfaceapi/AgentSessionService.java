package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pdflearning.backend.dataport.ChatDataPort;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Pattern;

/** 空会话、正式归属和前端会话视图的生命周期编排。 */
final class AgentSessionService {
    private final ChatDataPort chats;
    private final AgentControlClient controlClient;
    private final ObjectMapper mapper;
    private final PendingSessionRegistry pending = new PendingSessionRegistry();

    AgentSessionService(
            ChatDataPort chats,
            AgentControlClient controlClient,
            ObjectMapper mapper) {
        this.chats = chats;
        this.controlClient = controlClient;
        this.mapper = mapper;
    }

    ArrayNode list(String userId) {
        var values = new ArrayList<JsonNode>();
        chats.listSessions(userId).forEach(session -> values.add(sessionJson(session)));
        pending.listOwned(userId).forEach(session -> values.add(sessionJson(session)));
        values.sort(Comparator.comparing(
                value -> OffsetDateTime.parse(value.path("updatedAt").asText()),
                Comparator.reverseOrder()));
        var result = mapper.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    ObjectNode create(String userId, String title) {
        AgentControlClient.AgentSession created;
        try {
            created = controlClient.createSession(userId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentGatewayException(503, "Agent 会话创建被中断");
        } catch (IOException exception) {
            throw new AgentGatewayException(502, "Python Agent 暂时不可用");
        }
        String sessionId = requireGeneratedSessionId(created.sessionId(), userId);
        var now = now();
        var session = new PendingSessionRegistry.PendingSession(sessionId, userId, title, now);
        if (chats.findSession(sessionId).isPresent() || !pending.add(session)) {
            throw new AgentGatewayException(409, "同一秒内已创建会话，请重新创建");
        }
        return sessionJson(session);
    }

    void delete(String userId, String sessionId) {
        if (pending.removeOwned(userId, sessionId)) {
            return;
        }
        switch (chats.deleteSession(userId, sessionId)) {
            case DELETED -> {
            }
            case BUSY -> throw new AgentGatewayException(409, "会话正在运行，暂时不能删除");
            case NOT_FOUND -> throw new AgentGatewayException(404, "会话不存在");
        }
    }

    ArrayNode messages(String userId, String sessionId) {
        if (pending.findOwned(userId, sessionId).isPresent()) {
            return mapper.createArrayNode();
        }
        requireStored(userId, sessionId);
        var result = mapper.createArrayNode();
        chats.listMessages(userId, sessionId).forEach(message -> result.add(messageJson(message)));
        return result;
    }

    String title(String userId, String sessionId) {
        var pendingSession = pending.findOwned(userId, sessionId);
        if (pendingSession.isPresent()) {
            return pendingSession.get().title();
        }
        return requireStored(userId, sessionId).title();
    }

    ChatDataPort.SessionData requireStored(String userId, String sessionId) {
        return chats.findActiveSession(userId, sessionId)
                .orElseThrow(() -> new AgentGatewayException(404, "会话不存在"));
    }

    void markPersisted(String userId, String sessionId) {
        pending.removeOwned(userId, sessionId);
    }

    private ObjectNode sessionJson(ChatDataPort.SessionData session) {
        var value = mapper.createObjectNode();
        value.put("id", session.sessionId());
        value.put("title", session.title());
        value.put("createdAt", session.createdAt().toString());
        value.put("updatedAt", session.updatedAt().toString());
        value.put("messageCount", session.messageCount());
        return value;
    }

    private ObjectNode sessionJson(PendingSessionRegistry.PendingSession session) {
        var value = mapper.createObjectNode();
        value.put("id", session.sessionId());
        value.put("title", session.title());
        value.put("createdAt", session.createdAt().toString());
        value.put("updatedAt", session.createdAt().toString());
        value.put("messageCount", 0);
        return value;
    }

    private ObjectNode messageJson(ChatDataPort.MessageData message) {
        var value = mapper.createObjectNode();
        value.put("id", message.messageId());
        value.put("role", message.role());
        value.put("content", message.content());
        value.put("createdAt", message.createdAt().toString());
        if ("assistant".equals(message.role())) {
            value.put("status", message.status());
            value.set("segments", message.segments() == null
                    ? mapper.createArrayNode()
                    : message.segments().deepCopy());
            putIfPresent(value, "usage", message.usage());
            putIfPresent(value, "error", message.error());
            if (message.modelSteps() != null) {
                value.put("modelSteps", message.modelSteps());
            }
            if (message.toolRounds() != null) {
                value.put("toolRounds", message.toolRounds());
            }
        }
        return value;
    }

    private static String requireGeneratedSessionId(String value, String userId) {
        var expected = Pattern.compile(
                "session_\\d{8}_\\d{6}_" + Pattern.quote(userId));
        if (!expected.matcher(value).matches()) {
            throw new AgentGatewayException(502, "Python Agent 返回了无效的 session_id");
        }
        return value;
    }

    private static void putIfPresent(ObjectNode target, String name, JsonNode value) {
        if (value != null && !value.isNull()) {
            target.set(name, value.deepCopy());
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
