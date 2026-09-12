package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pdflearning.backend.dataport.ChatDataPort;
import com.pdflearning.backend.dataport.ContextSummaryDataPort;
import com.pdflearning.backend.dataport.DataPortException;
import com.pdflearning.backend.user.UserService;
import com.pdflearning.backend.user.UserServiceException;
import com.sun.net.httpserver.HttpExchange;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 面向前端的会话、聊天和手动 Compact HTTP 路由。 */
final class AgentGatewayHandler {
    private static final int MAX_TITLE_LENGTH = 100;
    private static final int MAX_MESSAGE_LENGTH = 500_000;
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,120}");
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,160}");

    private final UserService users;
    private final AgentGatewayHttp http;
    private final AgentSessionService sessions;
    private final AgentRunCoordinator runs;

    AgentGatewayHandler(
            UserService users,
            ChatDataPort chats,
            ContextSummaryDataPort summaries,
            AgentControlClient controlClient,
            AgentRunService runService,
            AgentRunRequest.AgentConfig agentConfig,
            String allowedOrigin,
            ObjectMapper mapper) {
        this.users = users;
        this.http = new AgentGatewayHttp(allowedOrigin, mapper);
        this.sessions = new AgentSessionService(chats, controlClient, mapper);
        var snapshots = new AgentContextSnapshotFactory(chats, summaries, mapper);
        this.runs = new AgentRunCoordinator(
                chats,
                controlClient,
                runService,
                agentConfig,
                sessions,
                snapshots,
                http,
                mapper);
    }

    void handle(HttpExchange exchange) {
        try {
            http.applyCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                http.send(exchange, 204, null);
                return;
            }
            route(exchange, authenticate(exchange));
        } catch (AgentGatewayException failure) {
            http.send(exchange, failure.status(), http.error(failure.getMessage()));
        } catch (UserServiceException failure) {
            http.send(exchange, failure.statusCode(), http.error(failure.getMessage()));
        } catch (DataPortException failure) {
            http.send(exchange, 500, http.error("数据服务处理失败"));
        } catch (RuntimeException failure) {
            http.send(exchange, 500, http.error("Agent 网关处理失败"));
        }
    }

    private void route(HttpExchange exchange, String userId) {
        String method = exchange.getRequestMethod();
        String path = normalizePath(exchange.getRequestURI().getPath());
        if ("/sessions".equals(path)) {
            handleSessions(exchange, method, userId);
            return;
        }

        String[] parts = path.split("/");
        if (parts.length < 3 || !"sessions".equals(parts[1])) {
            throw new AgentGatewayException(404, "接口不存在");
        }
        String sessionId = requireSessionId(parts[2]);
        if (parts.length == 3 && "DELETE".equals(method)) {
            sessions.delete(userId, sessionId);
            http.send(exchange, 204, null);
        } else if (parts.length == 4
                && "messages".equals(parts[3])
                && "GET".equals(method)) {
            http.send(exchange, 200, sessions.messages(userId, sessionId));
        } else if (parts.length == 4
                && "runs".equals(parts[3])
                && "POST".equals(method)) {
            handleRun(exchange, userId, sessionId);
        } else if (parts.length == 4
                && "compact".equals(parts[3])
                && "POST".equals(method)) {
            handleCompact(exchange, userId, sessionId);
        } else {
            throw new AgentGatewayException(404, "接口不存在");
        }
    }

    private void handleSessions(HttpExchange exchange, String method, String userId) {
        if ("GET".equals(method)) {
            http.send(exchange, 200, sessions.list(userId));
            return;
        }
        if (!"POST".equals(method)) {
            throw new AgentGatewayException(405, "请求方法不受支持");
        }
        var body = http.readObject(exchange, Set.of("title"));
        http.send(exchange, 201, sessions.create(userId, optionalTitle(body.get("title"))));
    }

    private void handleRun(HttpExchange exchange, String userId, String sessionId) {
        var body = http.readObject(exchange, Set.of("message", "request_id", "message_id"));
        runs.run(
                exchange,
                userId,
                sessionId,
                requiredPublicId(body, "request_id"),
                requiredPublicId(body, "message_id"),
                requiredText(body, "message", MAX_MESSAGE_LENGTH));
    }

    private void handleCompact(HttpExchange exchange, String userId, String sessionId) {
        var body = http.readObject(exchange, Set.of("request_id"));
        String requestId = body.has("request_id")
                ? requiredPublicId(body, "request_id")
                : UUID.randomUUID().toString();
        runs.compact(exchange, userId, sessionId, requestId);
    }

    private String authenticate(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String token = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring("Bearer ".length()).strip();
        }
        return users.authenticate(token).userId();
    }

    private static String requiredText(ObjectNode body, String name, int maxLength) {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new AgentGatewayException(400, name + " 必须是非空字符串");
        }
        String text = value.textValue().strip();
        if (text.length() > maxLength) {
            throw new AgentGatewayException(400, name + " 过长");
        }
        return text;
    }

    private static String requiredPublicId(ObjectNode body, String name) {
        String value = requiredText(body, name, 120);
        if (!PUBLIC_ID.matcher(value).matches()) {
            throw new AgentGatewayException(400, name + " 格式无效");
        }
        return value;
    }

    private static String optionalTitle(JsonNode value) {
        if (value == null || value.isNull()) {
            return "新会话";
        }
        if (!value.isTextual()) {
            throw new AgentGatewayException(400, "title 必须是字符串");
        }
        String title = value.textValue().strip();
        if (title.isEmpty()) {
            return "新会话";
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new AgentGatewayException(400, "title 不能超过 100 个字符");
        }
        return title;
    }

    private static String requireSessionId(String value) {
        if (value == null || !SESSION_ID.matcher(value).matches()) {
            throw new AgentGatewayException(400, "session_id 格式无效");
        }
        return value;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1)
                : path;
    }
}
