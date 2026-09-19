package com.learningassistant.backend.dataport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class AgentConfigDataPortTest {
    @Test
    void savesAndUpdatesConfigurationWithinUserScope() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE user_agent_config (
                        user_id VARCHAR(64) PRIMARY KEY,
                        model_name VARCHAR(128) NOT NULL,
                        persona VARCHAR(2000) NOT NULL,
                        enabled_tools_json CLOB NOT NULL,
                        created_at TIMESTAMP(6) NOT NULL,
                        updated_at TIMESTAMP(6) NOT NULL)
                    """);
        }

        var port = new AgentConfigDataPort(source);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        assertTrue(port.find("user-a").isEmpty());

        port.save(
                "user-a",
                "model-a",
                "先举例",
                List.of("rag__rag_search"),
                now);
        port.save(
                "user-b",
                "model-b",
                "简短回答",
                List.of("memory__memory_store"),
                now);
        port.save(
                "user-a",
                "model-a",
                "先定义再举例",
                List.of("rag__rag_search", "memory__memory_store"),
                now.plusSeconds(1));

        var first = port.find("user-a").orElseThrow();
        var second = port.find("user-b").orElseThrow();
        assertEquals("先定义再举例", first.persona());
        assertEquals(
                List.of("rag__rag_search", "memory__memory_store"),
                first.enabledTools());
        assertEquals("简短回答", second.persona());
        assertEquals(List.of("memory__memory_store"), second.enabledTools());
    }
}
