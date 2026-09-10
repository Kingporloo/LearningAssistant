package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;

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
}
