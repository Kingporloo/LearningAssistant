package com.pdflearning.backend.dataport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/** 会话摘要的版本检查与幂等保存。 */
public final class ContextSummaryDataPort {
    public record StoreCommand(
            String userId,
            String sessionId,
            String requestId,
            String operationId,
            int baseVersion,
            String historyCursor,
            String throughMessageId,
            JsonNode sourceRefs,
            String text) {
    }

    private record StoredOperation(String requestHash, String responseJson) {
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public ContextSummaryDataPort(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    ContextSummaryDataPort(DataSource dataSource, ObjectMapper mapper) {
        if (dataSource == null || mapper == null) {
            throw new IllegalArgumentException("ContextSummaryDataPort 依赖不能为空");
        }
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    public Map<String, Object> store(StoreCommand command) {
        validate(command);
        String requestHash = requestHash(command);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockSession(connection, command.userId(), command.sessionId());
                var previous = findOperation(connection, command, true);
                if (previous.isPresent()) {
                    if (!previous.get().requestHash().equals(requestHash)) {
                        throw new IllegalStateException(
                                "operation_id 已被不同的摘要保存请求使用");
                    }
                    var response = parse(previous.get().responseJson());
                    connection.commit();
                    return response;
                }

                Integer currentVersion = findVersion(connection, command, true).orElse(null);
                if ((currentVersion == null && command.baseVersion() != 0)
                        || (currentVersion != null
                        && currentVersion != command.baseVersion())) {
                    connection.commit();
                    return versionConflict(currentVersion == null ? 0 : currentVersion);
                }

                int nextVersion = command.baseVersion() + 1;
                saveSummary(connection, command, nextVersion, currentVersion == null);
                var response = savedResponse(command, nextVersion);
                insertOperation(connection, command, requestHash, json(response));
                connection.commit();
                return response;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 保存会话摘要失败", exception);
        }
    }

    private static void lockSession(
            Connection connection,
            String userId,
            String sessionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT session_id FROM agent_session
                WHERE user_id = ? AND session_id = ? FOR UPDATE
                """)) {
            statement.setString(1, userId);
            statement.setString(2, sessionId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Agent 会话不存在");
                }
            }
        }
    }

    private static Optional<Integer> findVersion(
            Connection connection,
            StoreCommand command,
            boolean lock) throws SQLException {
        var sql = """
                SELECT version FROM session_summary
                WHERE user_id = ? AND session_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, command.userId());
            statement.setString(2, command.sessionId());
            try (var result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(result.getInt("version"))
                        : Optional.empty();
            }
        }
    }

    private static Optional<StoredOperation> findOperation(
            Connection connection,
            StoreCommand command,
            boolean lock) throws SQLException {
        var sql = """
                SELECT request_hash, response_json FROM context_summary_operation
                WHERE user_id = ? AND request_id = ? AND operation_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, command.userId());
            statement.setString(2, command.requestId());
            statement.setString(3, command.operationId());
            try (var result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(new StoredOperation(
                                result.getString("request_hash"),
                                result.getString("response_json")))
                        : Optional.empty();
            }
        }
    }

    private static void saveSummary(
            Connection connection,
            StoreCommand command,
            int nextVersion,
            boolean insert) throws SQLException {
        var sql = insert
                ? """
                  INSERT INTO session_summary
                      (user_id, session_id, version, text, through_message_id,
                       history_cursor, source_refs_json)
                  VALUES (?, ?, ?, ?, ?, ?, ?)
                  """
                : """
                  UPDATE session_summary
                  SET version = ?, text = ?, through_message_id = ?,
                      history_cursor = ?, source_refs_json = ?,
                      updated_at = CURRENT_TIMESTAMP(6)
                  WHERE user_id = ? AND session_id = ? AND version = ?
                  """;
        try (var statement = connection.prepareStatement(sql)) {
            if (insert) {
                statement.setString(1, command.userId());
                statement.setString(2, command.sessionId());
                statement.setInt(3, nextVersion);
                statement.setString(4, command.text());
                statement.setString(5, command.throughMessageId());
                statement.setString(6, command.historyCursor());
                statement.setString(7, command.sourceRefs().toString());
            } else {
                statement.setInt(1, nextVersion);
                statement.setString(2, command.text());
                statement.setString(3, command.throughMessageId());
                statement.setString(4, command.historyCursor());
                statement.setString(5, command.sourceRefs().toString());
                statement.setString(6, command.userId());
                statement.setString(7, command.sessionId());
                statement.setInt(8, command.baseVersion());
            }
            if (statement.executeUpdate() != 1) {
                throw new SQLException("会话摘要版本更新失败");
            }
        }
    }

    private static void insertOperation(
            Connection connection,
            StoreCommand command,
            String requestHash,
            String responseJson) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO context_summary_operation
                    (user_id, request_id, operation_id, session_id,
                     request_hash, response_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, command.userId());
            statement.setString(2, command.requestId());
            statement.setString(3, command.operationId());
            statement.setString(4, command.sessionId());
            statement.setString(5, requestHash);
            statement.setString(6, responseJson);
            statement.executeUpdate();
        }
    }

    private Map<String, Object> savedResponse(StoreCommand command, int version) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("version", version);
        summary.put("text", command.text());
        summary.put("through_message_id", command.throughMessageId());
        summary.put("source_refs", command.sourceRefs());
        var response = new LinkedHashMap<String, Object>();
        response.put("status", "saved");
        response.put("session_summary", summary);
        return response;
    }

    private static Map<String, Object> versionConflict(int currentVersion) {
        var response = new LinkedHashMap<String, Object>();
        response.put("status", "conflict");
        response.put("message", "会话摘要版本已变化，请重新加载后再压缩。");
        response.put("current_version", currentVersion);
        return response;
    }

    private String requestHash(StoreCommand command) {
        var value = mapper.createObjectNode();
        value.put("user_id", command.userId());
        value.put("session_id", command.sessionId());
        value.put("request_id", command.requestId());
        value.put("operation_id", command.operationId());
        value.put("base_version", command.baseVersion());
        value.put("history_cursor", command.historyCursor());
        value.put("through_message_id", command.throughMessageId());
        value.set("source_refs", command.sourceRefs());
        value.put("text", command.text());
        return sha256(value.toString());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new DataPortException("无法序列化会话摘要结果", exception);
        }
    }

    private Map<String, Object> parse(String value) {
        try {
            return mapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            throw new DataPortException("数据库中的会话摘要操作结果无效", exception);
        }
    }

    private static void validate(StoreCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("摘要保存命令不能为空");
        }
        requireText(command.userId(), "user_id");
        requireText(command.sessionId(), "session_id");
        requireText(command.requestId(), "request_id");
        requireText(command.operationId(), "operation_id");
        requireText(command.text(), "text");
        if (command.baseVersion() < 0) {
            throw new IllegalArgumentException("base_version 不能小于 0");
        }
        if (command.sourceRefs() == null || !command.sourceRefs().isArray()) {
            throw new IllegalArgumentException("source_refs 必须是数组");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", exception);
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }
}
