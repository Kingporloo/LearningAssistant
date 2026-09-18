package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdflearning.backend.dataport.DataPortResources;
import com.pdflearning.backend.user.UserService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 前端统一 Java 入口；提供用户接口、Agent 会话、运行与持久化。 */
public final class AgentGatewayServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final DataPortResources resources;
    private final URI pythonBaseUrl;
    private final URI pythonRagBaseUrl;
    private final ObjectMapper mapper;

    private AgentGatewayServer(
            HttpServer server,
            ExecutorService executor,
            DataPortResources resources,
            URI pythonBaseUrl,
            URI pythonRagBaseUrl,
            ObjectMapper mapper) {
        this.server = server;
        this.executor = executor;
        this.resources = resources;
        this.pythonBaseUrl = pythonBaseUrl;
        this.pythonRagBaseUrl = pythonRagBaseUrl;
        this.mapper = mapper;
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
        URI pythonBaseUrl = uri(
                environment, "PYTHON_AGENT_BASE_URL", "http://127.0.0.1:8800");
        URI pythonRagBaseUrl = uri(
                environment, "PYTHON_RAG_BASE_URL", "http://127.0.0.1:8803");
        Path uploadRoot = Path.of(required(environment, "RAG_ALLOWED_ROOT"));

        var resources = DataPortResources.from(environment);
        ExecutorService executor = null;
        try {
            var mapper = new ObjectMapper();
            var users = new UserService(
                    resources.users(),
                    Duration.ofHours(integer(environment, "USER_TOKEN_TTL_HOURS", 168)));
            var controlClient = new AgentControlClient(pythonBaseUrl, pythonToken);
            var runClient = new AgentSseClient(pythonBaseUrl, pythonToken);
            var runService = new AgentRunService(runClient, resources.agentRuns());
            var ragBuilder = new RagBuildClient(
                    pythonRagBaseUrl,
                    pythonToken,
                    Duration.ofSeconds(integer(environment, "RAG_BUILD_TIMEOUT_SECONDS", 1800)),
                    mapper);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            var handler = new AgentGatewayHandler(
                    users,
                    resources.chats(),
                    resources.agentRuns(),
                    resources.contextSummaries(),
                    resources.sessionLedgers(),
                    resources.documents(),
                    resources.rag(),
                    ragBuilder,
                    uploadRoot,
                    executor,
                    controlClient,
                    runService,
                    agentConfig(environment),
                    allowedOrigin,
                    mapper);
            resources.agentRuns().recoverInterruptedRuns();
            resources.documents().recoverInterrupted();

            var server = HttpServer.create(new InetSocketAddress(host, port), 0);
            var application = new AgentGatewayServer(
                    server, executor, resources, pythonBaseUrl, pythonRagBaseUrl, mapper);
            server.createContext("/health/live", exchange -> application.health(exchange, false));
            server.createContext("/health/ready", exchange -> application.health(exchange, true));
            server.createContext("/", handler::handle);
            server.setExecutor(executor);
            return application;
        } catch (IOException | RuntimeException exception) {
            if (executor != null) {
                executor.close();
            }
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

    private void health(com.sun.net.httpserver.HttpExchange exchange, boolean checkDependencies) {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                sendHealth(exchange, 405, Map.of("status", "error"));
                return;
            }
            if (!checkDependencies) {
                sendHealth(exchange, 200, Map.of("status", "alive"));
                return;
            }
            var components = new LinkedHashMap<String, String>();
            var data = resources.readiness();
            components.put("data", data.ready() ? "up" : "down");
            components.put("agent", probe(pythonBaseUrl.resolve("/health/ready")));
            components.put("rag_build", probe(pythonRagBaseUrl.resolve("/health/ready")));
            boolean ready = components.values().stream().allMatch("up"::equals);
            sendHealth(
                    exchange,
                    ready ? 200 : 503,
                    Map.of(
                            "status", ready ? "ready" : "not_ready",
                            "components", components));
        } catch (RuntimeException | IOException exception) {
            exchange.close();
        } finally {
            exchange.close();
        }
    }

    private String probe(URI uri) {
        try {
            var request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 ? "up" : "down";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "down";
        } catch (IOException | RuntimeException exception) {
            return "down";
        }
    }

    private void sendHealth(
            com.sun.net.httpserver.HttpExchange exchange,
            int status,
            Map<String, ?> body) throws IOException {
        var bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
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

    private static URI uri(
            Map<String, String> environment,
            String name,
            String defaultValue) {
        try {
            var value = URI.create(environment.getOrDefault(name, defaultValue));
            if (value.getScheme() == null || value.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " 无效", exception);
        }
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
