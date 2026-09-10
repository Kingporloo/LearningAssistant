package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Python AgentLoop 发出的一个完整 SSE 事件。 */
public record AgentEvent(
        String type,
        String requestId,
        String sessionId,
        long eventSeq,
        JsonNode payload) {

    public AgentEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Agent 事件 type 不能为空");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Agent 事件 requestId 不能为空");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Agent 事件 sessionId 不能为空");
        }
        if (eventSeq <= 0) {
            throw new IllegalArgumentException("Agent 事件 eventSeq 必须是正整数");
        }
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Agent 事件 payload 必须是 JSON 对象");
        }
        type = type.strip();
        requestId = requestId.strip();
        sessionId = sessionId.strip();
        payload = payload.deepCopy();
    }

    static AgentEvent fromJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Agent 事件必须是 JSON 对象");
        }
        JsonNode sequence = root.get("event_seq");
        if (sequence == null || !sequence.isIntegralNumber() || !sequence.canConvertToLong()) {
            throw new IllegalArgumentException("Agent 事件 event_seq 必须是整数");
        }
        JsonNode payload = root.get("payload");
        return new AgentEvent(
                requiredText(root, "type"),
                requiredText(root, "request_id"),
                requiredText(root, "session_id"),
                sequence.longValue(),
                payload);
    }

    ObjectNode toJson(ObjectMapper mapper) {
        var root = mapper.createObjectNode();
        root.put("type", type);
        root.put("request_id", requestId);
        root.put("session_id", sessionId);
        root.put("event_seq", eventSeq);
        root.set("payload", payload);
        return root;
    }

    private static String requiredText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Agent 事件 " + name + " 必须是非空字符串");
        }
        return value.textValue();
    }
}
