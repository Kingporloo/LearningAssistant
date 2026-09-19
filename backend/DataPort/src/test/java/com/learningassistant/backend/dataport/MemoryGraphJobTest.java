package com.learningassistant.backend.dataport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryGraphJobTest {
    private JdbcDataSource source;
    private MySqlDataStore store;

    @BeforeEach
    void setUp() throws Exception {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE long_term_memory (
                        memory_id VARCHAR(36) PRIMARY KEY,
                        user_id VARCHAR(128) NOT NULL,
                        memory_type VARCHAR(16) NOT NULL,
                        content CLOB NOT NULL,
                        importance DOUBLE NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        index_status VARCHAR(24) NOT NULL,
                        revision INT NOT NULL,
                        graph_status VARCHAR(16) NOT NULL,
                        graph_error CLOB,
                        graph_updated_at TIMESTAMP(6),
                        source_session_id VARCHAR(160) NOT NULL,
                        source_message_id VARCHAR(160) NOT NULL,
                        event_time TIMESTAMP(6),
                        graph_json JSON NOT NULL,
                        created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE storage_operation (
                        user_id VARCHAR(128) NOT NULL,
                        request_id VARCHAR(160) NOT NULL,
                        operation_id VARCHAR(200) NOT NULL,
                        operation_type VARCHAR(32) NOT NULL,
                        response_json JSON NOT NULL,
                        created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        PRIMARY KEY (user_id, request_id, operation_id)
                    )
                    """);
            statement.execute("""
                    INSERT INTO long_term_memory
                        (memory_id, user_id, memory_type, content, importance, status,
                         index_status, revision, graph_status, source_session_id,
                         source_message_id, graph_json)
                    VALUES
                        ('c08eae45-249a-4a89-bf70-6610f94a0563', 'dev_user', 'semantic',
                         '用户偏好简洁回答', 0.8, 'active', 'ok', 2, 'pending',
                         'session_20260915_010203_dev_user', 'message-1', JSON '{}')
                    """);
        }
        store = new MySqlDataStore(source);
    }

    @Test
    void claimsAndCompletesOnePendingJob() throws Exception {
        var job = store.claimSemanticGraphJob().orElseThrow();

        assertEquals("dev_user", job.userId());
        assertEquals(2, job.revision());
        assertTrue(store.isCurrentSemanticGraphJob(job.userId(), job.memoryId(), job.revision()));
        assertTrue(store.completeSemanticGraphJob(
                job.userId(), job.memoryId(), job.revision(),
                "{\"entities\":[],\"relations\":[]}", "ok", null));
        assertTrue(store.claimSemanticGraphJob().isEmpty());
        assertEquals("ok", graphStatus());
    }

    @Test
    void rejectsStaleRevisionAndRecoversInterruptedJob() throws Exception {
        var job = store.claimSemanticGraphJob().orElseThrow();

        assertFalse(store.completeSemanticGraphJob(
                job.userId(), job.memoryId(), job.revision() - 1,
                "{\"entities\":[],\"relations\":[]}", "ok", null));
        assertEquals(1, store.recoverSemanticGraphJobs());
        assertEquals("pending", graphStatus());
        assertEquals(job.memoryId(), store.claimSemanticGraphJob().orElseThrow().memoryId());
    }

    @Test
    void correctionIncrementsRevisionAndReplacesThePendingTask() throws Exception {
        var oldJob = store.claimSemanticGraphJob().orElseThrow();

        var correction = store.beginMemoryStore(
                "dev_user",
                "request-2",
                "operation-2",
                oldJob.memoryId(),
                "semantic",
                "用户偏好详细回答",
                0.9,
                "session_20260915_010203_dev_user",
                "message-2",
                null,
                "{\"entities\":[],\"relations\":[]}",
                true);

        assertTrue(correction.claimed());
        assertEquals(3, correction.revision());
        assertEquals("pending", graphStatus());
        assertFalse(store.isCurrentSemanticGraphJob(
                oldJob.userId(), oldJob.memoryId(), oldJob.revision()));
    }

    private String graphStatus() throws Exception {
        try (var connection = source.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT graph_status FROM long_term_memory")) {
            result.next();
            return result.getString(1);
        }
    }
}
