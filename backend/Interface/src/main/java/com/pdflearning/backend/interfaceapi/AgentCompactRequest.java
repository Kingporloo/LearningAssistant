package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Java 为手动 Compact 提供的可信会话快照。 */
public record AgentCompactRequest(
        @JsonProperty("user_id") String userId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("recent_history") JsonNode recentHistory,
        @JsonProperty("execution_history") JsonNode executionHistory,
        @JsonProperty("history_cursor") String historyCursor,
        @JsonProperty("session_summary") JsonNode sessionSummary,
        @JsonProperty("session_ledger") JsonNode sessionLedger,
        @JsonProperty("agent_config") AgentRunRequest.AgentConfig agentConfig) {

    public AgentCompactRequest {
        userId = required(userId, "userId");
        sessionId = required(sessionId, "sessionId");
        requestId = required(requestId, "requestId");
        recentHistory = array(recentHistory, "recentHistory");
        executionHistory = array(executionHistory, "executionHistory");
        sessionSummary = objectOrNull(sessionSummary, "sessionSummary");
        sessionLedger = objectOrNull(sessionLedger, "sessionLedger");
        if (historyCursor != null) {
            historyCursor = required(historyCursor, "historyCursor");
        }
        if (agentConfig == null) {
            throw new IllegalArgumentException("agentConfig 不能为空");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.strip();
    }

    private static JsonNode array(JsonNode value, String name) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(name + " 必须是 JSON 数组");
        }
        return value.deepCopy();
    }

    private static JsonNode objectOrNull(JsonNode value, String name) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(name + " 必须是 JSON 对象或 null");
        }
        return value.deepCopy();
    }
}
