package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** 调用 Python Agent 的会话创建和手动 Compact JSON 接口。 */
public final class AgentControlClient {
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;
    private static final int MAX_JSON_BODY_BYTES = 2 * 1024 * 1024;

    private final URI sessionUri;
    private final URI compactUri;
    private final String internalToken;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public AgentControlClient(URI baseUri, String internalToken) {
        this(
                baseUri,
                internalToken,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper());
    }

    AgentControlClient(
            URI baseUri,
            String internalToken,
            HttpClient httpClient,
            ObjectMapper mapper) {
        if (baseUri == null
                || baseUri.getScheme() == null
                || baseUri.getHost() == null
                || !("http".equalsIgnoreCase(baseUri.getScheme())
                || "https".equalsIgnoreCase(baseUri.getScheme()))) {
            throw new IllegalArgumentException("Python Agent baseUri 必须是 HTTP(S) 地址");
        }
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalArgumentException("Python 内部服务 token 不能为空");
        }
        this.sessionUri = baseUri.resolve("/internal/agent/sessions");
        this.compactUri = baseUri.resolve("/internal/agent/context/compact");
        this.internalToken = internalToken;
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    public static AgentControlClient fromEnvironment() {
        Map<String, String> environment = System.getenv();
        String baseUrl = environment.getOrDefault(
                "PYTHON_AGENT_BASE_URL", "http://127.0.0.1:8800");
        String token = environment.get("PYTHON_INTERNAL_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("缺少环境变量 PYTHON_INTERNAL_TOKEN");
        }
        try {
            return new AgentControlClient(URI.create(baseUrl), token);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PYTHON_AGENT_BASE_URL 无效", exception);
        }
    }

    public AgentSession createSession(String userId)
            throws IOException, InterruptedException {
        userId = required(userId, "userId");
        var body = mapper.createObjectNode().put("user_id", userId);
        var response = sendJson(jsonRequest(sessionUri, userId, null, null, body));
        String returnedUserId = requiredText(response, "user_id");
        if (!userId.equals(returnedUserId)) {
            throw new IOException("Python Agent 返回了不匹配的 user_id");
        }
        return new AgentSession(returnedUserId, requiredText(response, "session_id"));
    }

    public AgentCompactResult compactContext(AgentCompactRequest compact)
            throws IOException, InterruptedException {
        if (compact == null) {
            throw new IllegalArgumentException("compact 不能为空");
        }
        var request = jsonRequest(
                compactUri,
                compact.userId(),
                compact.sessionId(),
                compact.requestId(),
                mapper.valueToTree(compact));
        var response = sendJson(request);
        return new AgentCompactResult(
                requiredText(response, "status"),
                requiredText(response, "trigger"),
                optionalText(response, "reason"),
                requiredInt(response, "before_tokens"),
                requiredInt(response, "after_tokens"),
                requiredInt(response, "compact_trigger_tokens"),
                requiredBoolean(response, "below_trigger"),
                requiredText(response, "summary_save_status"),
                objectOrNull(response, "session_summary"),
                objectOrNull(response, "compact"));
    }

    private HttpRequest jsonRequest(
            URI uri,
            String userId,
            String sessionId,
            String requestId,
            JsonNode body) throws IOException {
        var builder = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + internalToken)
                .header("X-User-ID", userId)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8");
        if (sessionId != null) {
            builder.header("X-Session-ID", sessionId);
        }
        if (requestId != null) {
            builder.header("X-Request-ID", requestId);
        }
        return builder.POST(HttpRequest.BodyPublishers.ofByteArray(
                mapper.writeValueAsBytes(body))).build();
    }

    private JsonNode sendJson(HttpRequest request)
            throws IOException, InterruptedException {
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            byte[] bytes = body.readNBytes(MAX_JSON_BODY_BYTES + 1);
            if (bytes.length > MAX_JSON_BODY_BYTES) {
                throw new IOException("Python Agent JSON 响应超过 2 MiB");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                int length = Math.min(bytes.length, MAX_ERROR_BODY_BYTES);
                String detail = new String(bytes, 0, length, StandardCharsets.UTF_8);
                if (bytes.length > MAX_ERROR_BODY_BYTES) {
                    detail += "…";
                }
                throw new IOException(
                        "Python Agent HTTP " + response.statusCode() + ": " + detail);
            }
            JsonNode result = mapper.readTree(bytes);
            if (result == null || !result.isObject()) {
                throw new IOException("Python Agent 没有返回 JSON 对象");
            }
            return result;
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.strip();
    }

    private static String requiredText(JsonNode body, String name) throws IOException {
        String value = optionalText(body, name);
        if (value == null) {
            throw new IOException("Python Agent 响应缺少 " + name);
        }
        return value;
    }

    private static String optionalText(JsonNode body, String name) throws IOException {
        var value = body.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IOException("Python Agent 响应字段 " + name + " 必须是非空字符串");
        }
        return value.textValue();
    }

    private static int requiredInt(JsonNode body, String name) throws IOException {
        var value = body.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IOException("Python Agent 响应字段 " + name + " 必须是整数");
        }
        return value.intValue();
    }

    private static boolean requiredBoolean(JsonNode body, String name) throws IOException {
        var value = body.get(name);
        if (value == null || !value.isBoolean()) {
            throw new IOException("Python Agent 响应字段 " + name + " 必须是布尔值");
        }
        return value.booleanValue();
    }

    private static JsonNode objectOrNull(JsonNode body, String name) throws IOException {
        var value = body.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IOException("Python Agent 响应字段 " + name + " 必须是对象或 null");
        }
        return value.deepCopy();
    }

    public record AgentSession(String userId, String sessionId) {
    }
}
