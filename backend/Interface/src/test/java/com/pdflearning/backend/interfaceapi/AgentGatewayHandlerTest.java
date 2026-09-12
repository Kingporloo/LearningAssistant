package com.pdflearning.backend.interfaceapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdflearning.backend.dataport.AgentRunDataPort;
import com.pdflearning.backend.dataport.ChatDataPort;
import com.pdflearning.backend.dataport.ContextSummaryDataPort;
import com.pdflearning.backend.dataport.UserDataPort;
import com.pdflearning.backend.user.UserService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class AgentGatewayHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void connectsAuthenticatedUserToPythonAgentAndPersistsVisibleMessages() throws Exception {
        var source = database();
        var users = new UserService(new UserDataPort(source), Duration.ofHours(1));
        var firstAuth = users.register("learner_one", "secret1", "学习者一");
        var secondAuth = users.register("learner_two", "secret2", "学习者二");
        var receivedRun = new AtomicReference<JsonNode>();
        var receivedCompact = new AtomicReference<JsonNode>();
        var runCalls = new AtomicInteger();
        var python = pythonServer(receivedRun, receivedCompact, runCalls);
        var gateway = gatewayServer(source, users, python);
        python.start();
        gateway.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + gateway.getAddress().getPort());
            var createdResponse = send(
                    base.resolve("/sessions"), "POST", firstAuth.token(), "{}");
            assertEquals(201, createdResponse.statusCode());
            var created = mapper.readTree(createdResponse.body());
            String sessionId = created.path("id").asText();
            assertEquals(0, created.path("messageCount").asInt());
            assertEquals(0, new ChatDataPort(source)
                    .listSessions(firstAuth.user().userId()).size());

            var runResponse = send(
                    base.resolve("/sessions/" + sessionId + "/runs"),
                    "POST",
                    firstAuth.token(),
                    """
                    {"request_id":"request_1","message_id":"message_1","message":"请解释注意力机制"}
                    """);
            assertEquals(200, runResponse.statusCode());
            assertTrue(runResponse.headers().firstValue("Content-Type")
                    .orElse("").startsWith("text/event-stream"));
            assertTrue(runResponse.body().contains("event: message_completed"));

            var secondRun = send(
                    base.resolve("/sessions/" + sessionId + "/runs"),
                    "POST",
                    firstAuth.token(),
                    """
                    {"request_id":"request_2","message_id":"message_2","message":"再举一个例子"}
                    """);
            assertEquals(200, secondRun.statusCode());
            assertEquals(2, runCalls.get());
            assertEquals(1, receivedRun.get().path("recent_history").size());
            assertEquals(
                    "请解释注意力机制",
                    receivedRun.get()
                            .path("recent_history").get(0)
                            .path("user_message").path("content").asText());

            var replay = send(
                    base.resolve("/sessions/" + sessionId + "/runs"),
                    "POST",
                    firstAuth.token(),
                    """
                    {"request_id":"request_2","message_id":"message_2","message":"再举一个例子"}
                    """);
            assertEquals(200, replay.statusCode());
            assertEquals(2, runCalls.get());

            var compact = send(
                    base.resolve("/sessions/" + sessionId + "/compact"),
                    "POST",
                    firstAuth.token(),
                    "{}");
            assertEquals(200, compact.statusCode());
            assertEquals(2, receivedCompact.get().path("recent_history").size());

            var historyResponse = send(
                    base.resolve("/sessions/" + sessionId + "/messages"),
                    "GET",
                    firstAuth.token(),
                    null);
            assertEquals(200, historyResponse.statusCode());
            var history = mapper.readTree(historyResponse.body());
            assertEquals(4, history.size());
            assertEquals("user", history.get(0).path("role").asText());
            assertEquals("assistant", history.get(1).path("role").asText());
            assertEquals("completed", history.get(1).path("status").asText());
            assertEquals("注意力会按相关性分配权重。", history.get(1).path("content").asText());
            assertEquals(
                    "教材片段",
                    history.get(1).path("segments").get(0)
                            .path("toolCall").path("result").path("results").get(0).asText());

            assertEquals(firstAuth.user().userId(), receivedRun.get().path("user_id").asText());
            assertEquals(sessionId, receivedRun.get().path("session_id").asText());

            var forbidden = send(
                    base.resolve("/sessions/" + sessionId + "/messages"),
                    "GET",
                    secondAuth.token(),
                    null);
            assertEquals(404, forbidden.statusCode());
        } finally {
            gateway.stop(0);
            python.stop(0);
        }
    }

    private HttpServer gatewayServer(
            JdbcDataSource source,
            UserService users,
            HttpServer python) throws Exception {
        var chats = new ChatDataPort(source);
        var runs = new AgentRunDataPort(source);
        var summaries = new ContextSummaryDataPort(source);
        URI pythonBase = URI.create("http://127.0.0.1:" + python.getAddress().getPort());
        var handler = new AgentGatewayHandler(
                users,
                chats,
                summaries,
                new AgentControlClient(pythonBase, "internal-token"),
                new AgentRunService(new AgentSseClient(pythonBase, "internal-token"), runs),
                new AgentRunRequest.AgentConfig(10_000, 8_000, 1_000, 1_000),
                "http://localhost:5173",
                mapper);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        return server;
    }

    private HttpServer pythonServer(
            AtomicReference<JsonNode> receivedRun,
            AtomicReference<JsonNode> receivedCompact,
            AtomicInteger runCalls) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/sessions", exchange -> {
            String userId = exchange.getRequestHeaders().getFirst("X-User-ID");
            exchange.getRequestBody().readAllBytes();
            byte[] response = mapper.createObjectNode()
                    .put("user_id", userId)
                    .put("session_id", "session_20260912_120000_" + userId)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.createContext("/internal/agent/runs", exchange -> {
            runCalls.incrementAndGet();
            var run = mapper.readTree(exchange.getRequestBody());
            receivedRun.set(run);
            String requestId = run.path("request_id").asText();
            String sessionId = run.path("session_id").asText();
            String events = event("run_started", requestId, sessionId, 1,
                            mapper.createObjectNode().put(
                                    "message_id", run.path("message_id").asText()))
                    + event("tool_started", requestId, sessionId, 2,
                            mapper.createObjectNode()
                                    .put("model_step", 1)
                                    .put("tool_call_id", "tool_call_1")
                                    .put("name", "rag__query")
                                    .set("arguments", mapper.createObjectNode()
                                            .put("query", "注意力机制")))
                    + event("tool_finished", requestId, sessionId, 3,
                            mapper.createObjectNode()
                                    .put("tool_call_id", "tool_call_1")
                                    .put("name", "rag__query")
                                    .put("outcome", "completed")
                                    .put("business_status", "ok")
                                    .put("content", "教材片段")
                                    .set("result", mapper.createObjectNode()
                                            .put("status", "ok")
                                            .set("results", mapper.createArrayNode()
                                                    .add("教材片段"))))
                    + event("text_delta", requestId, sessionId, 4,
                            mapper.createObjectNode()
                                    .put("model_step", 1)
                                    .put("delta", "注意力会按相关性分配权重。"))
                    + event("message_completed", requestId, sessionId, 5,
                            mapper.createObjectNode()
                                    .put("model_step", 1)
                                    .put("content", "注意力会按相关性分配权重。")
                                    .set("usage", mapper.createObjectNode()
                                            .put("input_tokens", 12)
                                            .put("output_tokens", 8)
                                            .put("total_tokens", 20)))
                    + event("run_finished", requestId, sessionId, 6,
                            mapper.createObjectNode()
                                    .put("status", "completed")
                                    .put("model_steps", 1)
                                    .put("tool_rounds", 0)
                                    .set("usage", mapper.createObjectNode()
                                            .put("input_tokens", 12)
                                            .put("output_tokens", 8)
                                            .put("total_tokens", 20)));
            byte[] response = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.createContext("/internal/agent/context/compact", exchange -> {
            receivedCompact.set(mapper.readTree(exchange.getRequestBody()));
            var result = mapper.createObjectNode();
            result.put("status", "completed");
            result.put("trigger", "manual");
            result.putNull("reason");
            result.put("before_tokens", 1200);
            result.put("after_tokens", 500);
            result.put("compact_trigger_tokens", 9200);
            result.put("below_trigger", true);
            result.put("summary_save_status", "saved");
            result.putNull("session_summary");
            result.putNull("compact");
            byte[] response = result.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        return server;
    }

    private String event(
            String type,
            String requestId,
            String sessionId,
            int sequence,
            JsonNode payload) {
        var root = mapper.createObjectNode();
        root.put("type", type);
        root.put("request_id", requestId);
        root.put("session_id", sessionId);
        root.put("event_seq", sequence);
        root.set("payload", payload);
        return "event: " + type + "\n" + "data: " + root + "\n\n";
    }

    private HttpResponse<String> send(
            URI uri,
            String method,
            String token,
            String body) throws Exception {
        var request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
    }

    private JdbcDataSource database() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (
                        user_id VARCHAR(64) PRIMARY KEY,
                        username VARCHAR(32) UNIQUE NOT NULL,
                        nickname VARCHAR(32) NOT NULL,
                        password_hash VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP(6) NOT NULL,
                        updated_at TIMESTAMP(6) NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE user_login_token (
                        token_hash CHAR(64) PRIMARY KEY,
                        user_id VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        expires_at TIMESTAMP(6) NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE chat_session (
                        session_id VARCHAR(160) PRIMARY KEY,
                        user_id VARCHAR(64) NOT NULL,
                        title VARCHAR(100) NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        created_at TIMESTAMP(6) NOT NULL,
                        updated_at TIMESTAMP(6) NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE chat_message (
                        message_id VARCHAR(160) PRIMARY KEY,
                        user_id VARCHAR(64) NOT NULL,
                        session_id VARCHAR(160) NOT NULL,
                        request_id VARCHAR(160) NOT NULL,
                        role VARCHAR(16) NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        content CLOB NOT NULL,
                        segments_json CLOB,
                        usage_json CLOB,
                        model_steps INT,
                        tool_rounds INT,
                        error_json CLOB,
                        created_at TIMESTAMP(6) NOT NULL,
                        UNIQUE (user_id, request_id, role))
                    """);
            statement.execute("""
                    CREATE TABLE agent_session (
                        user_id VARCHAR(128) NOT NULL,
                        session_id VARCHAR(160) NOT NULL,
                        active_request_id VARCHAR(160),
                        created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        PRIMARY KEY (user_id, session_id))
                    """);
            statement.execute("""
                    CREATE TABLE agent_run (
                        user_id VARCHAR(128) NOT NULL,
                        request_id VARCHAR(160) NOT NULL,
                        session_id VARCHAR(160) NOT NULL,
                        message_id VARCHAR(160) NOT NULL,
                        request_hash CHAR(64) NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        last_event_seq BIGINT DEFAULT 0 NOT NULL,
                        interruption_reason CLOB,
                        created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        finished_at TIMESTAMP(6),
                        PRIMARY KEY (user_id, request_id))
                    """);
            statement.execute("""
                    CREATE TABLE agent_run_event (
                        user_id VARCHAR(128) NOT NULL,
                        request_id VARCHAR(160) NOT NULL,
                        event_seq BIGINT NOT NULL,
                        session_id VARCHAR(160) NOT NULL,
                        event_type VARCHAR(64) NOT NULL,
                        event_hash CHAR(64) NOT NULL,
                        event_json CLOB NOT NULL,
                        created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        PRIMARY KEY (user_id, request_id, event_seq))
                    """);
            statement.execute("""
                    CREATE TABLE session_summary (
                        user_id VARCHAR(128) NOT NULL,
                        session_id VARCHAR(160) NOT NULL,
                        version INT NOT NULL,
                        text CLOB NOT NULL,
                        through_message_id VARCHAR(160),
                        history_cursor VARCHAR(160),
                        source_refs_json CLOB NOT NULL,
                        PRIMARY KEY (user_id, session_id))
                    """);
        }
        return source;
    }
}
