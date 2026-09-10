package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Java 已完成身份与会话处理后，交给 Python Agent 的一次运行快照。 */
public record AgentRunRequest(
        @JsonProperty("user_id") String userId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("message_id") String messageId,
        String message,
        @JsonProperty("recent_history") JsonNode recentHistory,
        @JsonProperty("execution_history") JsonNode executionHistory,
        @JsonProperty("history_cursor") String historyCursor,
        @JsonProperty("session_summary") JsonNode sessionSummary,
        @JsonProperty("session_ledger") JsonNode sessionLedger,
        @JsonProperty("agent_config") AgentConfig agentConfig) {

    public AgentRunRequest {
        userId = required(userId, "userId");
        sessionId = required(sessionId, "sessionId");
        requestId = required(requestId, "requestId");
        messageId = required(messageId, "messageId");
        message = required(message, "message");
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

    public record AgentConfig(
            @JsonProperty("model_window") int modelWindow,
            @JsonProperty("max_context_tokens") int maxContextTokens,
            @JsonProperty("output_reserve") int outputReserve,
            @JsonProperty("safety_margin") int safetyMargin,
            @JsonProperty("tool_result_reserve") int toolResultReserve,
            @JsonProperty("compact_trigger_ratio") double compactTriggerRatio,
            @JsonProperty("summary_max_tokens") int summaryMaxTokens,
            @JsonProperty("keep_recent_turns") int keepRecentTurns) {

        public AgentConfig(
                int modelWindow,
                int maxContextTokens,
                int outputReserve,
                int safetyMargin) {
            this(modelWindow, maxContextTokens, outputReserve, safetyMargin, 0, 0.92, 2_000, 5);
        }

        public AgentConfig {
            if (modelWindow <= 0
                    || maxContextTokens <= 0
                    || outputReserve < 0
                    || safetyMargin < 0
                    || toolResultReserve < 0
                    || summaryMaxTokens <= 0
                    || keepRecentTurns <= 0) {
                throw new IllegalArgumentException("Agent 上下文配置包含无效数值");
            }
            long reserved = (long) maxContextTokens
                    + outputReserve
                    + safetyMargin
                    + toolResultReserve;
            if (reserved > modelWindow) {
                throw new IllegalArgumentException("Agent 上下文配置超过模型窗口");
            }
            if (!Double.isFinite(compactTriggerRatio)
                    || compactTriggerRatio <= 0
                    || compactTriggerRatio >= 1) {
                throw new IllegalArgumentException("compactTriggerRatio 必须在 0 到 1 之间");
            }
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
