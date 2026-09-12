package com.pdflearning.backend.dataport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;

/** Java 后端共享的 MySQL 连接池及数据端口。 */
public final class MySqlDataPortResources implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final UserDataPort users;
    private final ChatDataPort chats;
    private final AgentRunDataPort agentRuns;
    private final ContextSummaryDataPort contextSummaries;

    private MySqlDataPortResources(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.users = new UserDataPort(dataSource);
        this.chats = new ChatDataPort(dataSource);
        this.agentRuns = new AgentRunDataPort(dataSource);
        this.contextSummaries = new ContextSummaryDataPort(dataSource);
    }

    public static MySqlDataPortResources fromEnvironment(Map<String, String> environment) {
        var hikari = new HikariConfig();
        hikari.setJdbcUrl(required(environment, "MYSQL_JDBC_URL"));
        hikari.setUsername(required(environment, "MYSQL_USER"));
        hikari.setPassword(required(environment, "MYSQL_PASSWORD"));
        hikari.setMaximumPoolSize(integer(environment, "MYSQL_POOL_SIZE", 5));
        hikari.setMinimumIdle(0);
        hikari.setPoolName("pdf-learning-mysql-data-port");
        return new MySqlDataPortResources(new HikariDataSource(hikari));
    }

    public UserDataPort users() {
        return users;
    }

    public ChatDataPort chats() {
        return chats;
    }

    public AgentRunDataPort agentRuns() {
        return agentRuns;
    }

    public ContextSummaryDataPort contextSummaries() {
        return contextSummaries;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static String required(Map<String, String> environment, String name) {
        var value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value;
    }

    private static int integer(Map<String, String> environment, String name, int defaultValue) {
        var value = environment.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            var parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " 必须是正整数", exception);
        }
    }
}
