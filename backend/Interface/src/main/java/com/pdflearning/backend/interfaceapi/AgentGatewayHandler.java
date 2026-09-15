package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pdflearning.backend.dataport.ChatDataPort;
import com.pdflearning.backend.dataport.AgentRunDataPort;
import com.pdflearning.backend.dataport.ContextSummaryDataPort;
import com.pdflearning.backend.dataport.DataPortException;
import com.pdflearning.backend.dataport.DocumentDataPort;
import com.pdflearning.backend.dataport.RagDocumentStore;
import com.pdflearning.backend.dataport.SessionLedgerDataPort;
import com.pdflearning.backend.user.UserRequestHandler;
import com.pdflearning.backend.user.UserService;
import com.pdflearning.backend.user.UserServiceException;
import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 面向前端的统一 HTTP 路由；用户语义委托给 User，Agent 语义留在 Interface。 */
final class AgentGatewayHandler {
    private static final int MAX_USER_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_UPLOAD_BYTES = 50 * 1024 * 1024;
    private static final int MAX_TITLE_LENGTH = 100;
    private static final int MAX_MESSAGE_LENGTH = 500_000;
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,120}");
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,160}");

    private final UserService users;
    private final UserRequestHandler userRequests;
    private final AgentGatewayHttp http;
    private final AgentSessionService sessions;
    private final AgentRunCoordinator runs;
    private final DocumentService documents;

    AgentGatewayHandler(
            UserService users,
            ChatDataPort chats,
            AgentRunDataPort agentRuns,
            ContextSummaryDataPort summaries,
            SessionLedgerDataPort sessionLedgers,
            DocumentDataPort documentData,
            RagDocumentStore ragDocuments,
            RagBuildClient ragBuilder,
            Path uploadRoot,
            Executor executor,
            AgentControlClient controlClient,
            AgentRunService runService,
            AgentRunRequest.AgentConfig agentConfig,
            String allowedOrigin,
            ObjectMapper mapper) {
        this.users = users;
        this.userRequests = new UserRequestHandler(users, mapper);
        this.http = new AgentGatewayHttp(allowedOrigin, mapper);
        this.sessions = new AgentSessionService(chats, controlClient, mapper);
        this.documents = new DocumentService(
                documentData, ragDocuments, ragBuilder, uploadRoot, executor, mapper);
        var snapshots = new AgentContextSnapshotFactory(
                chats, agentRuns, summaries, sessionLedgers, mapper);
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
            String path = normalizePath(exchange.getRequestURI().getPath());
            if (isUserRoute(path)) {
                handleUserRequest(exchange, path);
                return;
            }
            route(exchange, path, authenticate(exchange));
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

    private void route(HttpExchange exchange, String path, String userId) {
        String method = exchange.getRequestMethod();
        if ("/sessions".equals(path)) {
            handleSessions(exchange, method, userId);
            return;
        }
        if ("/documents".equals(path)) {
            handleDocuments(exchange, method, userId);
            return;
        }

        String[] parts = path.split("/");
        if (parts.length == 3 && "documents".equals(parts[1]) && "DELETE".equals(method)) {
            documents.delete(userId, requiredPublicPathId(parts[2], "document_id"));
            http.send(exchange, 204, null);
            return;
        }
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

    private void handleDocuments(HttpExchange exchange, String method, String userId) {
        if ("GET".equals(method)) {
            http.send(exchange, 200, documents.list(userId));
            return;
        }
        if (!"POST".equals(method)) {
            throw new AgentGatewayException(405, "请求方法不受支持");
        }
        String fileName = queryParameter(exchange, "filename");
        http.ensureContentLength(exchange, MAX_UPLOAD_BYTES);
        byte[] content = http.readBody(exchange, MAX_UPLOAD_BYTES);
        http.send(exchange, 202, documents.upload(userId, fileName, content));
    }

    private void handleUserRequest(HttpExchange exchange, String path) {
        JsonNode body;
        try {
            body = userRequests.parseBody(http.readBody(exchange, MAX_USER_REQUEST_BYTES));
        } catch (java.io.IOException exception) {
            throw new AgentGatewayException(400, "请求正文不是有效 JSON");
        }
        var response = userRequests.handle(
                exchange.getRequestMethod(),
                path.substring(1),
                body,
                exchange.getRequestHeaders().getFirst("Authorization"));
        if (response.allowHeader() != null) {
            exchange.getResponseHeaders().set("Allow", response.allowHeader());
        }
        http.sendJson(exchange, response.statusCode(), response.bodyJson());
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

    private static String requiredPublicPathId(String value, String name) {
        if (value == null || !PUBLIC_ID.matcher(value).matches()) {
            throw new AgentGatewayException(400, name + " 格式无效");
        }
        return value;
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            throw new AgentGatewayException(400, "缺少查询参数 " + name);
        }
        try {
            for (String item : query.split("&")) {
                int separator = item.indexOf('=');
                if (separator >= 0 && name.equals(URLDecoder.decode(
                        item.substring(0, separator), StandardCharsets.UTF_8))) {
                    return URLDecoder.decode(
                            item.substring(separator + 1), StandardCharsets.UTF_8);
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new AgentGatewayException(400, "查询参数编码无效");
        }
        throw new AgentGatewayException(400, "缺少查询参数 " + name);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1)
                : path;
    }

    private static boolean isUserRoute(String path) {
        return switch (path) {
            case "/auth/register", "/auth/login", "/auth/logout", "/auth/me",
                    "/users/me", "/users/me/password" -> true;
            default -> false;
        };
    }
}
