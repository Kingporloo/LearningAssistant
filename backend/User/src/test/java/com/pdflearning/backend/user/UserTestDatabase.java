package com.pdflearning.backend.user;

import com.pdflearning.backend.dataport.UserDataPort;
import java.sql.SQLException;
import org.h2.jdbcx.JdbcDataSource;

final class UserTestDatabase {
    private UserTestDatabase() {
    }

    static UserDataPort create() {
        return new UserDataPort(initialize("jdbc:h2:mem:" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
    }

    static JdbcDataSource initialize(String url) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        dataSource.setUser("sa");
        dataSource.setPassword("test");
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (
                        user_id VARCHAR(64) NOT NULL PRIMARY KEY,
                        username VARCHAR(32) NOT NULL UNIQUE,
                        nickname VARCHAR(32) NOT NULL,
                        password_hash VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP(6) NOT NULL,
                        updated_at TIMESTAMP(6) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE user_login_token (
                        token_hash CHAR(64) NOT NULL PRIMARY KEY,
                        user_id VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        expires_at TIMESTAMP(6) NOT NULL,
                        FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
                    )
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法初始化用户测试数据库", exception);
        }
        return dataSource;
    }
}
