package com.pdflearning.backend.dataport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import javax.sql.DataSource;

public final class MySqlDataStore {
    public record OperationClaim(boolean claimed, String responseJson) {
    }

    public record RagState(int total, int ready, int building, int failed) {
    }

    private static final String PENDING_RESPONSE =
            "{\"status\":\"unknown\",\"message\":\"操作正在执行或上次结果尚未确认。\"}";

    private final DataSource dataSource;

    public MySqlDataStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public OperationClaim beginMemoryStore(
            String userId,
            String requestId,
            String operationId,
            String memoryId,
            String memoryType,
            String content,
            double importance,
            String sourceSessionId,
            String sourceMessageId,
            OffsetDateTime eventTime,
            String graphJson,
            boolean correction) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var previous = findOperation(connection, userId, requestId, operationId, true);
                if (previous.isPresent()) {
                    connection.commit();
                    return new OperationClaim(false, previous.get());
                }
                insertOperation(connection, userId, requestId, operationId, "memory_store");
                int changed = correction
                        ? updateMemory(
                                connection,
                                userId,
                                memoryId,
                                memoryType,
                                content,
                                importance,
                                sourceSessionId,
                                sourceMessageId,
                                graphJson)
                        : insertMemory(
                                connection,
                                userId,
                                memoryId,
                                memoryType,
                                content,
                                importance,
                                sourceSessionId,
                                sourceMessageId,
                                eventTime,
                                graphJson);
                if (changed != 1) {
                    var notFound = "{\"status\":\"not_found\",\"memory_id\":\""
                            + memoryId + "\",\"message\":\"未找到当前用户可纠正的长期记忆。\"}";
                    updateOperation(connection, userId, requestId, operationId, notFound);
                    connection.commit();
                    return new OperationClaim(false, notFound);
                }
                connection.commit();
                return new OperationClaim(true, null);
            } catch (SQLException exception) {
                rollback(connection);
                if ("23000".equals(exception.getSQLState())) {
                    var previous = findOperation(userId, requestId, operationId);
                    if (previous.isPresent()) {
                        return new OperationClaim(false, previous.get());
                    }
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 写入长期记忆失败", exception);
        }
    }

    public OperationClaim beginMemoryForget(
            String userId,
            String requestId,
            String operationId,
            String memoryId,
            String memoryType) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var previous = findOperation(connection, userId, requestId, operationId, true);
                if (previous.isPresent()) {
                    connection.commit();
                    return new OperationClaim(false, previous.get());
                }
                insertOperation(connection, userId, requestId, operationId, "memory_forget");
                try (var statement = connection.prepareStatement("""
                        UPDATE long_term_memory
                        SET status = 'deleted', index_status = 'pending_delete', updated_at = CURRENT_TIMESTAMP(6)
                        WHERE user_id = ? AND memory_id = ? AND memory_type = ? AND status = 'active'
                        """)) {
                    statement.setString(1, userId);
                    statement.setString(2, memoryId);
                    statement.setString(3, memoryType);
                    if (statement.executeUpdate() != 1) {
                        var notFound = "{\"status\":\"not_found\",\"memory_id\":\""
                                + memoryId + "\",\"message\":\"未找到当前用户可删除的长期记忆。\"}";
                        updateOperation(connection, userId, requestId, operationId, notFound);
                        connection.commit();
                        return new OperationClaim(false, notFound);
                    }
                }
                connection.commit();
                return new OperationClaim(true, null);
            } catch (SQLException exception) {
                rollback(connection);
                if ("23000".equals(exception.getSQLState())) {
                    var previous = findOperation(userId, requestId, operationId);
                    if (previous.isPresent()) {
                        return new OperationClaim(false, previous.get());
                    }
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 删除长期记忆失败", exception);
        }
    }

    public void completeOperation(
            String userId,
            String requestId,
            String operationId,
            String responseJson,
            String indexStatus) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                updateOperation(connection, userId, requestId, operationId, responseJson);
                try (var statement = connection.prepareStatement("""
                        UPDATE long_term_memory
                        SET index_status = ?, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE user_id = ?
                          AND memory_id = JSON_UNQUOTE(JSON_EXTRACT(CAST(? AS JSON), '$.memory_id'))
                        """)) {
                    statement.setString(1, indexStatus);
                    statement.setString(2, userId);
                    statement.setString(3, responseJson);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 保存记忆操作结果失败", exception);
        }
    }

    public List<MemoryRecord> readActiveMemories(
            String userId,
            String memoryType,
            List<String> memoryIds) {
        if (memoryIds.isEmpty()) {
            return List.of();
        }
        var placeholders = new StringJoiner(",");
        memoryIds.forEach(ignored -> placeholders.add("?"));
        var typeClause = "all".equals(memoryType) ? "" : " AND memory_type = ?";
        var sql = """
                SELECT memory_id, memory_type, content, importance, status,
                       source_session_id, source_message_id, created_at, event_time
                FROM long_term_memory
                WHERE user_id = ? AND status = 'active' AND memory_id IN (%s)%s
                """.formatted(placeholders, typeClause);
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            int position = 1;
            statement.setString(position++, userId);
            for (var memoryId : memoryIds) {
                statement.setString(position++, memoryId);
            }
            if (!"all".equals(memoryType)) {
                statement.setString(position, memoryType);
            }
            try (var results = statement.executeQuery()) {
                var memories = new ArrayList<MemoryRecord>();
                while (results.next()) {
                    memories.add(memoryRecord(results));
                }
                return List.copyOf(memories);
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 回查长期记忆正文失败", exception);
        }
    }

    public RagState ragState(String userId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT COUNT(*) AS total,
                               SUM(status = 'ready') AS ready_count,
                               SUM(status = 'building') AS building_count,
                               SUM(status = 'failed') AS failed_count
                        FROM rag_document
                        WHERE user_id = ?
                        """)) {
            statement.setString(1, userId);
            try (var result = statement.executeQuery()) {
                result.next();
                return new RagState(
                        result.getInt("total"),
                        result.getInt("ready_count"),
                        result.getInt("building_count"),
                        result.getInt("failed_count"));
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 读取 RAG 状态失败", exception);
        }
    }

    public List<String> readyDocumentIds(String userId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT document_id
                        FROM rag_document
                        WHERE user_id = ? AND status = 'ready'
                        ORDER BY document_id
                        """)) {
            statement.setString(1, userId);
            try (var results = statement.executeQuery()) {
                var ids = new ArrayList<String>();
                while (results.next()) {
                    ids.add(results.getString("document_id"));
                }
                return List.copyOf(ids);
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 读取可用 RAG 文档失败", exception);
        }
    }

    public void setRagDocumentStatus(
            String userId,
            String documentId,
            String requestId,
            String status,
            String message) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO rag_document
                            (user_id, document_id, build_request_id, status, status_message)
                        VALUES (?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            build_request_id = VALUES(build_request_id),
                            status = VALUES(status),
                            status_message = VALUES(status_message),
                            updated_at = CURRENT_TIMESTAMP(6)
                        """)) {
            statement.setString(1, userId);
            statement.setString(2, documentId);
            statement.setString(3, requestId);
            statement.setString(4, status);
            statement.setString(5, message);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 更新 RAG 文档状态失败", exception);
        }
    }

    private int insertMemory(
            Connection connection,
            String userId,
            String memoryId,
            String memoryType,
            String content,
            double importance,
            String sourceSessionId,
            String sourceMessageId,
            OffsetDateTime eventTime,
            String graphJson) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO long_term_memory
                    (memory_id, user_id, memory_type, content, importance, status, index_status,
                     source_session_id, source_message_id, event_time, graph_json)
                VALUES (?, ?, ?, ?, ?, 'active', 'pending', ?, ?, ?, CAST(? AS JSON))
                """)) {
            statement.setString(1, memoryId);
            statement.setString(2, userId);
            statement.setString(3, memoryType);
            statement.setString(4, content);
            statement.setDouble(5, importance);
            statement.setString(6, sourceSessionId);
            statement.setString(7, sourceMessageId);
            statement.setTimestamp(8, timestamp(eventTime));
            statement.setString(9, graphJson);
            return statement.executeUpdate();
        }
    }

    private int updateMemory(
            Connection connection,
            String userId,
            String memoryId,
            String memoryType,
            String content,
            double importance,
            String sourceSessionId,
            String sourceMessageId,
            String graphJson) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE long_term_memory
                SET content = ?, importance = ?, source_session_id = ?, source_message_id = ?,
                    graph_json = CAST(? AS JSON), status = 'active', index_status = 'pending',
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE user_id = ? AND memory_id = ? AND memory_type = ? AND status = 'active'
                """)) {
            statement.setString(1, content);
            statement.setDouble(2, importance);
            statement.setString(3, sourceSessionId);
            statement.setString(4, sourceMessageId);
            statement.setString(5, graphJson);
            statement.setString(6, userId);
            statement.setString(7, memoryId);
            statement.setString(8, memoryType);
            return statement.executeUpdate();
        }
    }

    private void insertOperation(
            Connection connection,
            String userId,
            String requestId,
            String operationId,
            String operationType) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO storage_operation
                    (user_id, request_id, operation_id, operation_type, response_json)
                VALUES (?, ?, ?, ?, CAST(? AS JSON))
                """)) {
            statement.setString(1, userId);
            statement.setString(2, requestId);
            statement.setString(3, operationId);
            statement.setString(4, operationType);
            statement.setString(5, PENDING_RESPONSE);
            statement.executeUpdate();
        }
    }

    private void updateOperation(
            Connection connection,
            String userId,
            String requestId,
            String operationId,
            String responseJson) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE storage_operation
                SET response_json = CAST(? AS JSON), updated_at = CURRENT_TIMESTAMP(6)
                WHERE user_id = ? AND request_id = ? AND operation_id = ?
                """)) {
            statement.setString(1, responseJson);
            statement.setString(2, userId);
            statement.setString(3, requestId);
            statement.setString(4, operationId);
            statement.executeUpdate();
        }
    }

    private Optional<String> findOperation(String userId, String requestId, String operationId) {
        try (var connection = dataSource.getConnection()) {
            return findOperation(connection, userId, requestId, operationId, false);
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 查询幂等操作失败", exception);
        }
    }

    private Optional<String> findOperation(
            Connection connection,
            String userId,
            String requestId,
            String operationId,
            boolean lock) throws SQLException {
        var sql = """
                SELECT response_json
                FROM storage_operation
                WHERE user_id = ? AND request_id = ? AND operation_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, requestId);
            statement.setString(3, operationId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("response_json")) : Optional.empty();
            }
        }
    }

    private static MemoryRecord memoryRecord(ResultSet result) throws SQLException {
        return new MemoryRecord(
                result.getString("memory_id"),
                result.getString("memory_type"),
                result.getString("content"),
                result.getDouble("importance"),
                result.getString("status"),
                result.getString("source_session_id"),
                result.getString("source_message_id"),
                offsetDateTime(result.getTimestamp("created_at")),
                offsetDateTime(result.getTimestamp("event_time")));
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static OffsetDateTime offsetDateTime(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }
}
