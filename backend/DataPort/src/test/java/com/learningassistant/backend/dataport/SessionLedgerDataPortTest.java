package com.learningassistant.backend.dataport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class SessionLedgerDataPortTest {
    @Test
    void appliesVersionedPatchesAndRejectsAStaleBaseVersion() throws Exception {
        var source = database();
        var ledgers = new SessionLedgerDataPort(source);

        apply(source, ledgers, """
                {"payload":{"base_version":0,"operations":[{
                  "op":"add","entry":{
                    "id":"goal-1","type":"goal","content":"理解注意力机制",
                    "source_refs":["request-1:call-1"],"status":"active","scope":"session",
                    "supersedes":[],"created_at":"2026-09-15T08:00:00Z",
                    "updated_at":"2026-09-15T08:00:00Z"
                  }
                }]}}
                """, true);

        var first = ledgers.find("user-1", "session-1").orElseThrow();
        assertEquals(1, first.version());
        assertEquals("goal-1", first.entries().get(0).path("id").asText());

        apply(source, ledgers, """
                {"payload":{"base_version":0,"operations":[{
                  "op":"resolve","entry_id":"goal-1","source_refs":["stale"]
                }]}}
                """, false);
        assertEquals(1, ledgers.find("user-1", "session-1").orElseThrow().version());

        apply(source, ledgers, """
                {"payload":{"base_version":1,"operations":[{
                  "op":"resolve","entry_id":"goal-1","source_refs":["request-2:call-2"]
                }]}}
                """, true);
        var resolved = ledgers.find("user-1", "session-1").orElseThrow();
        assertEquals(2, resolved.version());
        assertEquals("resolved", resolved.entries().get(0).path("status").asText());
        assertEquals(2, resolved.entries().get(0).path("source_refs").size());
    }

    private static void apply(
            JdbcDataSource source,
            SessionLedgerDataPort ledgers,
            String event,
            boolean expected) throws Exception {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            boolean applied = ledgers.applyEvent(
                    connection, "user-1", "session-1", event);
            if (expected) {
                assertTrue(applied);
                connection.commit();
            } else {
                assertFalse(applied);
                connection.rollback();
            }
        }
    }

    private static JdbcDataSource database() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
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
        }
        return source;
    }
}

