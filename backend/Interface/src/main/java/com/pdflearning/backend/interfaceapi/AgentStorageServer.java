package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.pdflearning.backend.dataport.DataPortException;
import com.pdflearning.backend.dataport.DataPortResources;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentStorageServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;

    private final HttpServer server;
    private final ExecutorService executor;
    private final DataPortResources resources;
    private final DataPortRequestHandler requestHandler;
    private final ObjectMapper mapper;
    private final String internalToken;

    private AgentStorageServer(
            HttpServer server,
            ExecutorService executor,
            DataPortResources resources,
            String internalToken) {
        this.server = server;
        this.executor = executor;
        this.resources = resources;
        this.internalToken = internalToken;
        this.requestHandler = new DataPortRequestHandler(
                resources.memory(), resources.rag(), resources.contextSummaries());
        this.mapper = objectMapper();
        register("/internal/storage/rag/search", DataPortRequestHandler.Operation.RAG_SEARCH);
        register("/internal/storage/rag/graph", DataPortRequestHandler.Operation.RAG_GRAPH);
        register("/internal/storage/memory/query", DataPortRequestHandler.Operation.MEMORY_QUERY);
        register("/internal/storage/memory/store", DataPortRequestHandler.Operation.MEMORY_STORE);
        register("/internal/storage/memory/forget", DataPortRequestHandler.Operation.MEMORY_FORGET);
        register(
                "/internal/storage/context/summary",
                DataPortRequestHandler.Operation.CONTEXT_SUMMARY_STORE);
        server.setExecutor(executor);
    }

    public static AgentStorageServer fromEnvironment() {
        var environment = System.getenv();
        var token = required(environment, "JAVA_INTERNAL_TOKEN");
        var host = environment.getOrDefault("JAVA_INTERFACE_HOST", "127.0.0.1");
        var port = integer(environment, "JAVA_INTERFACE_PORT", 8080);
        if (port > 65535) {
            throw new IllegalStateException("JAVA_INTERFACE_PORT 必须在 1 到 65535 之间");
        }

        var resources = DataPortResources.fromEnvironment();
        try {
            resources.agentRuns().recoverInterruptedRuns();
            var server = HttpServer.create(new InetSocketAddress(host, port), 0);
            return new AgentStorageServer(
                    server, Executors.newVirtualThreadPerTaskExecutor(), resources, token);
        } catch (IOException | RuntimeException exception) {
            resources.close();
            throw new IllegalStateException("无法启动 Agent 数据接口", exception);
        }
    }

    public static void main(String[] args) {
        var application = fromEnvironment();
        Runtime.getRuntime().addShutdownHook(new Thread(application::close));
        application.start();
        System.out.println("Agent 数据接口已启动: " + application.server.getAddress());
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

    private void register(String path, DataPortRequestHandler.Operation operation) {
        server.createContext(path, exchange -> handle(exchange, path, operation));
    }

    private void handle(
            HttpExchange exchange,
            String expectedPath,
            DataPortRequestHandler.Operation operation) {
        try {
            if (!expectedPath.equals(exchange.getRequestURI().getPath())) {
                send(exchange, 404, error("接口不存在"));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                send(exchange, 405, error("只允许 POST 请求"));
                return;
            }
            var body = readBody(exchange);
            var context = InternalRequestContext.authenticate(
                    exchange.getRequestHeaders(), body, internalToken);
            send(exchange, 200, requestHandler.handle(operation, context, body));
        } catch (SecurityException exception) {
            safeSend(exchange, 401, error(exception.getMessage()));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            safeSend(exchange, 400, error(exception.getMessage()));
        } catch (DataPortException exception) {
            safeSend(exchange, 503, error(exception.getMessage()));
        } catch (IOException exception) {
            safeSend(exchange, 400, error("无法读取请求正文"));
        } catch (RuntimeException exception) {
            safeSend(exchange, 500, error("数据接口处理失败"));
        } finally {
            exchange.close();
        }
    }

    private JsonNode readBody(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (var input = exchange.getRequestBody()) {
            bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
        }
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("请求正文不能超过 2 MiB");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("请求正文不能为空");
        }
        var body = mapper.readTree(bytes);
        if (body == null) {
            throw new IllegalArgumentException("请求正文必须是 JSON 对象");
        }
        return body;
    }

    private void safeSend(HttpExchange exchange, int status, Map<String, Object> body) {
        try {
            send(exchange, status, body);
        } catch (IOException ignored) {
        }
    }

    private void send(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        var bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static Map<String, Object> error(String message) {
        return Map.of("status", "error", "message", message == null ? "请求失败" : message);
    }

    private static ObjectMapper objectMapper() {
        var module = new SimpleModule();
        module.addSerializer(OffsetDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(
                    OffsetDateTime value,
                    com.fasterxml.jackson.core.JsonGenerator generator,
                    com.fasterxml.jackson.databind.SerializerProvider serializers) throws IOException {
                generator.writeString(value.toString());
            }
        });
        return new ObjectMapper()
                .registerModule(module)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
