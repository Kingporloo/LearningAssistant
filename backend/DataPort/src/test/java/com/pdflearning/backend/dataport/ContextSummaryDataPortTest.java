package com.pdflearning.backend.dataport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class ContextSummaryDataPortTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcDataSource source;

    @Test
    void storesOneVersionAndReplaysTheSameOperation() throws Exception {
        var dataPort = dataPort();
        var command = command("压缩后的摘要");

        var first = dataPort.store(command);
        var replay = dataPort.store(command);

        assertEquals("saved", first.get("status"));
        assertEquals(mapper.valueToTree(first), mapper.valueToTree(replay));
        assertEquals(1, summaryVersion());
        assertEquals(1, operationCount());
        var stored = dataPort.find("dev_user", sessionId()).orElseThrow();
        assertEquals("压缩后的摘要", stored.text());
        assertEquals("assistant-5", stored.throughMessageId());
        assertEquals("cursor-1", stored.historyCursor());
        assertEquals(1, stored.sourceRefs().size());
    }

    @Test
    void rejectsChangedContentForTheSameOperation() throws Exception {
        var dataPort = dataPort();
        dataPort.store(command("原摘要"));

        assertThrows(
                IllegalStateException.class,
                () -> dataPort.store(command("被更改的摘要")));
    }

    @Test
    void reportsOptimisticVersionConflict() throws Exception {
        var dataPort = dataPort();
        dataPort.store(command("第一版"));
        var conflict = dataPort.store(new ContextSummaryDataPort.StoreCommand(
                "dev_user",
                sessionId(),
                "compact-request-2",
                "compact:compact-request-2",
                0,
                "cursor-2",
                "assistant-6",
                mapper.createArrayNode(),
                "冲突摘要"));

        assertEquals("conflict", conflict.get("status"));
        assertEquals(1, conflict.get("current_version"));
        assertEquals(1, summaryVersion());
        assertEquals(1, operationCount());
    }

    private ContextSummaryDataPort dataPort() throws SQLException {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE agent_session (
                        user_id VARCHAR(128) NOT NULL,
                        session_id VARCHAR(160) NOT NULL,
                        PRIMARY KEY (user_id, session_id)
                    )
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
                        updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        PRIMARY KEY (user_id, session_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE context_summary_operation (
                        user_id VARCHAR(128) NOT NULL,
                        request_id VARCHAR(160) NOT NULL,
                        operation_id VARCHAR(200) NOT NULL,
                        session_id VARCHAR(160) NOT NULL,
                        request_hash CHAR(64) NOT NULL,
                        response_json CLOB NOT NULL,
                        PRIMARY KEY (user_id, request_id, operation_id)
                    )
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO agent_session (user_id, session_id) VALUES (?, ?)
                    """)) {
                insert.setString(1, "dev_user");
                insert.setString(2, sessionId());
                insert.executeUpdate();
            }
        }
        return new ContextSummaryDataPort(source);
    }

    private ContextSummaryDataPort.StoreCommand command(String text) {
        return new ContextSummaryDataPort.StoreCommand(
                "dev_user",
                sessionId(),
                "compact-request-1",
                "compact:compact-request-1",
                0,
                "cursor-1",
                "assistant-5",
                mapper.createArrayNode().add(mapper.createObjectNode()
                        .put("kind", "message")
                        .put("ref_id", "assistant-5")),
                text);
    }

    private int summaryVersion() throws SQLException {
        try (var connection = source.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT version FROM session_summary")) {
            result.next();
            return result.getInt(1);
        }
    }

    private int operationCount() throws SQLException {
        try (var connection = source.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT COUNT(*) FROM context_summary_operation")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String sessionId() {
        return "session_20260911_101112_dev_user";
    }
}
