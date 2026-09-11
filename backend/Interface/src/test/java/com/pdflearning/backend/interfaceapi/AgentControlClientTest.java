package com.pdflearning.backend.interfaceapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentControlClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsSessionAndRequestsManualCompact() throws Exception {
        var sessionHeaders = new AtomicReference<Map<String, String>>();
        var compactBody = new AtomicReference<JsonNode>();
        var compactHeaders = new AtomicReference<Map<String, String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/sessions", exchange -> {
            sessionHeaders.set(Map.of(
                    "authorization", exchange.getRequestHeaders().getFirst("Authorization"),
                    "user", exchange.getRequestHeaders().getFirst("X-User-ID")));
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 201, """
                    {"user_id":"dev_user",\
                     "session_id":"session_20260911_101112_dev_user"}
                    """);
        });
        server.createContext("/internal/agent/context/compact", exchange -> {
            compactHeaders.set(Map.of(
                    "user", exchange.getRequestHeaders().getFirst("X-User-ID"),
                    "session", exchange.getRequestHeaders().getFirst("X-Session-ID"),
                    "request", exchange.getRequestHeaders().getFirst("X-Request-ID")));
            compactBody.set(mapper.readTree(exchange.getRequestBody()));
            sendJson(exchange, 200, """
                    {"status":"saved","trigger":"manual","reason":null,
                     "before_tokens":1200,"after_tokens":500,
                     "compact_trigger_tokens":7360,"below_trigger":true,
                     "summary_save_status":"saved",
                     "session_summary":{"version":1,"text":"摘要"},
                     "compact":{"status":"success"}}
                    """);
        });
        server.start();

        var client = new AgentControlClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "python-token",
                HttpClient.newHttpClient(),
                mapper);
        try {
            var session = client.createSession("dev_user");
            var result = client.compactContext(compactRequest(session.sessionId()));

            assertEquals("session_20260911_101112_dev_user", session.sessionId());
            assertEquals("Bearer python-token", sessionHeaders.get().get("authorization"));
            assertEquals("dev_user", sessionHeaders.get().get("user"));
            assertEquals("saved", result.status());
            assertEquals(500, result.afterTokens());
            assertEquals("dev_user", compactHeaders.get().get("user"));
            assertEquals(session.sessionId(), compactHeaders.get().get("session"));
            assertEquals("compact-request-1", compactHeaders.get().get("request"));
            assertEquals(
                    5,
                    compactBody.get().path("agent_config").path("keep_recent_turns").intValue());
            assertFalse(compactBody.get().has("message_id"));
        } finally {
            server.stop(0);
        }
    }

    private AgentCompactRequest compactRequest(String sessionId) {
        return new AgentCompactRequest(
                "dev_user",
                sessionId,
                "compact-request-1",
                mapper.createArrayNode(),
                mapper.createArrayNode(),
                null,
                null,
                null,
                new AgentRunRequest.AgentConfig(10_000, 8_000, 1_000, 1_000));
    }

    private static void sendJson(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
