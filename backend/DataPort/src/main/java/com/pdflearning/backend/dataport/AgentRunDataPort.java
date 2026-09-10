package com.pdflearning.backend.dataport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** Agent 会话占用、运行幂等和原始事件的 MySQL 数据端口。 */
public final class AgentRunDataPort {
    public enum ClaimDecision {
        STARTED,
        IN_PROGRESS,
        REPLAY,
        TERMINAL,
        SESSION_BUSY
    }

    public enum AppendResult {
        INSERTED,
        DUPLICATE
    }

    public record RunClaim(
            ClaimDecision decision,
            AgentRunRecord run,
            String activeRequestId) {
    }

    private final DataSource dataSource;

    public AgentRunDataPort(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为空");
        }
        this.dataSource = dataSource;
    }

    public RunClaim beginRun(
            String userId,
            String sessionId,
            String requestId,
            String messageId,
            String requestHash) {
        requireText(userId, "user_id");
        requireText(sessionId, "session_id");
        requireText(requestId, "request_id");
        requireText(messageId, "message_id");
        requireHash(requestHash, "request_hash");

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureSession(connection, userId, sessionId);
                String activeRequestId = lockSession(connection, userId, sessionId);
                var previous = findRun(connection, userId, requestId, true);
                if (previous.isPresent()) {
                    var run = previous.get();
                    verifyRequest(run, sessionId, messageId, requestHash);
                    if (run.status() == AgentRunStatus.RUNNING) {
                        if (activeRequestId != null && !activeRequestId.equals(requestId)) {
                            throw new DataPortException("同一会话存在两个活动 Agent 运行");
                        }
                        if (activeRequestId == null) {
                            setActiveRequest(connection, userId, sessionId, requestId);
                            activeRequestId = requestId;
                        }
                    }
                    connection.commit();
                    return claim(run, activeRequestId);
                }

                if (activeRequestId != null) {
                    var active = findRun(connection, userId, activeRequestId, true)
                            .orElseThrow(() -> new DataPortException(
                                    "Agent 会话引用了不存在的活动运行"));
                    if (active.status() == AgentRunStatus.RUNNING) {
                        connection.commit();
                        return new RunClaim(
                                ClaimDecision.SESSION_BUSY,
                                active,
                                activeRequestId);
                    }
                    setActiveRequest(connection, userId, sessionId, null);
                }

                insertRun(
                        connection,
                        userId,
                        sessionId,
                        requestId,
                        messageId,
                        requestHash);
                setActiveRequest(connection, userId, sessionId, requestId);
                var run = findRun(connection, userId, requestId, false).orElseThrow();
                connection.commit();
                return new RunClaim(ClaimDecision.STARTED, run, requestId);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 登记 Agent 运行失败", exception);
        }
    }

    public AppendResult appendEvent(
            String userId,
            String sessionId,
            String requestId,
            long eventSeq,
            String eventType,
            String eventHash,
            String eventJson,
            AgentRunStatus terminalStatus) {
        requireText(userId, "user_id");
        requireText(sessionId, "session_id");
        requireText(requestId, "request_id");
        requireText(eventType, "event_type");
        requireHash(eventHash, "event_hash");
        requireText(eventJson, "event_json");
        if (eventSeq <= 0) {
            throw new IllegalArgumentException("event_seq 必须是正整数");
        }
        validateTerminal(eventType, terminalStatus);

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockSession(connection, userId, sessionId);
                var run = findRun(connection, userId, requestId, true)
                        .orElseThrow(() -> new IllegalStateException("Agent 运行不存在"));
                if (!run.sessionId().equals(sessionId)) {
                    throw new IllegalStateException("Agent 事件不属于当前会话");
                }
                if (eventSeq <= run.lastEventSeq()) {
                    String previousHash = findEventHash(
                            connection, userId, requestId, eventSeq)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Agent 运行事件序号与事件记录不一致"));
                    if (!previousHash.equals(eventHash)) {
                        throw new IllegalStateException("相同 event_seq 对应了不同事件内容");
                    }
                    connection.commit();
                    return AppendResult.DUPLICATE;
                }
                if (run.status() != AgentRunStatus.RUNNING) {
                    throw new IllegalStateException("已结束的 Agent 运行不能追加新事件");
                }
                if (eventSeq != run.lastEventSeq() + 1) {
                    throw new IllegalStateException(
                            "Agent 事件序号不连续，期望 "
                                    + (run.lastEventSeq() + 1) + "，实际 " + eventSeq);
                }

                insertEvent(
                        connection,
                        userId,
                        sessionId,
                        requestId,
                        eventSeq,
                        eventType,
                        eventHash,
                        eventJson);
                updateRunAfterEvent(
                        connection, userId, requestId, eventSeq, terminalStatus);
                if (terminalStatus != null) {
                    clearActiveRequest(
                            connection, userId, sessionId, requestId);
                }
                connection.commit();
                return AppendResult.INSERTED;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 保存 Agent 事件失败", exception);
        }
    }

    public AgentRunRecord markInterrupted(
            String userId,
            String requestId,
            String reason) {
        requireText(userId, "user_id");
        requireText(requestId, "request_id");
        requireText(reason, "reason");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var unlocked = findRun(connection, userId, requestId, false)
                        .orElseThrow(() -> new IllegalStateException("Agent 运行不存在"));
                lockSession(connection, userId, unlocked.sessionId());
                var run = findRun(connection, userId, requestId, true)
                        .orElseThrow(() -> new IllegalStateException("Agent 运行不存在"));
                if (run.status() == AgentRunStatus.RUNNING) {
                    try (var statement = connection.prepareStatement("""
                            UPDATE agent_run
                            SET status = 'interrupted', interruption_reason = ?,
                                finished_at = CURRENT_TIMESTAMP(6)
                            WHERE user_id = ? AND request_id = ? AND status = 'running'
                            """)) {
                        statement.setString(1, reason);
                        statement.setString(2, userId);
                        statement.setString(3, requestId);
                        statement.executeUpdate();
                    }
                    clearActiveRequest(
                            connection, userId, run.sessionId(), requestId);
                }
                var updated = findRun(connection, userId, requestId, false).orElseThrow();
                connection.commit();
                return updated;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 标记 Agent 运行中断失败", exception);
        }
    }

    public int recoverInterruptedRuns() {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int changed;
                try (var statement = connection.prepareStatement("""
                        UPDATE agent_run
                        SET status = 'interrupted',
                            interruption_reason = 'Java 服务重启，运行状态无法确认。',
                            finished_at = CURRENT_TIMESTAMP(6)
                        WHERE status = 'running'
                        """)) {
                    changed = statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("""
                        UPDATE agent_session SET active_request_id = NULL
                        WHERE active_request_id IS NOT NULL
                        """)) {
                    statement.executeUpdate();
                }
                connection.commit();
                return changed;
            } catch (SQLException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 恢复遗留 Agent 运行失败", exception);
        }
    }

    public Optional<AgentRunRecord> findRun(String userId, String requestId) {
        requireText(userId, "user_id");
        requireText(requestId, "request_id");
        try (var connection = dataSource.getConnection()) {
            return findRun(connection, userId, requestId, false);
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 查询 Agent 运行失败", exception);
        }
    }

    public List<StoredAgentEvent> readEvents(String userId, String requestId) {
        requireText(userId, "user_id");
        requireText(requestId, "request_id");
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT event_seq, event_type, event_json, created_at
                        FROM agent_run_event
                        WHERE user_id = ? AND request_id = ?
                        ORDER BY event_seq
                        """)) {
            statement.setString(1, userId);
            statement.setString(2, requestId);
            try (var results = statement.executeQuery()) {
                var events = new ArrayList<StoredAgentEvent>();
                while (results.next()) {
                    events.add(new StoredAgentEvent(
                            results.getLong("event_seq"),
                            results.getString("event_type"),
                            results.getString("event_json"),
                            offsetDateTime(results.getTimestamp("created_at"))));
                }
                return List.copyOf(events);
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 读取 Agent 事件失败", exception);
        }
    }

    private static RunClaim claim(
            AgentRunRecord run,
            String activeRequestId) {
        var decision = switch (run.status()) {
            case RUNNING -> ClaimDecision.IN_PROGRESS;
            case COMPLETED, FAILED -> ClaimDecision.REPLAY;
            case INTERRUPTED -> ClaimDecision.TERMINAL;
        };
        return new RunClaim(decision, run, activeRequestId);
    }

    private static void ensureSession(
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

    private static String lockSession(
            Connection connection,
            String userId,
            String sessionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT active_request_id FROM agent_session
                WHERE user_id = ? AND session_id = ? FOR UPDATE
                """)) {
            statement.setString(1, userId);
            statement.setString(2, sessionId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("无法锁定 Agent 会话");
                }
                return result.getString("active_request_id");
            }
        }
    }

    private static void setActiveRequest(
            Connection connection,
            String userId,
            String sessionId,
            String requestId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE agent_session
                SET active_request_id = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE user_id = ? AND session_id = ?
                """)) {
            statement.setString(1, requestId);
            statement.setString(2, userId);
            statement.setString(3, sessionId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Agent 会话不存在");
            }
        }
    }

    private static void clearActiveRequest(
            Connection connection,
            String userId,
            String sessionId,
            String requestId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE agent_session
                SET active_request_id = NULL, updated_at = CURRENT_TIMESTAMP(6)
                WHERE user_id = ? AND session_id = ? AND active_request_id = ?
                """)) {
            statement.setString(1, userId);
            statement.setString(2, sessionId);
            statement.setString(3, requestId);
            statement.executeUpdate();
        }
    }

    private static void insertRun(
            Connection connection,
            String userId,
            String sessionId,
            String requestId,
            String messageId,
            String requestHash) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO agent_run
                    (user_id, request_id, session_id, message_id, request_hash, status)
                VALUES (?, ?, ?, ?, ?, 'running')
                """)) {
            statement.setString(1, userId);
            statement.setString(2, requestId);
            statement.setString(3, sessionId);
            statement.setString(4, messageId);
            statement.setString(5, requestHash);
            statement.executeUpdate();
        }
    }

    private static void insertEvent(
            Connection connection,
            String userId,
            String sessionId,
            String requestId,
            long eventSeq,
            String eventType,
            String eventHash,
            String eventJson) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO agent_run_event
                    (user_id, request_id, event_seq, session_id,
                     event_type, event_hash, event_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, userId);
            statement.setString(2, requestId);
            statement.setLong(3, eventSeq);
            statement.setString(4, sessionId);
            statement.setString(5, eventType);
            statement.setString(6, eventHash);
            statement.setString(7, eventJson);
            statement.executeUpdate();
        }
    }

    private static void updateRunAfterEvent(
            Connection connection,
            String userId,
            String requestId,
            long eventSeq,
            AgentRunStatus terminalStatus) throws SQLException {
        var sql = terminalStatus == null
                ? """
                  UPDATE agent_run SET last_event_seq = ?
                  WHERE user_id = ? AND request_id = ? AND status = 'running'
                  """
                : """
                  UPDATE agent_run
                  SET last_event_seq = ?, status = ?, finished_at = CURRENT_TIMESTAMP(6)
                  WHERE user_id = ? AND request_id = ? AND status = 'running'
                  """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, eventSeq);
            int offset = 2;
            if (terminalStatus != null) {
                statement.setString(offset++, terminalStatus.databaseValue());
            }
            statement.setString(offset++, userId);
            statement.setString(offset, requestId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Agent 运行状态更新失败");
            }
        }
    }

    private static Optional<String> findEventHash(
            Connection connection,
            String userId,
            String requestId,
            long eventSeq) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT event_hash FROM agent_run_event
                WHERE user_id = ? AND request_id = ? AND event_seq = ?
                """)) {
            statement.setString(1, userId);
            statement.setString(2, requestId);
            statement.setLong(3, eventSeq);
            try (var result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(result.getString("event_hash"))
                        : Optional.empty();
            }
        }
    }

    private static Optional<AgentRunRecord> findRun(
            Connection connection,
            String userId,
            String requestId,
            boolean lock) throws SQLException {
        var sql = """
                SELECT user_id, session_id, request_id, message_id, request_hash,
                       status, last_event_seq, interruption_reason, created_at, finished_at
                FROM agent_run WHERE user_id = ? AND request_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, requestId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(runRecord(result)) : Optional.empty();
            }
        }
    }

    private static AgentRunRecord runRecord(ResultSet result) throws SQLException {
        return new AgentRunRecord(
                result.getString("user_id"),
                result.getString("session_id"),
                result.getString("request_id"),
                result.getString("message_id"),
                result.getString("request_hash"),
                AgentRunStatus.fromDatabase(result.getString("status")),
                result.getLong("last_event_seq"),
                result.getString("interruption_reason"),
                offsetDateTime(result.getTimestamp("created_at")),
                offsetDateTime(result.getTimestamp("finished_at")));
    }

    private static void verifyRequest(
            AgentRunRecord run,
            String sessionId,
            String messageId,
            String requestHash) {
        if (!run.sessionId().equals(sessionId)
                || !run.messageId().equals(messageId)
                || !run.requestHash().equals(requestHash)) {
            throw new IllegalStateException("request_id 已被不同的 Agent 请求使用");
        }
    }

    private static void validateTerminal(
            String eventType,
            AgentRunStatus terminalStatus) {
        if ("run_finished".equals(eventType)) {
            if (terminalStatus != AgentRunStatus.COMPLETED
                    && terminalStatus != AgentRunStatus.FAILED) {
                throw new IllegalArgumentException("run_finished 必须提供 completed 或 failed");
            }
        } else if (terminalStatus != null) {
            throw new IllegalArgumentException("只有 run_finished 可以结束 Agent 运行");
        }
    }

    private static void requireHash(String value, String name) {
        requireText(value, name);
        if (value.length() != 64 || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " 必须是小写 SHA-256");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
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
