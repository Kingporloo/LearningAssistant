package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pdflearning.backend.dataport.ChatDataPort;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将已持久化的运行事件归约为前端可直接读取的一条助手消息。 */
final class AgentRunTranscript {
    private final ObjectMapper mapper;
    private final ArrayNode segments;
    private final Map<String, ObjectNode> toolCalls = new LinkedHashMap<>();

    private String content = "";
    private JsonNode usage;
    private JsonNode error;
    private Integer modelSteps;
    private Integer toolRounds;
    private boolean completedMessage;
    private String runStatus;

    AgentRunTranscript(ObjectMapper mapper) {
        this.mapper = mapper;
        this.segments = mapper.createArrayNode();
    }

    void accept(AgentEvent event) {
        var payload = event.payload();
        switch (event.type()) {
            case "text_delta" -> appendText(payload);
            case "tool_started" -> startTool(payload);
            case "tool_finished" -> finishTool(payload);
            case "model_step_finished" -> copyUsage(payload);
            case "message_completed" -> completeMessage(payload);
            case "error" -> error = payload.deepCopy();
            case "run_finished" -> finishRun(payload);
            default -> {
                // 其他状态事件已保存在 agent_run_event，不属于聊天消息视图。
            }
        }
    }

    ChatDataPort.AssistantWrite toMessage(
            String userId,
            String sessionId,
            String requestId,
            OffsetDateTime createdAt) {
        boolean completed = "completed".equals(runStatus) && completedMessage;
        if (!completed && error == null) {
            error = error("agent_run_interrupted", "智能体运行未完整结束", "agent", true);
        }
        return new ChatDataPort.AssistantWrite(
                assistantMessageId(requestId),
                userId,
                sessionId,
                requestId,
                completed ? "completed" : "failed",
                content,
                segments,
                usage,
                modelSteps,
                toolRounds,
                error,
                createdAt);
    }

    void fail(String code, String message, boolean retryable) {
        runStatus = "failed";
        error = error(code, message, "gateway", retryable);
    }

    boolean terminal() {
        return runStatus != null;
    }

    private void appendText(JsonNode payload) {
        String delta = payload.path("delta").asText("");
        int modelStep = payload.path("model_step").asInt(1);
        if (delta.isEmpty()) {
            return;
        }
        JsonNode last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        if (last != null
                && "text".equals(last.path("kind").asText())
                && last.path("modelStep").asInt() == modelStep) {
            ((ObjectNode) last).put("text", last.path("text").asText() + delta);
            return;
        }
        var segment = mapper.createObjectNode();
        segment.put("kind", "text");
        segment.put("text", delta);
        segment.put("modelStep", modelStep);
        segments.add(segment);
    }

    private void startTool(JsonNode payload) {
        String callId = payload.path("tool_call_id").asText("");
        var call = mapper.createObjectNode();
        call.put("toolCallId", callId);
        call.put("name", payload.path("name").asText(""));
        var arguments = payload.get("arguments");
        call.set("arguments", arguments != null && arguments.isObject()
                ? arguments.deepCopy()
                : mapper.createObjectNode());
        call.put("status", "skipped".equals(payload.path("outcome").asText())
                ? "skipped"
                : "running");
        toolCalls.put(callId, call);

        var segment = mapper.createObjectNode();
        segment.put("kind", "tool");
        segment.set("toolCall", call);
        segments.add(segment);
    }

    private void finishTool(JsonNode payload) {
        String callId = payload.path("tool_call_id").asText("");
        var call = toolCalls.get(callId);
        if (call == null) {
            return;
        }
        String outcome = payload.path("outcome").asText("");
        call.put("status", switch (outcome) {
            case "completed" -> "completed";
            case "skipped" -> "skipped";
            default -> "error";
        });
        call.put("outcome", outcome);
        putNullableText(call, "businessStatus", payload.get("business_status"));
        call.put("content", payload.path("content").asText(""));
        if (payload.has("result")) {
            call.set("result", payload.get("result").deepCopy());
        }
    }

    private void copyUsage(JsonNode payload) {
        if (payload.has("usage") && payload.get("usage").isObject()) {
            usage = payload.get("usage").deepCopy();
        }
    }

    private void completeMessage(JsonNode payload) {
        content = payload.path("content").asText("");
        completedMessage = true;
        copyUsage(payload);
    }

    private void finishRun(JsonNode payload) {
        runStatus = payload.path("status").asText("");
        modelSteps = nonNegativeInt(payload.get("model_steps"));
        toolRounds = nonNegativeInt(payload.get("tool_rounds"));
        copyUsage(payload);
    }

    private ObjectNode error(String code, String message, String phase, boolean retryable) {
        var value = mapper.createObjectNode();
        value.put("code", code);
        value.put("message", message);
        value.put("phase", phase);
        value.put("retryable", retryable);
        return value;
    }

    private static void putNullableText(ObjectNode target, String name, JsonNode value) {
        if (value == null || value.isNull()) {
            target.putNull(name);
        } else {
            target.put(name, value.asText());
        }
    }

    private static Integer nonNegativeInt(JsonNode value) {
        return value != null && value.canConvertToInt() && value.intValue() >= 0
                ? value.intValue()
                : null;
    }

    static String assistantMessageId(String requestId) {
        return "assistant_" + requestId;
    }
}
