package com.pdflearning.backend.dataport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;

/** 用户、密码哈希和登录令牌的 MySQL 数据端口。 */
public final class UserDataPort {
    private final DataSource dataSource;

    public UserDataPort(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record UserData(
            String userId,
            String username,
            String nickname,
            String passwordHash,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    /** 在同一事务中创建用户并发放首个令牌；用户名冲突时返回 false。 */
    public boolean register(UserData user, String tokenHash, OffsetDateTime expiresAt) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertUser(connection, user);
                insertToken(connection, tokenHash, user.userId(), expiresAt);
                connection.commit();
                return true;
            } catch (SQLIntegrityConstraintViolationException exception) {
                rollback(connection, exception);
                return false;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 创建用户失败", exception);
        }
    }

    public Optional<UserData> findByUsername(String username) {
        return find(
                "SELECT user_id, username, nickname, password_hash, created_at, updated_at"
                        + " FROM users WHERE username = ?",
                statement -> statement.setString(1, username));
    }

    public Optional<UserData> findByUserId(String userId) {
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
                "MySQL 更新用户昵称失败");
    }

    public void insertToken(String tokenHash, String userId, OffsetDateTime expiresAt) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                deleteExpired(connection);
                insertToken(connection, tokenHash, userId, expiresAt);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 写入登录令牌失败", exception);
        }
    }

    public Optional<UserData> findUserByTokenHash(String tokenHash, OffsetDateTime now) {
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
                return result.next() ? Optional.of(userData(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 校验登录令牌失败", exception);
        }
    }

    public void deleteToken(String tokenHash) {
        update(
                "DELETE FROM user_login_token WHERE token_hash = ?",
                statement -> statement.setString(1, tokenHash),
                "MySQL 删除登录令牌失败");
    }

    /** 密码更新和其他令牌吊销共享一个事务。 */
    public void changePassword(
            String userId,
            String passwordHash,
            OffsetDateTime updatedAt,
            String keepTokenHash) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var password = connection.prepareStatement(
                        "UPDATE users SET password_hash = ?, updated_at = ? WHERE user_id = ?");
                    var tokens = connection.prepareStatement(
                        "DELETE FROM user_login_token WHERE user_id = ? AND token_hash <> ?")) {
                password.setString(1, passwordHash);
                password.setTimestamp(2, timestamp(updatedAt));
                password.setString(3, userId);
                password.executeUpdate();

                tokens.setString(1, userId);
                tokens.setString(2, keepTokenHash);
                tokens.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 修改用户密码失败", exception);
        }
    }

    private static void insertUser(Connection connection, UserData user) throws SQLException {
        try (var statement = connection.prepareStatement("""
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
        }
    }

    private static void insertToken(
            Connection connection,
            String tokenHash,
            String userId,
            OffsetDateTime expiresAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO user_login_token (token_hash, user_id, expires_at)
                VALUES (?, ?, ?)
                """)) {
            statement.setString(1, tokenHash);
            statement.setString(2, userId);
            statement.setTimestamp(3, timestamp(expiresAt));
            statement.executeUpdate();
        }
    }

    private static void deleteExpired(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "DELETE FROM user_login_token WHERE expires_at <= CURRENT_TIMESTAMP(6)")) {
            statement.executeUpdate();
        }
    }

    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private Optional<UserData> find(String sql, StatementBinder binder) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(userData(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 查询用户失败", exception);
        }
    }

    private void update(String sql, StatementBinder binder, String message) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new DataPortException(message, exception);
        }
    }

    private static UserData userData(ResultSet result) throws SQLException {
        return new UserData(
                result.getString("user_id"),
                result.getString("username"),
                result.getString("nickname"),
                result.getString("password_hash"),
                offsetDateTime(result.getTimestamp("created_at")),
                offsetDateTime(result.getTimestamp("updated_at")));
    }

    private static void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return Timestamp.from(value.toInstant());
    }

    private static OffsetDateTime offsetDateTime(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }
}
