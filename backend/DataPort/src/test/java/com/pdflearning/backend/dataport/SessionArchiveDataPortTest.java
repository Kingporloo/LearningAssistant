package com.pdflearning.backend.dataport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class SessionArchiveDataPortTest {
    @Test
    void searchesAndReadsExactTextWithinTrustedSnapshot() throws Exception {
        var source = database();
        insert(source, "user-1", "session-1", "old-message", "user",
                "背景段落\n\nattention mechanism details", "2026-09-01T08:00:00Z");
        insert(source, "user-1", "session-1", "cursor-message", "assistant",
                "当前快照上界", "2026-09-01T08:01:00Z");
        insert(source, "user-1", "session-1", "future-message", "user",
                "future attention text", "2026-09-01T08:02:00Z");
        insert(source, "user-2", "session-1", "other-message", "user",
                "attention from another user", "2026-09-01T07:59:00Z");
        var archive = new SessionArchiveDataPort(source);

        var hits = archive.search(
                "user-1", "session-1", "cursor-message", "attention mechanism", 10);

        assertEquals(1, hits.size());
        assertEquals("old-message", hits.getFirst().messageId());
        assertEquals("p2", hits.getFirst().spanId());
        var text = archive.read(
                "user-1", "session-1", "cursor-message",
                java.util.List.of(new SessionArchiveDataPort.Ref("old-message", "p2")));
        assertEquals(1, text.size());
        assertEquals("attention mechanism details", text.getFirst().content());
        assertThrows(
                IllegalArgumentException.class,
                () -> archive.search(
                        "user-2", "session-1", "cursor-message", "attention", 10));
    }

    private static JdbcDataSource database() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE chat_message (
                        message_id VARCHAR(160) PRIMARY KEY,
                        user_id VARCHAR(128) NOT NULL,
                        session_id VARCHAR(160) NOT NULL,
                        role VARCHAR(16) NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        content CLOB NOT NULL,
                        created_at TIMESTAMP(6) NOT NULL)
                    """);
        }
        return source;
    }

    private static void insert(
            JdbcDataSource source,
            String userId,
            String sessionId,
            String messageId,
            String role,
            String content,
            String createdAt) throws Exception {
        try (var connection = source.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO chat_message
                            (message_id, user_id, session_id, role, status, content, created_at)
                        VALUES (?, ?, ?, ?, 'completed', ?, ?)
                        """)) {
            statement.setString(1, messageId);
            statement.setString(2, userId);
            statement.setString(3, sessionId);
            statement.setString(4, role);
            statement.setString(5, content);
            statement.setTimestamp(6, Timestamp.from(Instant.parse(createdAt)));
            statement.executeUpdate();
        }
    }
}

