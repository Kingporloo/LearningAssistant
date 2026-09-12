package com.pdflearning.backend.dataport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class UserDataPortTest {
    @Test
    void passwordAndTokenRevocationRollbackTogether() throws Exception {
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
                        updated_at TIMESTAMP(6) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE user_login_token (
                        token_hash CHAR(64) PRIMARY KEY,
                        user_id VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        expires_at TIMESTAMP(6) NOT NULL,
                        FOREIGN KEY (user_id) REFERENCES users(user_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE token_guard (
                        token_hash CHAR(64) PRIMARY KEY,
                        FOREIGN KEY (token_hash) REFERENCES user_login_token(token_hash)
                    )
                    """);
        }

        var port = new UserDataPort(source);
        var now = OffsetDateTime.now();
        var keptToken = "a".repeat(64);
        var revokedToken = "b".repeat(64);
        port.register(
                new UserDataPort.UserData(
                        "user_1", "alice", "Alice", "old-hash", now, now),
                keptToken,
                now.plusDays(7));
        port.insertToken(revokedToken, "user_1", now.plusDays(7));
        try (var connection = source.getConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO token_guard (token_hash) VALUES (?)")) {
            statement.setString(1, revokedToken);
            statement.executeUpdate();
        }

        assertThrows(
                DataPortException.class,
                () -> port.changePassword("user_1", "new-hash", now.plusMinutes(1), keptToken));

        assertEquals("old-hash", port.findByUserId("user_1").orElseThrow().passwordHash());
    }
}
