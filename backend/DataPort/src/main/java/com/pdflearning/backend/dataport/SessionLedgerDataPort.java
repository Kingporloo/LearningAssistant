package com.pdflearning.backend.dataport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;

/** Session Ledger 的结构化补丁归约与版本持久化。 */
public final class SessionLedgerDataPort {
    public record StoredLedger(
            int version,
            String compactedThroughMessageId,
            ArrayNode entries) {
    }

    private static final java.util.Set<String> ENTRY_TYPES = java.util.Set.of(
            "goal", "constraint", "decision", "explained", "user_feedback",
            "active_example", "open_question", "resolved_question", "tool_state", "pointer");
    private static final java.util.Set<String> ENTRY_SCOPES = java.util.Set.of(
            "session", "thread", "task");

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public SessionLedgerDataPort(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    SessionLedgerDataPort(DataSource dataSource, ObjectMapper mapper) {
        if (dataSource == null || mapper == null) {
            throw new IllegalArgumentException("SessionLedgerDataPort 依赖不能为空");
        }
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    public Optional<StoredLedger> find(String userId, String sessionId) {
        requireText(userId, "user_id");
        requireText(sessionId, "session_id");
        try (var connection = dataSource.getConnection()) {
            return find(connection, userId, sessionId, false);
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 读取 Session Ledger 失败", exception);
        }
    }

    boolean applyEvent(
            Connection connection,
            String userId,
            String sessionId,
            String eventJson) throws SQLException {
        JsonNode event;
        try {
            event = mapper.readTree(eventJson);
        } catch (Exception exception) {
            throw new IllegalArgumentException("session_ledger_patch 不是有效 JSON", exception);
        }
        JsonNode payload = event.path("payload");
        JsonNode baseVersionNode = payload.get("base_version");
        JsonNode operations = payload.get("operations");
        if (baseVersionNode == null
                || !baseVersionNode.canConvertToInt()
                || baseVersionNode.intValue() < 0
                || operations == null
                || !operations.isArray()
                || operations.isEmpty()) {
            throw new IllegalArgumentException("session_ledger_patch 字段无效");
        }

        var current = find(connection, userId, sessionId, true).orElse(null);
        int currentVersion = current == null ? 0 : current.version();
        if (baseVersionNode.intValue() != currentVersion) {
            return false;
        }
        ArrayNode entries = current == null
                ? mapper.createArrayNode()
                : current.entries().deepCopy();
        for (var operation : operations) {
            apply(entries, operation);
        }

        String compactedThrough = current == null
                ? null
                : current.compactedThroughMessageId();
        if (payload.has("compacted_through_message_id")) {
            compactedThrough = requiredText(
                    payload.get("compacted_through_message_id"),
                    "compacted_through_message_id");
        }
        save(connection, userId, sessionId, currentVersion + 1, compactedThrough, entries, current == null);
        return true;
    }

    private void apply(ArrayNode entries, JsonNode operation) {
        if (!operation.isObject()) {
            throw new IllegalArgumentException("Ledger 操作必须是对象");
        }
        String type = requiredText(operation.get("op"), "operation.op");
        switch (type) {
            case "add" -> add(entries, validEntry(operation.get("entry"), null));
            case "update" -> update(entries, operation);
            case "resolve" -> resolve(entries, operation);
            case "supersede" -> supersede(entries, operation);
            default -> throw new IllegalArgumentException("Ledger 操作类型无效");
        }
    }

    private void add(ArrayNode entries, ObjectNode entry) {
        if (findEntry(entries, entry.path("id").asText()) != null) {
            throw new IllegalArgumentException("Ledger ADD 的条目 id 已存在");
        }
        entries.add(entry);
    }

    private void update(ArrayNode entries, JsonNode operation) {
        ObjectNode entry = requiredEntry(entries, operation);
        boolean changesContent = operation.has("content") && operation.get("content").isTextual();
        boolean changesPayload = operation.has("exact_payload")
                && operation.get("exact_payload").isObject();
        if (!changesContent && !changesPayload) {
            throw new IllegalArgumentException("Ledger UPDATE 缺少更新内容");
        }
        if (changesContent) {
            entry.put("content", requiredText(operation.get("content"), "operation.content"));
        }
        if (changesPayload) {
            entry.set("exact_payload", operation.get("exact_payload").deepCopy());
        }
        mergeSourceRefs(entry, operation.get("source_refs"));
        entry.put("updated_at", now());
    }

    private void resolve(ArrayNode entries, JsonNode operation) {
        ObjectNode entry = requiredEntry(entries, operation);
        mergeSourceRefs(entry, operation.get("source_refs"));
        entry.put("status", "resolved");
        entry.put("updated_at", now());
    }

    private void supersede(ArrayNode entries, JsonNode operation) {
        ObjectNode oldEntry = requiredEntry(entries, operation);
        String oldId = oldEntry.path("id").asText();
        ObjectNode newEntry = validEntry(operation.get("entry"), oldId);
        if (findEntry(entries, newEntry.path("id").asText()) != null) {
            throw new IllegalArgumentException("Ledger SUPERSEDE 的新条目 id 已存在");
        }
        mergeSourceRefs(oldEntry, operation.get("source_refs"));
        oldEntry.put("status", "superseded");
        oldEntry.put("updated_at", now());
        entries.add(newEntry);
    }

    private ObjectNode validEntry(JsonNode value, String supersededId) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Ledger entry 必须是对象");
        }
        var entry = (ObjectNode) value.deepCopy();
        String id = requiredText(entry.get("id"), "entry.id");
        String type = requiredText(entry.get("type"), "entry.type");
        if (!ENTRY_TYPES.contains(type)) {
            throw new IllegalArgumentException("Ledger entry.type 无效");
        }
        requiredText(entry.get("content"), "entry.content");
        String scope = requiredText(entry.get("scope"), "entry.scope");
        if (!ENTRY_SCOPES.contains(scope)) {
            throw new IllegalArgumentException("Ledger entry.scope 无效");
        }
        String status = requiredText(entry.get("status"), "entry.status");
        if (!("active".equals(status) || "resolved".equals(status) || "superseded".equals(status))) {
            throw new IllegalArgumentException("Ledger entry.status 无效");
        }
        requireTextArray(entry.get("source_refs"), "entry.source_refs", true);
        requireTextArray(entry.get("supersedes"), "entry.supersedes", false);
        requiredText(entry.get("created_at"), "entry.created_at");
        requiredText(entry.get("updated_at"), "entry.updated_at");
        if (entry.has("exact_payload")
                && !entry.get("exact_payload").isNull()
                && !entry.get("exact_payload").isObject()) {
            throw new IllegalArgumentException("entry.exact_payload 必须是对象或 null");
        }
        if (supersededId != null && !contains(entry.withArray("supersedes"), supersededId)) {
            throw new IllegalArgumentException("新条目必须引用被替代条目 " + supersededId);
        }
        entry.put("id", id);
        return entry;
    }

    private ObjectNode requiredEntry(ArrayNode entries, JsonNode operation) {
        String entryId = requiredText(operation.get("entry_id"), "operation.entry_id");
        ObjectNode entry = findEntry(entries, entryId);
        if (entry == null) {
            throw new IllegalArgumentException("Ledger 操作引用了不存在的条目");
        }
        return entry;
    }

    private static ObjectNode findEntry(ArrayNode entries, String entryId) {
        for (var entry : entries) {
            if (entry.isObject() && entryId.equals(entry.path("id").asText())) {
                return (ObjectNode) entry;
            }
        }
        return null;
    }

    private static void mergeSourceRefs(ObjectNode entry, JsonNode additional) {
        requireTextArray(additional, "operation.source_refs", true);
        ArrayNode refs = entry.withArray("source_refs");
        for (var value : additional) {
            String ref = value.textValue();
            if (!contains(refs, ref)) {
                refs.add(ref);
            }
        }
    }

    private Optional<StoredLedger> find(
            Connection connection,
            String userId,
            String sessionId,
            boolean lock) throws SQLException {
        var sql = """
                SELECT version, compacted_through_message_id, entries_json
                FROM session_ledger
                WHERE user_id = ? AND session_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, sessionId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private StoredLedger read(ResultSet result) throws SQLException {
        try {
            JsonNode entries = mapper.readTree(result.getString("entries_json"));
            if (!entries.isArray()) {
                throw new IllegalArgumentException("Session Ledger entries 不是数组");
            }
            return new StoredLedger(
                    result.getInt("version"),
                    result.getString("compacted_through_message_id"),
                    (ArrayNode) entries);
        } catch (Exception exception) {
            throw new DataPortException("数据库中的 Session Ledger JSON 无效", exception);
        }
    }

    private static void save(
            Connection connection,
            String userId,
            String sessionId,
            int version,
            String compactedThrough,
            ArrayNode entries,
            boolean insert) throws SQLException {
        var sql = insert
                ? """
                  INSERT INTO session_ledger
                      (user_id, session_id, version, compacted_through_message_id, entries_json)
                  VALUES (?, ?, ?, ?, ?)
                  """
                : """
                  UPDATE session_ledger
                  SET version = ?, compacted_through_message_id = ?, entries_json = ?,
                      updated_at = CURRENT_TIMESTAMP(6)
                  WHERE user_id = ? AND session_id = ?
                  """;
        try (var statement = connection.prepareStatement(sql)) {
            if (insert) {
                statement.setString(1, userId);
                statement.setString(2, sessionId);
                statement.setInt(3, version);
                statement.setString(4, compactedThrough);
                statement.setString(5, entries.toString());
            } else {
                statement.setInt(1, version);
                statement.setString(2, compactedThrough);
                statement.setString(3, entries.toString());
                statement.setString(4, userId);
                statement.setString(5, sessionId);
            }
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Session Ledger 版本更新失败");
            }
        }
    }

    private static void requireTextArray(JsonNode value, String name, boolean nonEmpty) {
        if (value == null || !value.isArray() || (nonEmpty && value.isEmpty())) {
            throw new IllegalArgumentException(name + " 必须是" + (nonEmpty ? "非空" : "") + "字符串数组");
        }
        for (var item : value) {
            requiredText(item, name);
        }
    }

    private static boolean contains(ArrayNode values, String expected) {
        for (var value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String requiredText(JsonNode value, String name) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.textValue().strip();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }
}
