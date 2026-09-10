package com.pdflearning.backend.interfaceapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdflearning.backend.dataport.AgentRunDataPort;
import com.pdflearning.backend.dataport.AgentRunStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class AgentRunServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void persistsEventsAndReplaysCompletedRequestWithoutCallingPythonAgain() throws Exception {
        var calls = new AtomicInteger();
        var server = server(calls, event("run_started", 1) + event("run_finished", 2));
        var dataPort = dataPort();
        var service = service(server, dataPort);
        var firstEvents = new ArrayList<AgentEvent>();
        var replayedEvents = new ArrayList<AgentEvent>();
        try {
            var first = service.execute(request("request-1", "message-1"), firstEvents::add);
            var changedHistory = mapper.createArrayNode()
                    .add(mapper.createObjectNode().put("loaded_after_first_run", true));
            var replay = service.execute(
                    request("request-1", "message-1", "请解释注意力机制", changedHistory),
                    replayedEvents::add);

            assertEquals(AgentRunService.Outcome.EXECUTED, first.outcome());
            assertEquals(AgentRunStatus.COMPLETED, first.run().status());
            assertEquals(AgentRunService.Outcome.REPLAYED, replay.outcome());
            assertEquals(1, calls.get());
            assertEquals(2, firstEvents.size());
            assertEquals(2, replayedEvents.size());
            assertEquals(2, dataPort.readEvents("dev_user", "request-1").size());
            assertThrows(
                    IllegalStateException.class,
                    () -> service.execute(
                            request("request-1", "message-1", "不同的问题", changedHistory),
                            ignored -> {}));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsSameRequestInProgressAndDifferentRequestBusy() {
        var dataPort = dataPort();
        String requestHash = "a".repeat(64);

        var started = dataPort.beginRun(
                "dev_user", sessionId(), "request-1", "message-1", requestHash);
        var duplicate = dataPort.beginRun(
                "dev_user", sessionId(), "request-1", "message-1", requestHash);
        var busy = dataPort.beginRun(
                "dev_user", sessionId(), "request-2", "message-2", "b".repeat(64));

        assertEquals(AgentRunDataPort.ClaimDecision.STARTED, started.decision());
        assertEquals(AgentRunDataPort.ClaimDecision.IN_PROGRESS, duplicate.decision());
        assertEquals(AgentRunDataPort.ClaimDecision.SESSION_BUSY, busy.decision());
        assertEquals("request-1", busy.activeRequestId());
    }

    @Test
    void replaysCompletedRequestWhileANewerRequestOwnsTheSession() {
        var dataPort = dataPort();
        String sessionId = sessionId();
        dataPort.beginRun(
                "dev_user", sessionId, "request-1", "message-1", "a".repeat(64));
        dataPort.appendEvent(
                "dev_user",
                sessionId,
                "request-1",
                1,
                "run_finished",
                "b".repeat(64),
                "{}",
                AgentRunStatus.COMPLETED);
        dataPort.beginRun(
                "dev_user", sessionId, "request-2", "message-2", "c".repeat(64));

        var replay = dataPort.beginRun(
                "dev_user", sessionId, "request-1", "message-1", "a".repeat(64));

        assertEquals(AgentRunDataPort.ClaimDecision.REPLAY, replay.decision());
        assertEquals("request-1", replay.run().requestId());
        assertEquals("request-2", replay.activeRequestId());
    }

    @Test
    void keepsOnlyOneCopyOfAnIdenticalEventAndRejectsConflictingContent() {
        var dataPort = dataPort();
        dataPort.beginRun(
                "dev_user", sessionId(), "request-1", "message-1", "a".repeat(64));

        var inserted = dataPort.appendEvent(
                "dev_user",
                sessionId(),
                "request-1",
                1,
                "run_started",
                "b".repeat(64),
                "{}",
                null);
        var duplicate = dataPort.appendEvent(
                "dev_user",
                sessionId(),
                "request-1",
                1,
                "run_started",
                "b".repeat(64),
                "{}",
                null);

        assertEquals(AgentRunDataPort.AppendResult.INSERTED, inserted);
        assertEquals(AgentRunDataPort.AppendResult.DUPLICATE, duplicate);
        assertThrows(IllegalStateException.class, () -> dataPort.appendEvent(
                "dev_user",
                sessionId(),
                "request-1",
                1,
                "run_started",
                "c".repeat(64),
                "{\"changed\":true}",
                null));
    }

    @Test
    void marksRunInterruptedAndDoesNotReplayItAfterPrematureEof() throws Exception {
        var calls = new AtomicInteger();
        var server = server(calls, event("run_started", 1));
        var dataPort = dataPort();
        var service = service(server, dataPort);
        try {
            assertThrows(
                    EOFException.class,
                    () -> service.execute(request("request-1", "message-1"), ignored -> {}));
            assertEquals(
                    AgentRunStatus.INTERRUPTED,
                    dataPort.findRun("dev_user", "request-1").orElseThrow().status());

            var repeated = service.execute(
                    request("request-1", "message-1"),
                    ignored -> {});
            assertEquals(AgentRunService.Outcome.TERMINAL, repeated.outcome());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startupRecoveryInterruptsRunningRequestsAndReleasesTheirSessions() {
        var dataPort = dataPort();
        String sessionId = sessionId();
        dataPort.beginRun(
                "dev_user", sessionId, "request-1", "message-1", "a".repeat(64));

        assertEquals(1, dataPort.recoverInterruptedRuns());
        assertEquals(
                AgentRunStatus.INTERRUPTED,
                dataPort.findRun("dev_user", "request-1").orElseThrow().status());
        assertEquals(
                AgentRunDataPort.ClaimDecision.STARTED,
                dataPort.beginRun(
                        "dev_user",
                        sessionId,
                        "request-2",
                        "message-2",
                        "b".repeat(64))
                        .decision());
    }

    private AgentRunService service(HttpServer server, AgentRunDataPort dataPort) {
        var client = new AgentSseClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "python-token",
                HttpClient.newHttpClient(),
                mapper);
        return new AgentRunService(client, dataPort, mapper);
    }

    private HttpServer server(AtomicInteger calls, String events) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/runs", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        return server;
    }

    private AgentRunRequest request(String requestId, String messageId) {
        return request(
                requestId,
                messageId,
                "请解释注意力机制",
                mapper.createArrayNode());
    }

    private AgentRunRequest request(
            String requestId,
            String messageId,
            String message,
            JsonNode recentHistory) {
        return new AgentRunRequest(
                "dev_user",
                sessionId(),
                requestId,
                messageId,
                message,
                recentHistory,
                mapper.createArrayNode(),
                null,
                null,
                null,
                new AgentRunRequest.AgentConfig(10_000, 8_000, 1_000, 1_000));
    }

    private AgentRunDataPort dataPort() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE agent_session (
                        user_id VARCHAR(128) NOT NULL,
                        session_id VARCHAR(160) NOT NULL,
                        active_request_id VARCHAR(160),
                        created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        PRIMARY KEY (user_id, session_id)
                    )
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
                        PRIMARY KEY (user_id, request_id)
                    )
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
                        PRIMARY KEY (user_id, request_id, event_seq)
                    )
                    """);
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
        return new AgentRunDataPort(dataSource);
    }

    private static String event(String type, int sequence) {
        String status = "run_finished".equals(type) ? "\"status\":\"completed\"" : "";
        return "event: " + type + "\n"
                + "data: {\"type\":\"" + type + "\","
                + "\"request_id\":\"request-1\","
                + "\"session_id\":\"" + sessionId() + "\","
                + "\"event_seq\":" + sequence + ",\"payload\":{" + status + "}}\n\n";
    }

    private static String sessionId() {
        return "session_20260911_101112_dev_user";
    }
}
