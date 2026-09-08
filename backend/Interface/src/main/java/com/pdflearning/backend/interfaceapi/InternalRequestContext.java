package com.pdflearning.backend.interfaceapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.pdflearning.backend.user.DevIdentity;
import com.sun.net.httpserver.Headers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Pattern;

record InternalRequestContext(
        String userId,
        String sessionId,
        String requestId,
        String messageId) {

    static InternalRequestContext authenticate(
            Headers headers,
            JsonNode body,
            String internalToken) {
        var expectedAuthorization = "Bearer " + internalToken;
        var actualAuthorization = headers.getFirst("Authorization");
        if (actualAuthorization == null || !MessageDigest.isEqual(
                actualAuthorization.getBytes(StandardCharsets.UTF_8),
                expectedAuthorization.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("内部服务认证失败");
        }

        var userId = requiredHeader(headers, "X-User-ID");
        var sessionId = requiredHeader(headers, "X-Session-ID");
        var requestId = requiredHeader(headers, "X-Request-ID");
        var messageId = optionalHeader(headers, "X-Message-ID");

        matchBody(body, "user_id", userId);
        matchBody(body, "session_id", sessionId);
        matchBody(body, "request_id", requestId);
        matchOptionalBody(body, "message_id", messageId);

        if (!DevIdentity.USER_ID.equals(userId)) {
            throw new SecurityException("当前开发环境只允许 dev_user");
        }
        var expectedSession = Pattern.compile(
                "session_\\d{8}_\\d{6}_" + Pattern.quote(userId));
        if (!expectedSession.matcher(sessionId).matches()) {
            throw new IllegalArgumentException(
                    "session_id 必须符合 session_yyyyMMdd_HHmmss_" + userId);
        }
        return new InternalRequestContext(userId, sessionId, requestId, messageId);
    }

    String requireMessageId() {
        if (messageId == null) {
            throw new IllegalArgumentException("当前操作需要 X-Message-ID 和 message_id");
        }
        return messageId;
    }

    private static String requiredHeader(Headers headers, String name) {
        var value = optionalHeader(headers, name);
        if (value == null) {
            throw new IllegalArgumentException("缺少请求头 " + name);
        }
        return value;
    }

    private static String optionalHeader(Headers headers, String name) {
        var value = headers.getFirst(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static void matchBody(JsonNode body, String name, String expected) {
        var value = body.path(name);
        if (!value.isTextual() || !expected.equals(value.textValue())) {
            throw new IllegalArgumentException(name + " 必须与可信请求头一致");
        }
    }

    private static void matchOptionalBody(JsonNode body, String name, String expected) {
        var value = body.get(name);
        var actual = value == null || value.isNull() ? null : value.asText(null);
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(name + " 必须与可信请求头一致");
        }
    }
}
