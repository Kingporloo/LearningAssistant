package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdflearning.backend.dataport.MySqlDataPortResources;
import com.pdflearning.backend.user.UserService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 前端访问 Agent 的 Java 入口；身份、会话和持久化均在此处完成。 */
public final class AgentGatewayServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final MySqlDataPortResources resources;

    private AgentGatewayServer(
            HttpServer server,
            ExecutorService executor,
            MySqlDataPortResources resources) {
        this.server = server;
        this.executor = executor;
        this.resources = resources;
    }

    public static AgentGatewayServer fromEnvironment() {
        return from(System.getenv());
    }

    static AgentGatewayServer from(Map<String, String> environment) {
        String host = environment.getOrDefault("AGENT_GATEWAY_HOST", "127.0.0.1");
        int port = integer(environment, "AGENT_GATEWAY_PORT", 8082);
        if (port > 65535) {
            throw new IllegalStateException("AGENT_GATEWAY_PORT 必须在 1 到 65535 之间");
        }
        String allowedOrigin = environment.getOrDefault(
                "AGENT_GATEWAY_ALLOWED_ORIGIN", "http://localhost:5173");
        String pythonToken = required(environment, "PYTHON_INTERNAL_TOKEN");
        URI pythonBaseUrl;
        try {
            pythonBaseUrl = URI.create(environment.getOrDefault(
                    "PYTHON_AGENT_BASE_URL", "http://127.0.0.1:8800"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PYTHON_AGENT_BASE_URL 无效", exception);
        }

        var resources = MySqlDataPortResources.fromEnvironment(environment);
        try {
            var mapper = new ObjectMapper();
            var users = new UserService(
                    resources.users(),
                    Duration.ofHours(integer(environment, "USER_TOKEN_TTL_HOURS", 168)));
            var controlClient = new AgentControlClient(pythonBaseUrl, pythonToken);
            var runClient = new AgentSseClient(pythonBaseUrl, pythonToken);
            var runService = new AgentRunService(runClient, resources.agentRuns());
            var handler = new AgentGatewayHandler(
                    users,
                    resources.chats(),
                    resources.contextSummaries(),
                    controlClient,
                    runService,
                    agentConfig(environment),
                    allowedOrigin,
                    mapper);
            resources.agentRuns().recoverInterruptedRuns();

            var server = HttpServer.create(new InetSocketAddress(host, port), 0);
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            server.createContext("/", handler::handle);
            server.setExecutor(executor);
            return new AgentGatewayServer(server, executor, resources);
        } catch (IOException | RuntimeException exception) {
            resources.close();
            throw new IllegalStateException("无法启动 Agent 网关", exception);
        }
    }

    public static void main(String[] args) {
        var application = fromEnvironment();
        Runtime.getRuntime().addShutdownHook(new Thread(application::close));
        application.start();
        System.out.println("Agent 网关已启动: " + application.server.getAddress());
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
        resources.close();
    }

    private static AgentRunRequest.AgentConfig agentConfig(Map<String, String> environment) {
        return new AgentRunRequest.AgentConfig(
                integer(environment, "AGENT_MODEL_WINDOW", 128_000),
                integer(environment, "AGENT_MAX_CONTEXT_TOKENS", 100_000),
                nonNegativeInteger(environment, "AGENT_OUTPUT_RESERVE", 8_000),
                nonNegativeInteger(environment, "AGENT_SAFETY_MARGIN", 4_000),
                nonNegativeInteger(environment, "AGENT_TOOL_RESULT_RESERVE", 8_000),
                decimal(environment, "AGENT_COMPACT_TRIGGER_RATIO", 0.92),
                integer(environment, "AGENT_SUMMARY_MAX_TOKENS", 2_000),
                integer(environment, "AGENT_KEEP_RECENT_TURNS", 5));
    }

    private static String required(Map<String, String> environment, String name) {
        var value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value;
    }

    private static int integer(Map<String, String> environment, String name, int defaultValue) {
        int value = parsedInteger(environment, name, defaultValue);
        if (value <= 0) {
            throw new IllegalStateException(name + " 必须是正整数");
        }
        return value;
    }

    private static int nonNegativeInteger(
            Map<String, String> environment,
            String name,
            int defaultValue) {
        int value = parsedInteger(environment, name, defaultValue);
        if (value < 0) {
            throw new IllegalStateException(name + " 不能小于 0");
        }
        return value;
    }

    private static int parsedInteger(
            Map<String, String> environment,
            String name,
            int defaultValue) {
        var raw = environment.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " 必须是整数", exception);
        }
    }

    private static double decimal(
            Map<String, String> environment,
            String name,
            double defaultValue) {
        var raw = environment.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " 必须是数字", exception);
        }
    }
}
