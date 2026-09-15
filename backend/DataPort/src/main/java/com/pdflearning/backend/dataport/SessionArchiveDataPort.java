package com.pdflearning.backend.dataport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;

/** 从权威聊天记录搜索并回读当前会话的历史原文。 */
public final class SessionArchiveDataPort {
    public record Ref(String messageId, String spanId) {
    }

    public record SearchHit(
            String messageId,
            String spanId,
            String role,
            String preview,
            double score,
            OffsetDateTime createdAt) {
    }

    public record ArchiveText(
            String messageId,
            String spanId,
            String role,
            String content,
            OffsetDateTime createdAt) {
    }

    private record Message(
            String messageId,
            String role,
            String content,
            OffsetDateTime createdAt) {
    }

    private record Span(String id, String text) {
    }

    private final DataSource dataSource;

    public SessionArchiveDataPort(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为空");
        }
        this.dataSource = dataSource;
    }

    public List<SearchHit> search(
            String userId,
            String sessionId,
            String historyCursor,
            String query,
            int topK) {
        validate(userId, sessionId, historyCursor);
        requireText(query, "query");
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("top_k 必须在 1 到 20 之间");
        }
        var terms = terms(query);
        var hits = new ArrayList<SearchHit>();
        for (var message : messages(userId, sessionId, historyCursor)) {
            for (var span : spans(message.content())) {
                double score = score(span.text(), query, terms);
                if (score > 0) {
                    hits.add(new SearchHit(
                            message.messageId(), span.id(), message.role(),
                            preview(span.text()), score, message.createdAt()));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed()
                .thenComparing(SearchHit::createdAt, Comparator.reverseOrder())
                .thenComparing(SearchHit::messageId)
                .thenComparing(SearchHit::spanId));
        return List.copyOf(hits.subList(0, Math.min(topK, hits.size())));
    }

    public List<ArchiveText> read(
            String userId,
            String sessionId,
            String historyCursor,
            List<Ref> refs) {
        validate(userId, sessionId, historyCursor);
        if (refs == null || refs.isEmpty() || refs.size() > 20) {
            throw new IllegalArgumentException("refs 必须包含 1 到 20 个引用");
        }
        var byId = new java.util.LinkedHashMap<String, Message>();
        for (var message : messages(userId, sessionId, historyCursor)) {
            byId.put(message.messageId(), message);
        }
        var result = new ArrayList<ArchiveText>();
        for (var ref : refs) {
            requireText(ref.messageId(), "refs.message_id");
            requireText(ref.spanId(), "refs.span_id");
            var message = byId.get(ref.messageId());
            if (message == null) {
                continue;
            }
            for (var span : spans(message.content())) {
                if (span.id().equals(ref.spanId())) {
                    result.add(new ArchiveText(
                            message.messageId(), span.id(), message.role(),
                            span.text(), message.createdAt()));
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    private List<Message> messages(String userId, String sessionId, String historyCursor) {
        try (var connection = dataSource.getConnection()) {
            Timestamp upper = cursorTime(connection, userId, sessionId, historyCursor);
            try (var statement = connection.prepareStatement("""
                    SELECT message_id, role, content, created_at
                    FROM chat_message
                    WHERE user_id = ? AND session_id = ? AND status = 'completed'
                      AND created_at <= ?
                    ORDER BY created_at, message_id
                    """)) {
                statement.setString(1, userId);
                statement.setString(2, sessionId);
                statement.setTimestamp(3, upper);
                try (var result = statement.executeQuery()) {
                    var messages = new ArrayList<Message>();
                    while (result.next()) {
                        messages.add(new Message(
                                result.getString("message_id"),
                                result.getString("role"),
                                result.getString("content"),
                                time(result.getTimestamp("created_at"))));
                    }
                    return messages;
                }
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 读取 Session Archive 失败", exception);
        }
    }

    private static Timestamp cursorTime(
            Connection connection,
            String userId,
            String sessionId,
            String historyCursor) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT created_at
                FROM chat_message
                WHERE user_id = ? AND session_id = ? AND message_id = ? AND status = 'completed'
                """)) {
            statement.setString(1, userId);
            statement.setString(2, sessionId);
            statement.setString(3, historyCursor);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("history_cursor 不属于当前会话快照");
                }
                return result.getTimestamp("created_at");
            }
        }
    }

    private static List<Span> spans(String content) {
        String[] parts = content.split("(?:\\R\\s*){2,}");
        var spans = new ArrayList<Span>();
        for (String part : parts) {
            String text = part.strip();
            if (!text.isEmpty()) {
                spans.add(new Span("p" + (spans.size() + 1), text));
            }
        }
        if (spans.isEmpty() && !content.isBlank()) {
            spans.add(new Span("p1", content.strip()));
        }
        return spans;
    }

    private static LinkedHashSet<String> terms(String query) {
        var result = new LinkedHashSet<String>();
        String normalized = query.toLowerCase(Locale.ROOT).strip();
        for (String value : normalized.split("[\\p{P}\\p{Z}\\s]+")) {
            if (value.isBlank()) {
                continue;
            }
            result.add(value);
            int[] points = value.codePoints().toArray();
            if (points.length >= 4) {
                for (int i = 0; i < points.length - 1; i++) {
                    result.add(new String(points, i, 2));
                }
            }
        }
        return result;
    }

    private static double score(String text, String query, LinkedHashSet<String> terms) {
        String normalized = text.toLowerCase(Locale.ROOT);
        int matches = 0;
        for (String term : terms) {
            if (normalized.contains(term)) {
                matches++;
            }
        }
        if (matches == 0) {
            return 0;
        }
        double score = (double) matches / Math.max(1, terms.size());
        if (normalized.contains(query.toLowerCase(Locale.ROOT).strip())) {
            score += 1;
        }
        return score;
    }

    private static String preview(String text) {
        return text.length() <= 240 ? text : text.substring(0, 240);
    }

    private static void validate(String userId, String sessionId, String historyCursor) {
        requireText(userId, "user_id");
        requireText(sessionId, "session_id");
        requireText(historyCursor, "history_cursor");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private static OffsetDateTime time(Timestamp value) {
        return value.toInstant().atOffset(ZoneOffset.UTC);
    }
}
