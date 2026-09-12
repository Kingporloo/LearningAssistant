package com.pdflearning.backend.dataport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class ChatDataPortTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsSessionWithFirstMessageAndKeepsGlobalOwnership() {
        var port = createDatabase().port();
        var now = OffsetDateTime.parse("2026-09-12T03:00:00Z");

        port.saveUserMessage(
                "user_a", "session_1", "注意力机制", "message_1", "request_1", "问题", now);
        port.saveUserMessage(
                "user_a", "session_1", "注意力机制", "message_1", "request_1", "问题", now);

        assertEquals(1, port.listSessions("user_a").size());
        assertEquals(1, port.listMessages("user_a", "session_1").size());
        assertThrows(IllegalArgumentException.class, () -> port.saveUserMessage(
                "user_b",
                "session_1",
                "其他会话",
                "message_2",
                "request_2",
                "越权问题",
                now));
        assertEquals(0, port.listSessions("user_b").size());
    }

    @Test
    void returnsOnlyCompletedDialogueAfterSummaryCursor() {
        var port = createDatabase().port();
        var first = OffsetDateTime.parse("2026-09-12T03:00:00Z");
        saveTurn(port, "1", first);
        saveTurn(port, "2", first.plusMinutes(1));

        var history = port.readDialogueHistory("user_a", "session_1", "assistant_request_1");

        assertEquals(true, history.cursorFound());
        assertEquals(1, history.turns().size());
        assertEquals("message_2", history.turns().getFirst().userMessage().messageId());
        assertEquals(4, port.listSessions("user_a").getFirst().messageCount());
    }

    @Test
    void refusesDeletionWhileTheSessionHasAnActiveRun() throws Exception {
        var database = createDatabase();
        var port = database.port();
        var now = OffsetDateTime.parse("2026-09-12T03:00:00Z");
        port.saveUserMessage(
                "user_a", "session_1", "会话", "message_1", "request_1", "问题", now);
        setActiveRun(database.source(), "request_1");

        assertEquals(ChatDataPort.DeleteResult.BUSY, port.deleteSession("user_a", "session_1"));
    }

    private void saveTurn(ChatDataPort port, String suffix, OffsetDateTime at) {
        String requestId = "request_" + suffix;
        port.saveUserMessage(
                "user_a",
                "session_1",
                "会话",
                "message_" + suffix,
                requestId,
                "问题" + suffix,
                at);
        port.saveAssistantMessage(new ChatDataPort.AssistantWrite(
                "assistant_" + requestId,
                "user_a",
                "session_1",
                requestId,
                "completed",
                "回答" + suffix,
                mapper.createArrayNode(),
                mapper.createObjectNode().put("total_tokens", 10),
                1,
                0,
                null,
                at.plusSeconds(1)));
    }

    private TestDatabase createDatabase() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE chat_session (
                        session_id VARCHAR(160) PRIMARY KEY,
                        user_id VARCHAR(64) NOT NULL,
                        title VARCHAR(100) NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        created_at TIMESTAMP(6) NOT NULL,
                        updated_at TIMESTAMP(6) NOT NULL
                    )
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
                        UNIQUE (user_id, request_id, role)
                    )
                    """);
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
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
        return new TestDatabase(new ChatDataPort(source), source);
    }

    private void setActiveRun(JdbcDataSource source, String requestId) throws Exception {
        try (var connection = source.getConnection();
                var statement = connection.prepareStatement("""
                        UPDATE agent_session SET active_request_id = ?
                        WHERE user_id = 'user_a' AND session_id = 'session_1'
                        """)) {
            statement.setString(1, requestId);
            statement.executeUpdate();
        }
    }

    private record TestDatabase(ChatDataPort port, JdbcDataSource source) {
    }
}
