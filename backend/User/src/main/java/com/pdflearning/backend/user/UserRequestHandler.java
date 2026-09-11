package com.pdflearning.backend.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/**
 * 用户服务的 JSON 路由。响应结构与前端契约（Fronted/src/api/real.ts）保持一致：
 * - 认证类：{"token": "...", "user": {"id","username","nickname","createdAt"}}
 * - 用户类：user 对象
 * - 操作类：{"status": "ok"}
 * - 错误类：{"message": "..."}
 */
public final class UserRequestHandler {
    private final UserService service;
    private final ObjectMapper mapper;

    public UserRequestHandler(UserService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** 路由分发。path 为去斜杠后的路径，method 为 HTTP 方法。业务异常统一映射为 {"message": ...}。 */
    public Response handle(String method, String path, JsonNode body, String authorization) {
        try {
            return route(method, path, body, authorization);
        } catch (UserServiceException exception) {
            var node = mapper.createObjectNode();
            node.put("message", exception.getMessage());
            // 目前仅 POST-only 路由会产生 405
            var allow = exception.statusCode() == 405 ? "POST" : null;
            return new Response(exception.statusCode(), node.toString(), allow);
        }
    }

    private Response route(String method, String path, JsonNode body, String authorization) {
        return switch (path) {
            case "auth/register" -> {
                requirePost(method);
                yield ok(register(body));
            }
            case "auth/login" -> {
                requirePost(method);
                yield ok(login(body));
            }
            case "auth/logout" -> ok(authenticated(authorization, (user, token) -> {
                service.logout(token);
                return statusOk();
            }));
            case "auth/me" -> ok(authenticated(authorization, (user, token) -> userNode(user)));
            case "users/me" -> {
                if (!"PATCH".equals(method)) {
                    yield methodNotAllowed("PATCH");
                }
                yield ok(authenticated(authorization, (user, token) ->
                        userNode(service.updateNickname(user.userId(), body.path("nickname").asText(null)))));
            }
            case "users/me/password" -> {
                requirePost(method);
                yield ok(authenticated(authorization, (user, token) -> {
                    service.changePassword(
                            user.userId(),
                            token,
                            textOrNull(body, "oldPassword"),
                            textOrNull(body, "newPassword"));
                    return statusOk();
                }));
            }
            default -> notFound();
        };
    }

    private interface AuthedAction {
        ObjectNode run(UserRecord user, String token);
    }

    private ObjectNode authenticated(String authorization, AuthedAction action) {
        var token = bearerToken(authorization);
        var user = service.authenticate(token);
        return action.run(user, token);
    }

    private ObjectNode register(JsonNode body) {
        var result = service.register(
                textOrNull(body, "username"),
                textOrNull(body, "password"),
                textOrNull(body, "nickname"));
        return authNode(result);
    }

    private ObjectNode login(JsonNode body) {
        var result = service.login(
                textOrNull(body, "username"),
                textOrNull(body, "password"));
        return authNode(result);
    }

    private ObjectNode authNode(UserService.AuthResult result) {
        var node = mapper.createObjectNode();
        node.put("token", result.token());
        node.set("user", userNode(result.user()));
        return node;
    }

    private ObjectNode userNode(UserRecord user) {
        var node = mapper.createObjectNode();
        node.put("id", user.userId());
        node.put("username", user.username());
        node.put("nickname", user.nickname());
        node.put("createdAt", user.createdAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return node;
    }

    private ObjectNode statusOk() {
        var node = mapper.createObjectNode();
        node.put("status", "ok");
        return node;
    }

    private static String textOrNull(JsonNode body, String field) {
        var value = body.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new UserServiceException(401, "缺少 Bearer 登录令牌");
        }
        var token = authorization.substring("Bearer ".length()).strip();
        if (token.isEmpty()) {
            throw new UserServiceException(401, "缺少 Bearer 登录令牌");
        }
        return token;
    }

    private static void requirePost(String method) {
        if (!"POST".equals(method)) {
            throw new UserServiceException(405, "只允许 POST 请求");
        }
    }

    private static UserRequestHandler.Response methodNotAllowed(String allow) {
        return new UserRequestHandler.Response(405, "{\"message\":\"该方法不被允许\"}", allow);
    }

    private static UserRequestHandler.Response notFound() {
        return new UserRequestHandler.Response(404, "{\"message\":\"接口不存在\"}", null);
    }

    private static UserRequestHandler.Response ok(ObjectNode node) {
        return new UserRequestHandler.Response(200, node.toString(), null);
    }

    public record Response(int statusCode, String bodyJson, String allowHeader) {
    }

    // ---------- 请求正文读取（由 Server 调用） ----------

    public JsonNode parseBody(byte[] raw) throws IOException {
        if (raw.length == 0) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(new String(raw, StandardCharsets.UTF_8));
    }
}
