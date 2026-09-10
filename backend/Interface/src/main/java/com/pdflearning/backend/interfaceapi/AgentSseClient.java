package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** 调用 Python /internal/agent/runs 并打开 SSE 事件流。 */
public final class AgentSseClient {
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

    private final URI runUri;
    private final String internalToken;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public AgentSseClient(URI baseUri, String internalToken) {
        this(
                baseUri,
                internalToken,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper());
    }

    AgentSseClient(
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
        this.runUri = baseUri.resolve("/internal/agent/runs");
        this.internalToken = internalToken;
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    public static AgentSseClient fromEnvironment() {
        Map<String, String> environment = System.getenv();
        String baseUrl = environment.getOrDefault(
                "PYTHON_AGENT_BASE_URL", "http://127.0.0.1:8800");
        String token = environment.get("PYTHON_INTERNAL_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("缺少环境变量 PYTHON_INTERNAL_TOKEN");
        }
        try {
            return new AgentSseClient(URI.create(baseUrl), token);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PYTHON_AGENT_BASE_URL 无效", exception);
        }
    }

    /**
     * 发起一次 Agent 运行。调用在收到响应头前阻塞；
     * 返回后由调用方负责关闭事件流。
     */
    public AgentEventStream openRun(AgentRunRequest run)
            throws IOException, InterruptedException {
        if (run == null) {
            throw new IllegalArgumentException("run 不能为空");
        }
        var request = HttpRequest.newBuilder(runUri)
                .header("Authorization", "Bearer " + internalToken)
                .header("X-User-ID", run.userId())
                .header("X-Session-ID", run.sessionId())
                .header("X-Request-ID", run.requestId())
                .header("X-Message-ID", run.messageId())
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(run)))
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (var body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_ERROR_BODY_BYTES + 1);
                String detail = new String(
                        bytes,
                        0,
                        Math.min(bytes.length, MAX_ERROR_BODY_BYTES),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (bytes.length > MAX_ERROR_BODY_BYTES) {
                    detail += "…";
                }
                throw new IOException(
                        "Python Agent HTTP " + response.statusCode() + ": " + detail);
            }
        }

        String contentType = response.headers()
                .firstValue("Content-Type")
                .orElse("")
                .toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("text/event-stream")) {
            response.body().close();
            throw new IOException("Python Agent 没有返回 text/event-stream");
        }
        return new AgentEventStream(
                response.body(), mapper, run.requestId(), run.sessionId());
    }
}
