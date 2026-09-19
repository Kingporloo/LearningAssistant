package com.learningassistant.backend.dataport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** 用户级模型、人设和模型可调用工具配置。 */
public final class AgentConfigDataPort {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    public record StoredConfig(
            String userId,
            String modelName,
            String persona,
            List<String> enabledTools,
            OffsetDateTime updatedAt) {

        public StoredConfig {
            enabledTools = List.copyOf(enabledTools);
        }
    }

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentConfigDataPort(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<StoredConfig> find(String userId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT user_id, model_name, persona, enabled_tools_json, updated_at
                        FROM user_agent_config
                        WHERE user_id = ?
                        """)) {
            statement.setString(1, userId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredConfig(
                        result.getString("user_id"),
                        result.getString("model_name"),
                        result.getString("persona"),
                        mapper.readValue(result.getString("enabled_tools_json"), STRING_LIST),
                        result.getTimestamp("updated_at").toInstant().atOffset(ZoneOffset.UTC)));
            }
        } catch (Exception exception) {
            throw new DataPortException("MySQL 查询用户 Agent 配置失败", exception);
        }
    }

    public StoredConfig save(
            String userId,
            String modelName,
            String persona,
            List<String> enabledTools,
            OffsetDateTime updatedAt) {
        String toolsJson;
        try {
            toolsJson = mapper.writeValueAsString(enabledTools);
        } catch (Exception exception) {
            throw new IllegalArgumentException("enabledTools 无法序列化", exception);
        }

        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO user_agent_config
                            (user_id, model_name, persona, enabled_tools_json,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            model_name = VALUES(model_name),
                            persona = VALUES(persona),
                            enabled_tools_json = VALUES(enabled_tools_json),
                            updated_at = VALUES(updated_at)
                        """)) {
            statement.setString(1, userId);
            statement.setString(2, modelName);
            statement.setString(3, persona);
            statement.setString(4, toolsJson);
            statement.setTimestamp(5, timestamp(updatedAt));
            statement.setTimestamp(6, timestamp(updatedAt));
            statement.executeUpdate();
            return new StoredConfig(userId, modelName, persona, enabledTools, updatedAt);
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 保存用户 Agent 配置失败", exception);
        }
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return Timestamp.from(value.toInstant());
    }
}
