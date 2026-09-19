package com.learningassistant.backend.dataport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

public final class DataPortResources implements AutoCloseable {
    public record Readiness(boolean ready, Map<String, String> components) {
    }

    private final HikariDataSource dataSource;
    private final Neo4jGraphStore graphStore;
    private final QdrantMemoryIndex qdrant;
    private final MilvusRagIndex milvus;
    private final UserDataPort users;
    private final AgentConfigDataPort agentConfigs;
    private final ChatDataPort chats;
    private final AgentRunDataPort agentRuns;
    private final SessionLedgerDataPort sessionLedgers;
    private final SessionArchiveDataPort sessionArchive;
    private final DocumentDataPort documents;
    private final ContextSummaryDataPort contextSummaries;
    private final MemoryDataPort memory;
    private final RagDataPort rag;

    private DataPortResources(
            HikariDataSource dataSource,
            Neo4jGraphStore graphStore,
            QdrantMemoryIndex qdrant,
            MilvusRagIndex milvus,
            UserDataPort users,
            AgentConfigDataPort agentConfigs,
            ChatDataPort chats,
            AgentRunDataPort agentRuns,
            SessionLedgerDataPort sessionLedgers,
            SessionArchiveDataPort sessionArchive,
            DocumentDataPort documents,
            ContextSummaryDataPort contextSummaries,
            MemoryDataPort memory,
            RagDataPort rag) {
        this.dataSource = dataSource;
        this.graphStore = graphStore;
        this.qdrant = qdrant;
        this.milvus = milvus;
        this.users = users;
        this.agentConfigs = agentConfigs;
        this.chats = chats;
        this.agentRuns = agentRuns;
        this.sessionLedgers = sessionLedgers;
        this.sessionArchive = sessionArchive;
        this.documents = documents;
        this.contextSummaries = contextSummaries;
        this.memory = memory;
        this.rag = rag;
    }

    public static DataPortResources fromEnvironment() {
        return from(System.getenv());
    }

    public static DataPortResources from(Map<String, String> environment) {
        var mysqlUrl = required(environment, "MYSQL_JDBC_URL");
        var mysqlUser = required(environment, "MYSQL_USER");
        var mysqlPassword = required(environment, "MYSQL_PASSWORD");
        var neo4jUri = required(environment, "NEO4J_URI");
        var neo4jUser = required(environment, "NEO4J_USER");
        var neo4jPassword = required(environment, "NEO4J_PASSWORD");
        var qdrantUrl = required(environment, "QDRANT_URL");
        var milvusUrl = required(environment, "MILVUS_URL");
        var timeout = Duration.ofSeconds(integer(environment, "DATA_PORT_TIMEOUT_SECONDS", 15));
        var vectorDimension = integer(environment, "EMBEDDING_VECTOR_DIMENSION", 768);
        var qdrant = new QdrantMemoryIndex(
                qdrantUrl,
                environment.getOrDefault("QDRANT_MEMORY_COLLECTION", "agent_memory_dev"),
                environment.get("QDRANT_API_KEY"),
                timeout);
        var milvus = new MilvusRagIndex(
                milvusUrl,
                environment.getOrDefault("MILVUS_RAG_COLLECTION", "rag_chunks_dev"),
                environment.getOrDefault("MILVUS_DATABASE", "default"),
                environment.get("MILVUS_TOKEN"),
                timeout);

        var hikari = new HikariConfig();
        hikari.setJdbcUrl(mysqlUrl);
        hikari.setUsername(mysqlUser);
        hikari.setPassword(mysqlPassword);
        hikari.setMaximumPoolSize(integer(environment, "MYSQL_POOL_SIZE", 10));
        hikari.setMinimumIdle(0);
        hikari.setPoolName("learning-assistant-data-port");
        var dataSource = new HikariDataSource(hikari);

        try {
            var neo4jDriver = GraphDatabase.driver(
                    neo4jUri,
                    AuthTokens.basic(neo4jUser, neo4jPassword));
            var graph = new Neo4jGraphStore(
                    neo4jDriver, environment.getOrDefault("NEO4J_DATABASE", "neo4j"));
            var mysql = new MySqlDataStore(dataSource);
            var ledgers = new SessionLedgerDataPort(dataSource);
            return new DataPortResources(
                    dataSource,
                    graph,
                    qdrant,
                    milvus,
                    new UserDataPort(dataSource),
                    new AgentConfigDataPort(dataSource),
                    new ChatDataPort(dataSource),
                    new AgentRunDataPort(dataSource, ledgers),
                    ledgers,
                    new SessionArchiveDataPort(dataSource),
                    new DocumentDataPort(dataSource),
                    new ContextSummaryDataPort(dataSource),
                    new MemoryDataPort(mysql, qdrant, graph, vectorDimension),
                    new RagDataPort(mysql, milvus, graph, vectorDimension));
        } catch (RuntimeException exception) {
            dataSource.close();
            throw exception;
        }
    }

    public MemoryDataPort memory() {
        return memory;
    }

    public UserDataPort users() {
        return users;
    }

    public AgentConfigDataPort agentConfigs() {
        return agentConfigs;
    }

    public ChatDataPort chats() {
        return chats;
    }

    public AgentRunDataPort agentRuns() {
        return agentRuns;
    }

    public SessionLedgerDataPort sessionLedgers() {
        return sessionLedgers;
    }

    public SessionArchiveDataPort sessionArchive() {
        return sessionArchive;
    }

    public DocumentDataPort documents() {
        return documents;
    }

    public ContextSummaryDataPort contextSummaries() {
        return contextSummaries;
    }

    public RagDataPort rag() {
        return rag;
    }

    public Readiness readiness() {
        var components = new LinkedHashMap<String, String>();
        check(components, "mysql", this::checkMySql);
        check(components, "milvus", milvus::checkReady);
        check(components, "neo4j", graphStore::checkReady);
        check(components, "qdrant", qdrant::checkReady);
        return new Readiness(
                components.values().stream().allMatch("up"::equals),
                Map.copyOf(components));
    }

    @Override
    public void close() {
        try {
            graphStore.close();
        } finally {
            dataSource.close();
        }
    }

    private void checkMySql() {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT 1");
                var result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new DataPortException("MySQL 健康检查返回异常");
            }
        } catch (SQLException exception) {
            throw new DataPortException("MySQL 健康检查失败", exception);
        }
    }

    private static void check(
            Map<String, String> components,
            String name,
            Runnable operation) {
        try {
            operation.run();
            components.put(name, "up");
        } catch (RuntimeException exception) {
            components.put(name, "down");
        }
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
