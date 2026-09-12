package com.pdflearning.backend.dataport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/** 前端可见会话和消息的 MySQL 数据端口。 */
public final class ChatDataPort {
    public enum DeleteResult {
        DELETED,
        NOT_FOUND,
        BUSY
    }

    public record SessionData(
            String sessionId,
            String userId,
            String title,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int messageCount) {
    }

    public record MessageData(
            String messageId,
            String userId,
            String sessionId,
            String requestId,
            String role,
            String status,
            String content,
            JsonNode segments,
            JsonNode usage,
            Integer modelSteps,
            Integer toolRounds,
            JsonNode error,
            OffsetDateTime createdAt) {
    }

    public record DialogueTurn(MessageData userMessage, MessageData assistantMessage) {
    }

    public record HistorySlice(boolean cursorFound, List<DialogueTurn> turns) {
        public HistorySlice {
            turns = List.copyOf(turns);
        }
    }

    public record AssistantWrite(
            String messageId,
            String userId,
            String sessionId,
            String requestId,
            String status,
            String content,
            JsonNode segments,
            JsonNode usage,
            Integer modelSteps,
            Integer toolRounds,
            JsonNode error,
            OffsetDateTime createdAt) {
    }

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public ChatDataPort(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    ChatDataPort(DataSource dataSource, ObjectMapper mapper) {
        if (dataSource == null || mapper == null) {
            throw new IllegalArgumentException("ChatDataPort 依赖不能为空");
        }
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    public Optional<SessionData> findSession(String sessionId) {
        requireText(sessionId, "session_id");
        try (var connection = dataSource.getConnection()) {
            return findSession(connection, sessionId, false);
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 查询聊天会话失败", exception);
        }
    }

    public Optional<SessionData> findActiveSession(String userId, String sessionId) {
        requireText(userId, "user_id");
        requireText(sessionId, "session_id");
        return findSession(sessionId)
                .filter(session -> session.userId().equals(userId))
                .filter(session -> "active".equals(session.status()));
    }

    public List<SessionData> listSessions(String userId) {
        requireText(userId, "user_id");
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT s.session_id, s.user_id, s.title, s.status,
                               s.created_at, s.updated_at,
                               (SELECT COUNT(*) FROM chat_message m
                                WHERE m.user_id = s.user_id
                                  AND m.session_id = s.session_id) AS message_count
                        FROM chat_session s
                        WHERE s.user_id = ? AND s.status = 'active'
                        ORDER BY s.updated_at DESC, s.session_id DESC
                        """)) {
            statement.setString(1, userId);
            try (var result = statement.executeQuery()) {
                var sessions = new ArrayList<SessionData>();
                while (result.next()) {
                    sessions.add(sessionData(result));
                }
                return List.copyOf(sessions);
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 读取聊天会话失败", exception);
        }
    }

    /** 首条用户消息与会话归属在同一事务中落库。 */
    public void saveUserMessage(
            String userId,
            String sessionId,
            String title,
            String messageId,
            String requestId,
            String content,
            OffsetDateTime createdAt) {
        requireText(userId, "user_id");
        requireText(sessionId, "session_id");
        requireText(title, "title");
        requireText(messageId, "message_id");
        requireText(requestId, "request_id");
        requireText(content, "content");
        requireTime(createdAt, "created_at");

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var session = findSession(connection, sessionId, true);
                if (session.isEmpty()) {
                    insertSession(connection, userId, sessionId, title, createdAt);
                } else {
                    requireOwnedActive(session.get(), userId);
                }
                ensureAgentSession(connection, userId, sessionId);

                var previous = findMessage(connection, messageId, userId, requestId, "user", true);
                if (previous.isPresent()) {
                    verifyUserMessage(previous.get(), userId, sessionId, messageId, requestId, content);
                    connection.commit();
                    return;
                }

                try (var statement = connection.prepareStatement("""
                        INSERT INTO chat_message
                            (message_id, user_id, session_id, request_id, role, status,
                             content, segments_json, created_at)
                        VALUES (?, ?, ?, ?, 'user', 'completed', ?, '[]', ?)
                        """)) {
                    statement.setString(1, messageId);
                    statement.setString(2, userId);
                    statement.setString(3, sessionId);
                    statement.setString(4, requestId);
                    statement.setString(5, content);
                    statement.setTimestamp(6, timestamp(createdAt));
                    statement.executeUpdate();
                }
                touchSession(connection, sessionId, createdAt);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 保存用户聊天消息失败", exception);
        }
    }

    public void saveAssistantMessage(AssistantWrite message) {
        validateAssistant(message);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var session = findSession(connection, message.sessionId(), true)
                        .orElseThrow(() -> new IllegalArgumentException("聊天会话不存在"));
                requireOwnedActive(session, message.userId());
                var previous = findMessage(
                        connection,
                        message.messageId(),
                        message.userId(),
                        message.requestId(),
                        "assistant",
                        true);
                if (previous.isEmpty()) {
                    insertAssistant(connection, message);
                } else {
                    verifyAssistantIdentity(previous.get(), message);
                    connection.commit();
                    return;
                }
                touchSession(connection, message.sessionId(), message.createdAt());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 保存助手聊天消息失败", exception);
        }
    }

    public List<MessageData> listMessages(String userId, String sessionId) {
        if (findActiveSession(userId, sessionId).isEmpty()) {
            return List.of();
        }
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT message_id, user_id, session_id, request_id, role, status,
                               content, segments_json, usage_json, model_steps, tool_rounds,
                               error_json, created_at
                        FROM chat_message
                        WHERE user_id = ? AND session_id = ?
                        ORDER BY created_at, message_id
                        """)) {
            statement.setString(1, userId);
            statement.setString(2, sessionId);
            try (var result = statement.executeQuery()) {
                var messages = new ArrayList<MessageData>();
                while (result.next()) {
                    messages.add(messageData(result));
                }
                return List.copyOf(messages);
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 读取聊天消息失败", exception);
        }
    }

    public HistorySlice readDialogueHistory(
            String userId,
            String sessionId,
            String afterMessageId) {
        var users = new LinkedHashMap<String, MessageData>();
        var pairs = new ArrayList<DialogueTurn>();
        for (var message : listMessages(userId, sessionId)) {
            if ("user".equals(message.role())) {
                users.put(message.requestId(), message);
            } else if ("assistant".equals(message.role())
                    && "completed".equals(message.status())) {
                var user = users.get(message.requestId());
                if (user != null) {
                    pairs.add(new DialogueTurn(user, message));
                }
            }
        }
        if (afterMessageId == null) {
            return new HistorySlice(true, pairs);
        }
        var filtered = new ArrayList<DialogueTurn>();
        boolean found = false;
        for (var pair : pairs) {
            if (!found) {
                found = afterMessageId.equals(pair.userMessage().messageId())
                        || afterMessageId.equals(pair.assistantMessage().messageId());
            } else {
                filtered.add(pair);
            }
        }
        return new HistorySlice(found, found ? filtered : List.of());
    }

    public DeleteResult deleteSession(String userId, String sessionId) {
        requireText(userId, "user_id");
        requireText(sessionId, "session_id");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var session = findSession(connection, sessionId, true);
                if (session.isEmpty()
                        || !session.get().userId().equals(userId)
                        || !"active".equals(session.get().status())) {
                    connection.commit();
                    return DeleteResult.NOT_FOUND;
                }
                if (hasActiveRun(connection, userId, sessionId)) {
                    connection.commit();
                    return DeleteResult.BUSY;
                }
                try (var statement = connection.prepareStatement("""
                        UPDATE chat_session
                        SET status = 'deleted', updated_at = CURRENT_TIMESTAMP(6)
                        WHERE session_id = ? AND user_id = ? AND status = 'active'
                        """)) {
                    statement.setString(1, sessionId);
                    statement.setString(2, userId);
                    statement.executeUpdate();
                }
                connection.commit();
                return DeleteResult.DELETED;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 删除聊天会话失败", exception);
        }
    }

    private static void insertSession(
            Connection connection,
            String userId,
            String sessionId,
            String title,
            OffsetDateTime createdAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO chat_session
                    (session_id, user_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, 'active', ?, ?)
                """)) {
            statement.setString(1, sessionId);
            statement.setString(2, userId);
            statement.setString(3, title);
            statement.setTimestamp(4, timestamp(createdAt));
            statement.setTimestamp(5, timestamp(createdAt));
            statement.executeUpdate();
        }
    }

    private static void ensureAgentSession(
            Connection connection,
            String userId,
            String sessionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO agent_session (user_id, session_id)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP(6)
                """)) {
            statement.setString(1, userId);
            statement.setString(2, sessionId);
            statement.executeUpdate();
        }
    }

    private static void insertAssistant(Connection connection, AssistantWrite message)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO chat_message
                    (message_id, user_id, session_id, request_id, role, status,
                     content, segments_json, usage_json, model_steps, tool_rounds,
                     error_json, created_at)
                VALUES (?, ?, ?, ?, 'assistant', ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindAssistant(statement, message);
            statement.executeUpdate();
        }
    }

    private static void bindAssistant(
            java.sql.PreparedStatement statement,
            AssistantWrite message) throws SQLException {
        statement.setString(1, message.messageId());
        statement.setString(2, message.userId());
        statement.setString(3, message.sessionId());
        statement.setString(4, message.requestId());
        statement.setString(5, message.status());
        statement.setString(6, message.content());
        statement.setString(7, jsonOrNull(message.segments()));
        statement.setString(8, jsonOrNull(message.usage()));
        nullableInt(statement, 9, message.modelSteps());
        nullableInt(statement, 10, message.toolRounds());
        statement.setString(11, jsonOrNull(message.error()));
        statement.setTimestamp(12, timestamp(message.createdAt()));
    }

    private static void touchSession(
            Connection connection,
            String sessionId,
            OffsetDateTime updatedAt) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE chat_session SET updated_at = ? WHERE session_id = ?")) {
            statement.setTimestamp(1, timestamp(updatedAt));
            statement.setString(2, sessionId);
            statement.executeUpdate();
        }
    }

    private Optional<SessionData> findSession(
            Connection connection,
            String sessionId,
            boolean lock) throws SQLException {
        var sql = lock
                ? """
                  SELECT session_id, user_id, title, status,
                         created_at, updated_at, 0 AS message_count
                  FROM chat_session WHERE session_id = ? FOR UPDATE
                  """
                : """
                  SELECT s.session_id, s.user_id, s.title, s.status,
                         s.created_at, s.updated_at,
                         (SELECT COUNT(*) FROM chat_message m
                          WHERE m.user_id = s.user_id
                            AND m.session_id = s.session_id) AS message_count
                  FROM chat_session s WHERE s.session_id = ?
                  """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(sessionData(result)) : Optional.empty();
            }
        }
    }

    private Optional<MessageData> findMessage(
            Connection connection,
            String messageId,
            String userId,
            String requestId,
            String role,
            boolean lock) throws SQLException {
        var sql = """
                SELECT message_id, user_id, session_id, request_id, role, status,
                       content, segments_json, usage_json, model_steps, tool_rounds,
                       error_json, created_at
                FROM chat_message
                WHERE message_id = ? OR (user_id = ? AND request_id = ? AND role = ?)
                """ + (lock ? " FOR UPDATE" : "");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, messageId);
            statement.setString(2, userId);
            statement.setString(3, requestId);
            statement.setString(4, role);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(messageData(result)) : Optional.empty();
            }
        }
    }

    private JsonNode jsonNode(ResultSet result, String column) throws SQLException {
        String value = result.getString(column);
        if (value == null) {
            return null;
        }
        try {
            return mapper.readTree(value);
        } catch (Exception exception) {
            throw new DataPortException("数据库中的聊天 JSON 无效", exception);
        }
    }

    private MessageData messageData(ResultSet result) throws SQLException {
        int modelSteps = result.getInt("model_steps");
        Integer nullableModelSteps = result.wasNull() ? null : modelSteps;
        int toolRounds = result.getInt("tool_rounds");
        Integer nullableToolRounds = result.wasNull() ? null : toolRounds;
        return new MessageData(
                result.getString("message_id"),
                result.getString("user_id"),
                result.getString("session_id"),
                result.getString("request_id"),
                result.getString("role"),
                result.getString("status"),
                result.getString("content"),
                jsonNode(result, "segments_json"),
                jsonNode(result, "usage_json"),
                nullableModelSteps,
                nullableToolRounds,
                jsonNode(result, "error_json"),
                offsetDateTime(result.getTimestamp("created_at")));
    }

    private static SessionData sessionData(ResultSet result) throws SQLException {
        return new SessionData(
                result.getString("session_id"),
                result.getString("user_id"),
                result.getString("title"),
                result.getString("status"),
                offsetDateTime(result.getTimestamp("created_at")),
                offsetDateTime(result.getTimestamp("updated_at")),
                result.getInt("message_count"));
    }

    private static boolean hasActiveRun(
            Connection connection,
            String userId,
            String sessionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT active_request_id FROM agent_session
                WHERE user_id = ? AND session_id = ?
                """)) {
            statement.setString(1, userId);
            statement.setString(2, sessionId);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getString("active_request_id") != null;
            }
        }
    }

    private static void requireOwnedActive(SessionData session, String userId) {
        if (!session.userId().equals(userId) || !"active".equals(session.status())) {
            throw new IllegalArgumentException("聊天会话不存在");
        }
    }

    private static void verifyUserMessage(
            MessageData previous,
            String userId,
            String sessionId,
            String messageId,
            String requestId,
            String content) {
        if (!previous.userId().equals(userId)
                || !previous.sessionId().equals(sessionId)
                || !previous.messageId().equals(messageId)
                || !previous.requestId().equals(requestId)
                || !"user".equals(previous.role())
                || !previous.content().equals(content)) {
            throw new IllegalStateException("request_id 或 message_id 已被其他消息使用");
        }
    }

    private static void verifyAssistantIdentity(
            MessageData previous,
            AssistantWrite message) {
        if (!previous.messageId().equals(message.messageId())
                || !previous.userId().equals(message.userId())
                || !previous.sessionId().equals(message.sessionId())
                || !previous.requestId().equals(message.requestId())
                || !"assistant".equals(previous.role())) {
            throw new IllegalStateException("助手消息标识与已有记录冲突");
        }
    }

    private static void validateAssistant(AssistantWrite message) {
        if (message == null) {
            throw new IllegalArgumentException("助手消息不能为空");
        }
        requireText(message.messageId(), "message_id");
        requireText(message.userId(), "user_id");
        requireText(message.sessionId(), "session_id");
        requireText(message.requestId(), "request_id");
        if (!("completed".equals(message.status()) || "failed".equals(message.status()))) {
            throw new IllegalArgumentException("助手消息状态无效");
        }
        if (message.content() == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        requireTime(message.createdAt(), "created_at");
    }

    private static String jsonOrNull(JsonNode value) {
        return value == null || value.isNull() ? null : value.toString();
    }

    private static void nullableInt(
            java.sql.PreparedStatement statement,
            int index,
            Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private static void requireTime(OffsetDateTime value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
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
        return value.toInstant().atOffset(ZoneOffset.UTC);
    }
}
