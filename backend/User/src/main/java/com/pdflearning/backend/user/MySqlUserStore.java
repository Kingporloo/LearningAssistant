package com.pdflearning.backend.user;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * 用户与登录令牌的 MySQL 存储。启动时自动建表（幂等 DDL）。
 */
public final class MySqlUserStore {
    private final DataSource dataSource;

    public MySqlUserStore(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTables();
    }

    private void ensureTables() {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        user_id VARCHAR(64) NOT NULL PRIMARY KEY,
                        username VARCHAR(32) NOT NULL UNIQUE,
                        nickname VARCHAR(32) NOT NULL,
                        password_hash VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS user_login_token (
                        token_hash VARCHAR(64) NOT NULL PRIMARY KEY,
                        user_id VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
                        expires_at TIMESTAMP(6) NOT NULL
                    )
                    """);
        } catch (SQLException exception) {
            throw new UserServiceException(503, "初始化用户表失败：" + exception.getMessage());
        }
    }

    public void insertUser(UserRecord user) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO users
                            (user_id, username, nickname, password_hash, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setString(1, user.userId());
            statement.setString(2, user.username());
            statement.setString(3, user.nickname());
            statement.setString(4, user.passwordHash());
            statement.setTimestamp(5, timestamp(user.createdAt()));
            statement.setTimestamp(6, timestamp(user.updatedAt()));
            statement.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException exception) {
            throw new UserServiceException(409, "用户名已被占用");
        } catch (SQLException exception) {
            if ("23000".equals(exception.getSQLState())) {
                throw new UserServiceException(409, "用户名已被占用");
            }
            throw new UserServiceException(503, "MySQL 创建用户失败：" + exception.getMessage());
        }
    }

    public Optional<UserRecord> findByUsername(String username) {
        return find(
                "SELECT user_id, username, nickname, password_hash, created_at, updated_at"
                        + " FROM users WHERE username = ?",
                statement -> statement.setString(1, username));
    }

    public Optional<UserRecord> findByUserId(String userId) {
        return find(
                "SELECT user_id, username, nickname, password_hash, created_at, updated_at"
                        + " FROM users WHERE user_id = ?",
                statement -> statement.setString(1, userId));
    }

    public void updateNickname(String userId, String nickname, OffsetDateTime updatedAt) {
        update(
                "UPDATE users SET nickname = ?, updated_at = ? WHERE user_id = ?",
                statement -> {
                    statement.setString(1, nickname);
                    statement.setTimestamp(2, timestamp(updatedAt));
                    statement.setString(3, userId);
                },
                "更新昵称失败");
    }

    public void updatePasswordHash(String userId, String passwordHash, OffsetDateTime updatedAt) {
        update(
                "UPDATE users SET password_hash = ?, updated_at = ? WHERE user_id = ?",
                statement -> {
                    statement.setString(1, passwordHash);
                    statement.setTimestamp(2, timestamp(updatedAt));
                    statement.setString(3, userId);
                },
                "更新密码失败");
    }

    // ---------- 登录令牌（仅存储 SHA-256 哈希，不存明文） ----------

    public void insertToken(String tokenHash, String userId, OffsetDateTime expiresAt) {
        deleteExpired();
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO user_login_token (token_hash, user_id, expires_at)
                        VALUES (?, ?, ?)
                        """)) {
            statement.setString(1, tokenHash);
            statement.setString(2, userId);
            statement.setTimestamp(3, timestamp(expiresAt));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new UserServiceException(503, "MySQL 写入登录令牌失败：" + exception.getMessage());
        }
    }

    public Optional<UserRecord> findUserByTokenHash(String tokenHash, OffsetDateTime now) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT u.user_id, u.username, u.nickname, u.password_hash,
                               u.created_at, u.updated_at
                        FROM user_login_token t
                        JOIN users u ON u.user_id = t.user_id
                        WHERE t.token_hash = ? AND t.expires_at > ?
                        """)) {
            statement.setString(1, tokenHash);
            statement.setTimestamp(2, timestamp(now));
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(userRecord(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new UserServiceException(503, "MySQL 校验登录令牌失败：" + exception.getMessage());
        }
    }

    public void deleteToken(String tokenHash) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "DELETE FROM user_login_token WHERE token_hash = ?")) {
            statement.setString(1, tokenHash);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new UserServiceException(503, "MySQL 删除登录令牌失败：" + exception.getMessage());
        }
    }

    /** 修改密码后吊销该用户其余所有令牌（保留当前会话）。 */
    public void deleteTokensForUserExcept(String userId, String keepTokenHash) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "DELETE FROM user_login_token WHERE user_id = ? AND token_hash <> ?")) {
            statement.setString(1, userId);
            statement.setString(2, keepTokenHash);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new UserServiceException(503, "MySQL 吊销登录令牌失败：" + exception.getMessage());
        }
    }

    private void deleteExpired() {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "DELETE FROM user_login_token WHERE expires_at <= CURRENT_TIMESTAMP(6)")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new UserServiceException(503, "MySQL 清理过期令牌失败：" + exception.getMessage());
        }
    }

    // ---------- 内部工具 ----------

    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private Optional<UserRecord> find(String sql, StatementBinder binder) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(userRecord(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new UserServiceException(503, "MySQL 查询用户失败：" + exception.getMessage());
        }
    }

    private void update(String sql, StatementBinder binder, String action) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new UserServiceException(503, "MySQL " + action + "：" + exception.getMessage());
        }
    }

    private static UserRecord userRecord(ResultSet result) throws SQLException {
        return new UserRecord(
                result.getString("user_id"),
                result.getString("username"),
                result.getString("nickname"),
                result.getString("password_hash"),
                offsetDateTime(result.getTimestamp("created_at")),
                offsetDateTime(result.getTimestamp("updated_at")));
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return Timestamp.from(value.toInstant());
    }

    private static OffsetDateTime offsetDateTime(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }
}
