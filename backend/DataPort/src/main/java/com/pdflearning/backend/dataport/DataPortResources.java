package com.pdflearning.backend.dataport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.Map;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

public final class DataPortResources implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final Neo4jGraphStore graphStore;
    private final MemoryDataPort memory;
    private final RagDataPort rag;

    private DataPortResources(
            HikariDataSource dataSource,
            Neo4jGraphStore graphStore,
            MemoryDataPort memory,
            RagDataPort rag) {
        this.dataSource = dataSource;
        this.graphStore = graphStore;
        this.memory = memory;
        this.rag = rag;
    }

    public static DataPortResources fromEnvironment() {
        return from(System.getenv());
    }

    static DataPortResources from(Map<String, String> environment) {
        var mysqlUrl = required(environment, "MYSQL_JDBC_URL");
        var mysqlUser = required(environment, "MYSQL_USER");
        var mysqlPassword = required(environment, "MYSQL_PASSWORD");
        var neo4jUri = required(environment, "NEO4J_URI");
        var neo4jUser = required(environment, "NEO4J_USER");
        var neo4jPassword = required(environment, "NEO4J_PASSWORD");
        var qdrantUrl = required(environment, "QDRANT_URL");
        var milvusUrl = required(environment, "MILVUS_URL");
        var timeout = Duration.ofSeconds(integer(environment, "DATA_PORT_TIMEOUT_SECONDS", 15));
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
        hikari.setPoolName("pdf-learning-data-port");
        var dataSource = new HikariDataSource(hikari);

        try {
            var neo4jDriver = GraphDatabase.driver(
                    neo4jUri,
                    AuthTokens.basic(neo4jUser, neo4jPassword));
            var graph = new Neo4jGraphStore(
                    neo4jDriver, environment.getOrDefault("NEO4J_DATABASE", "neo4j"));
            var mysql = new MySqlDataStore(dataSource);
            return new DataPortResources(
                    dataSource,
                    graph,
                    new MemoryDataPort(mysql, qdrant, graph),
                    new RagDataPort(mysql, milvus, graph));
        } catch (RuntimeException exception) {
            dataSource.close();
            throw exception;
        }
    }

    public MemoryDataPort memory() {
        return memory;
    }

    public RagDataPort rag() {
        return rag;
    }

    @Override
    public void close() {
        try {
            graphStore.close();
        } finally {
            dataSource.close();
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
