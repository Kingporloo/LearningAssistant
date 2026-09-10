package com.pdflearning.backend.interfaceapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentSseClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsTrustedRunAndConsumesCorrelatedEvents() throws Exception {
        var receivedBody = new AtomicReference<JsonNode>();
        var receivedHeaders = new AtomicReference<Map<String, String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/runs", exchange -> {
            var headers = exchange.getRequestHeaders();
            receivedHeaders.set(Map.of(
                    "authorization", headers.getFirst("Authorization"),
                    "user", headers.getFirst("X-User-ID"),
                    "session", headers.getFirst("X-Session-ID"),
                    "request", headers.getFirst("X-Request-ID"),
                    "message", headers.getFirst("X-Message-ID")));
            receivedBody.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = (event("run_started", 1) + event("run_finished", 2))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        var client = new AgentSseClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "python-token",
                HttpClient.newHttpClient(),
                mapper);
        var run = request();
        var events = new ArrayList<AgentEvent>();
        try {
            try (var stream = client.openRun(run)) {
                stream.consume(events::add);
            }
        } finally {
            server.stop(0);
        }

        assertEquals("Bearer python-token", receivedHeaders.get().get("authorization"));
        assertEquals("dev_user", receivedHeaders.get().get("user"));
        assertEquals(
                "session_20260911_101112_dev_user",
                receivedHeaders.get().get("session"));
        assertEquals("request-1", receivedHeaders.get().get("request"));
        assertEquals("message-1", receivedHeaders.get().get("message"));
        assertEquals("dev_user", receivedBody.get().path("user_id").textValue());
        assertEquals(
                10_000,
                receivedBody.get().path("agent_config").path("model_window").intValue());
        assertEquals(2, events.size());
        assertEquals("run_started", events.get(0).type());
        assertEquals("run_finished", events.get(1).type());
    }

    @Test
    void rejectsMissingRunFinished() throws Exception {
        byte[] response = event("run_started", 1).getBytes(StandardCharsets.UTF_8);
        try (var stream = new AgentEventStream(
                new ByteArrayInputStream(response),
                mapper,
                "request-1",
                "session_20260911_101112_dev_user")) {
            assertEquals("run_started", stream.readNext().type());
            assertThrows(EOFException.class, stream::readNext);
        }
    }

    @Test
    void rejectsNonContinuousEventSequence() throws Exception {
        byte[] response = event("run_started", 2).getBytes(StandardCharsets.UTF_8);
        try (var stream = new AgentEventStream(
                new ByteArrayInputStream(response),
                mapper,
                "request-1",
                "session_20260911_101112_dev_user")) {
            var exception = assertThrows(java.io.IOException.class, stream::readNext);
            assertEquals(
                    "Python Agent SSE event_seq 不连续，期望 1，实际 2",
                    exception.getMessage());
        }
    }

    private AgentRunRequest request() {
        return new AgentRunRequest(
                "dev_user",
                "session_20260911_101112_dev_user",
                "request-1",
                "message-1",
                "请解释注意力机制",
                mapper.createArrayNode(),
                mapper.createArrayNode(),
                null,
                null,
                null,
                new AgentRunRequest.AgentConfig(10_000, 8_000, 1_000, 1_000));
    }

    private static String event(String type, int sequence) {
        return "event: " + type + "\n"
                + "data: {\"type\":\"" + type + "\","
                + "\"request_id\":\"request-1\","
                + "\"session_id\":\"session_20260911_101112_dev_user\","
                + "\"event_seq\":" + sequence + ",\"payload\":{}}\n\n";
    }
}
