package com.pdflearning.backend.interfaceapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdflearning.backend.dataport.AgentRunDataPort;
import com.pdflearning.backend.dataport.ChatDataPort;
import com.pdflearning.backend.dataport.ContextSummaryDataPort;
import com.pdflearning.backend.dataport.DocumentDataPort;
import com.pdflearning.backend.dataport.RagDataPort;
import com.pdflearning.backend.dataport.RagDocumentStore;
import com.pdflearning.backend.dataport.UserDataPort;
import com.pdflearning.backend.user.UserService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
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
            assertEquals(1, receivedRun.get().path("execution_history").size());
            assertEquals(
                    "request_1:tool_call_1",
                    receivedRun.get().path("execution_history").get(0)
                            .path("tool_calls").get(0).path("tool_call_id").asText());
            assertEquals(
                    "教材片段",
                    receivedRun.get().path("execution_history").get(0)
                            .path("tool_results").get(0).path("content").asText());
            assertEquals(1, receivedRun.get().path("session_ledger").path("version").asInt());
            assertEquals(
                    "理解注意力机制",
                    receivedRun.get().path("session_ledger").path("entries").get(0)
                            .path("content").asText());
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

    @Test
    void exposesUserAndAgentRoutesThroughOneGateway() throws Exception {
        var source = database();
        var users = new UserService(new UserDataPort(source), Duration.ofHours(1));
        var python = pythonServer(
                new AtomicReference<>(), new AtomicReference<>(), new AtomicInteger());
        var gateway = gatewayServer(source, users, python);
        python.start();
        gateway.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + gateway.getAddress().getPort());
            var register = send(
                    base.resolve("/auth/register"),
                    "POST",
                    null,
                    """
                    {"username":"gateway_user","password":"secret123","nickname":"网关用户"}
                    """);
            assertEquals(200, register.statusCode());
            var auth = mapper.readTree(register.body());
            String token = auth.path("token").asText();
            assertEquals("gateway_user", auth.path("user").path("username").asText());

            var me = send(base.resolve("/auth/me"), "GET", token, null);
            assertEquals(200, me.statusCode());
            assertEquals("网关用户", mapper.readTree(me.body()).path("nickname").asText());

            var sessions = send(base.resolve("/sessions"), "GET", token, null);
            assertEquals(200, sessions.statusCode());
            assertTrue(mapper.readTree(sessions.body()).isArray());

            var anonymous = send(base.resolve("/sessions"), "GET", null, null);
            assertEquals(401, anonymous.statusCode());
        } finally {
            gateway.stop(0);
            python.stop(0);
        }
    }

    @Test
    void managesRagDocumentsThroughAuthenticatedGateway() throws Exception {
        var source = database();
        var users = new UserService(new UserDataPort(source), Duration.ofHours(1));
        var first = users.register("document_owner", "secret1", "文档用户");
        var second = users.register("another_owner", "secret2", "另一用户");
        var python = pythonServer(
                new AtomicReference<>(), new AtomicReference<>(), new AtomicInteger());
        var gateway = gatewayServer(source, users, python);
        python.start();
        gateway.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + gateway.getAddress().getPort());
            var upload = upload(
                    base.resolve("/documents?filename=%E6%95%99%E6%9D%90.txt"),
                    first.token(),
                    "注意力机制会为不同信息分配不同权重。".getBytes(StandardCharsets.UTF_8));
            assertEquals(202, upload.statusCode());
            String documentId = mapper.readTree(upload.body()).path("id").asText();

            JsonNode ready = waitForReady(base, first.token(), documentId);
            assertEquals("教材.txt", ready.path("name").asText());
            assertEquals("ready", ready.path("status").asText());
            assertEquals(1, ready.path("chunkCount").asInt());
            assertEquals(3, ready.path("pageCount").asInt());

            var rebuilt = send(
                    base.resolve("/documents/" + documentId + "/rebuild"),
                    "POST",
                    first.token(),
                    "{}");
            assertEquals(202, rebuilt.statusCode());
            assertEquals(documentId, mapper.readTree(rebuilt.body()).path("id").asText());
            ready = waitForReady(base, first.token(), documentId);

            var replacement = upload(
                    base.resolve("/documents/" + documentId
                            + "?filename=%E6%96%B0%E6%95%99%E6%9D%90.md"),
                    first.token(),
                    "# 新教材".getBytes(StandardCharsets.UTF_8),
                    "PUT");
            assertEquals(202, replacement.statusCode());
            assertEquals(documentId, mapper.readTree(replacement.body()).path("id").asText());
            ready = waitForReady(base, first.token(), documentId);
            assertEquals("新教材.md", ready.path("name").asText());

            try (var connection = source.getConnection();
                    var statement = connection.prepareStatement("""
                            UPDATE rag_document SET status = 'failed'
                            WHERE user_id = ? AND document_id = ?
                            """)) {
                statement.setString(1, first.user().userId());
                statement.setString(2, documentId);
                statement.executeUpdate();
            }
            var retryFailed = send(
                    base.resolve("/documents/" + documentId + "/rebuild"),
                    "POST",
                    first.token(),
                    "{}");
            assertEquals(202, retryFailed.statusCode());
            ready = waitForReady(base, first.token(), documentId);
            assertEquals("ready", ready.path("status").asText());

            var isolated = send(base.resolve("/documents"), "GET", second.token(), null);
            assertEquals(0, mapper.readTree(isolated.body()).size());
            var forbiddenRebuild = send(
                    base.resolve("/documents/" + documentId + "/rebuild"),
                    "POST",
                    second.token(),
                    "{}");
            assertEquals(404, forbiddenRebuild.statusCode());
            var forbiddenDelete = send(
                    base.resolve("/documents/" + documentId), "DELETE", second.token(), null);
            assertEquals(404, forbiddenDelete.statusCode());

            var deleted = send(
                    base.resolve("/documents/" + documentId), "DELETE", first.token(), null);
            assertEquals(204, deleted.statusCode());
            var afterDelete = send(base.resolve("/documents"), "GET", first.token(), null);
            assertEquals(0, mapper.readTree(afterDelete.body()).size());
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
        var ledgers = new com.pdflearning.backend.dataport.SessionLedgerDataPort(source);
        var summaries = new ContextSummaryDataPort(source);
        var documents = new DocumentDataPort(source);
        URI pythonBase = URI.create("http://127.0.0.1:" + python.getAddress().getPort());
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var handler = new AgentGatewayHandler(
                users,
                chats,
                runs,
                summaries,
                ledgers,
                documents,
                new TestRagDocumentStore(source),
                new RagBuildClient(pythonBase, "internal-token", Duration.ofSeconds(2), mapper),
                Path.of(System.getProperty("java.io.tmpdir"),
                        "pdf-learning-gateway-test-" + System.nanoTime()),
                executor,
                new AgentControlClient(pythonBase, "internal-token"),
                new AgentRunService(new AgentSseClient(pythonBase, "internal-token"), runs),
                new AgentRunRequest.AgentConfig(10_000, 8_000, 1_000, 1_000),
                "http://localhost:5173",
                mapper);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler::handle);
        server.setExecutor(executor);
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
            int runNumber = runCalls.incrementAndGet();
            var run = mapper.readTree(exchange.getRequestBody());
            receivedRun.set(run);
            String requestId = run.path("request_id").asText();
            String sessionId = run.path("session_id").asText();
            var ledgerEntry = mapper.createObjectNode();
            ledgerEntry.put("id", "goal-attention");
            ledgerEntry.put("type", "goal");
            ledgerEntry.put("content", "理解注意力机制");
            ledgerEntry.put("status", "active");
            ledgerEntry.put("scope", "session");
            ledgerEntry.put("created_at", "2026-09-15T08:00:00Z");
            ledgerEntry.put("updated_at", "2026-09-15T08:00:00Z");
            ledgerEntry.putArray("source_refs").add("request_1:tool_call_1");
            ledgerEntry.putArray("supersedes");
            var ledgerOperation = mapper.createObjectNode().put("op", "add");
            ledgerOperation.set("entry", ledgerEntry);
            var ledgerPatch = mapper.createObjectNode().put("base_version", 0);
            ledgerPatch.putArray("operations").add(ledgerOperation);
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
                    + (runNumber == 1
                            ? event("session_ledger_patch", requestId, sessionId, 6, ledgerPatch)
                            : "")
                    + event("run_finished", requestId, sessionId, runNumber == 1 ? 7 : 6,
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
        server.createContext("/internal/rag/build", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            var chunk = mapper.createObjectNode();
            chunk.put("chunk_id", request.path("document_id").asText() + ":0");
            chunk.put("document_id", request.path("document_id").asText());
            chunk.put("chunk_index", 0);
            chunk.put("text", "注意力机制会为不同信息分配不同权重。");
            chunk.put("source", request.path("source_name").asText());
            chunk.put("file_type", ".txt");
            chunk.put("page", 3);
            chunk.set("vector", mapper.createArrayNode().add(0.1).add(0.2));
            var graph = mapper.createObjectNode();
            graph.set("next_chunk", mapper.createArrayNode());
            graph.set("similar_to", mapper.createArrayNode());
            var result = mapper.createObjectNode();
            result.put("user_id", request.path("user_id").asText());
            result.put("document_id", request.path("document_id").asText());
            result.put("request_id", request.path("request_id").asText());
            result.put("status", "ok");
            result.put("message", "构建完成");
            result.set("chunks", mapper.createArrayNode().add(chunk));
            result.set("graph", graph);
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
        var builder = HttpRequest.newBuilder(uri).header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        var request = builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(URI uri, String token, byte[] body) throws Exception {
        return upload(uri, token, body, "POST");
    }

    private HttpResponse<String> upload(
            URI uri,
            String token,
            byte[] body,
            String method) throws Exception {
        var request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/octet-stream")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode waitForReady(URI base, String token, String documentId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            var response = send(base.resolve("/documents"), "GET", token, null);
            assertEquals(200, response.statusCode());
            for (var document : mapper.readTree(response.body())) {
                if (documentId.equals(document.path("id").asText())
                        && "ready".equals(document.path("status").asText())) {
                    return document;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("文档未在测试时限内完成构建");
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
            statement.execute("""
                    CREATE TABLE session_ledger (
                        user_id VARCHAR(128) NOT NULL,
                        session_id VARCHAR(160) NOT NULL,
                        version INT NOT NULL,
                        compacted_through_message_id VARCHAR(160),
                        entries_json CLOB NOT NULL,
                        created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        PRIMARY KEY (user_id, session_id))
                    """);
            statement.execute("""
                    CREATE TABLE rag_document (
                        user_id VARCHAR(128) NOT NULL,
                        document_id VARCHAR(160) NOT NULL,
                        file_name VARCHAR(255),
                        file_size BIGINT,
                        source_path VARCHAR(1024),
                        markdown_path VARCHAR(1024),
                        build_request_id VARCHAR(160) NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        status_message CLOB,
                        chunk_count INT,
                        page_count INT,
                        ready_at TIMESTAMP(6),
                        created_at TIMESTAMP(6) NOT NULL,
                        updated_at TIMESTAMP(6) NOT NULL,
                        PRIMARY KEY (user_id, document_id))
                    """);
        }
        return source;
    }

    private static final class TestRagDocumentStore implements RagDocumentStore {
        private final JdbcDataSource source;

        private TestRagDocumentStore(JdbcDataSource source) {
            this.source = source;
        }

        @Override
        public Map<String, Object> replaceDocument(RagDataPort.BuildCommand command) {
            int updated = updateStatus(
                    command.userId(), command.documentId(), command.requestId(),
                    command.chunks().isEmpty() ? "empty" : "ready");
            return updated == 0
                    ? Map.of("status", "cancelled")
                    : Map.of("status", command.chunks().isEmpty() ? "empty" : "ready");
        }

        @Override
        public Map<String, Object> deleteDocument(String userId, String documentId) {
            try (var connection = source.getConnection();
                    var statement = connection.prepareStatement("""
                            UPDATE rag_document SET status = 'deleted'
                            WHERE user_id = ? AND document_id = ? AND status <> 'deleted'
                            """)) {
                statement.setString(1, userId);
                statement.setString(2, documentId);
                return statement.executeUpdate() == 0
                        ? Map.of("status", "not_found")
                        : Map.of("status", "deleted");
            } catch (java.sql.SQLException exception) {
                throw new RuntimeException(exception);
            }
        }

        private int updateStatus(
                String userId, String documentId, String requestId, String status) {
            try (var connection = source.getConnection();
                    var statement = connection.prepareStatement("""
                            UPDATE rag_document SET status = ?, updated_at = CURRENT_TIMESTAMP
                            WHERE user_id = ? AND document_id = ? AND build_request_id = ?
                              AND status <> 'deleted'
                            """)) {
                statement.setString(1, status);
                statement.setString(2, userId);
                statement.setString(3, documentId);
                statement.setString(4, requestId);
                return statement.executeUpdate();
            } catch (java.sql.SQLException exception) {
                throw new RuntimeException(exception);
            }
        }
    }
}
