package com.pdflearning.backend.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdflearning.backend.dataport.MySqlDataPortResources;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 面向前端的用户服务：注册、登录、登出、个人信息、修改密码。
 *
 * 环境变量（与 deploy/.env.example 一致）：
 * - MYSQL_JDBC_URL / MYSQL_USER / MYSQL_PASSWORD（必填，与 DataPort 相同）
 * - USER_SERVICE_HOST（默认 127.0.0.1）、USER_SERVICE_PORT（默认 8081）
 * - USER_TOKEN_TTL_HOURS（默认 168，即 7 天）
 * - USER_ALLOWED_ORIGIN（默认 http://localhost:5173，跨域白名单）
 *
 * 用户表由 DataPort 的 V4__user.sql 在部署阶段初始化。
 */
public final class UserServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 64 * 1024;

    private final HttpServer server;
    private final ExecutorService executor;
    private final MySqlDataPortResources resources;

    private UserServer(
            HttpServer server,
            ExecutorService executor,
            MySqlDataPortResources resources) {
        this.server = server;
        this.executor = executor;
        this.resources = resources;
    }

    public static UserServer fromEnvironment() {
        return from(System.getenv());
    }

    static UserServer from(Map<String, String> environment) {
        var host = environment.getOrDefault("USER_SERVICE_HOST", "127.0.0.1");
        var port = integer(environment, "USER_SERVICE_PORT", 8081);
        var tokenTtl = Duration.ofHours(integer(environment, "USER_TOKEN_TTL_HOURS", 168));
        var allowedOrigin = environment.getOrDefault("USER_ALLOWED_ORIGIN", "http://localhost:5173");

        var resources = MySqlDataPortResources.fromEnvironment(environment);

        try {
            var service = new UserService(resources.users(), tokenTtl);
            var handler = new UserRequestHandler(service, new ObjectMapper());
            var server = HttpServer.create(new InetSocketAddress(host, port), 0);
            var application = new UserServer(server, Executors.newVirtualThreadPerTaskExecutor(), resources);
            server.createContext("/", exchange -> application.handle(exchange, allowedOrigin, handler));
            server.setExecutor(application.executor);
            return application;
        } catch (IOException | RuntimeException exception) {
            resources.close();
            throw new IllegalStateException("无法启动用户服务", exception);
        }
    }

    public static void main(String[] args) {
        var application = fromEnvironment();
        Runtime.getRuntime().addShutdownHook(new Thread(application::close));
        application.start();
        System.out.println("用户服务已启动: " + application.server.getAddress());
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

    private void handle(HttpExchange exchange, String allowedOrigin, UserRequestHandler handler) {
        try {
            if (applyCors(exchange, allowedOrigin) && "OPTIONS".equals(exchange.getRequestMethod())) {
                send(exchange, 204, "");
                return;
            }
            var path = exchange.getRequestURI().getPath().replaceFirst("^/", "");
            var body = handler.parseBody(readBody(exchange));
            var response = handler.handle(
                    exchange.getRequestMethod(),
                    path,
                    body,
                    exchange.getRequestHeaders().getFirst("Authorization"));
            if (response.allowHeader() != null) {
                exchange.getResponseHeaders().set("Allow", response.allowHeader());
            }
            send(exchange, response.statusCode(), response.bodyJson());
        } catch (UserServiceException exception) {
            safeSend(exchange, exception.statusCode(),
                    "{\"message\":" + jsonQuote(exception.getMessage()) + "}");
        } catch (IOException exception) {
            safeSend(exchange, 400, "{\"message\":\"无法读取请求正文\"}");
        } catch (RuntimeException exception) {
            safeSend(exchange, 500, "{\"message\":\"用户服务处理失败\"}");
        }
    }

    private boolean applyCors(HttpExchange exchange, String allowedOrigin) {
        var origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || !allowedOrigin.equals(origin)) {
            return false;
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PATCH, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
        exchange.getResponseHeaders().set("Vary", "Origin");
        return true;
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (var input = exchange.getRequestBody()) {
            var bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) {
                throw new UserServiceException(413, "请求正文过大");
            }
            return bytes;
        }
    }

    private static void send(HttpExchange exchange, int statusCode, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (statusCode == 204) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void safeSend(HttpExchange exchange, int statusCode, String body) {
        try {
            send(exchange, statusCode, body);
        } catch (IOException ignored) {
            // 客户端已中断连接
        }
    }

    private static String jsonQuote(String value) {
        var escaped = value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return "\"" + escaped + "\"";
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
