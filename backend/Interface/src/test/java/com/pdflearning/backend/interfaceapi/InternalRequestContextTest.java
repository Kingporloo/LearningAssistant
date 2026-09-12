package com.pdflearning.backend.interfaceapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

class InternalRequestContextTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsMatchingDevelopmentContext() throws Exception {
        var context = InternalRequestContext.authenticate(
                headers("Bearer internal-token", "dev_user", "session_20260908_101112_dev_user"),
                mapper.readTree("""
                        {
                          "user_id":"dev_user",
                          "session_id":"session_20260908_101112_dev_user",
                          "request_id":"request-1",
                          "message_id":"message-1"
                        }
                        """),
                "internal-token");

        assertEquals("dev_user", context.userId());
        assertEquals("session_20260908_101112_dev_user", context.sessionId());
        assertEquals("request-1", context.requestId());
        assertEquals("message-1", context.messageId());
    }

    @Test
    void acceptsUserServiceIdentity() throws Exception {
        var userId = "user_20260912_103040_1a2b3c4d";
        var sessionId = "session_20260912_103100_" + userId;
        var context = InternalRequestContext.authenticate(
                headers("Bearer internal-token", userId, sessionId),
                mapper.readTree("""
                        {
                          "user_id":"user_20260912_103040_1a2b3c4d",
                          "session_id":"session_20260912_103100_user_20260912_103040_1a2b3c4d",
                          "request_id":"request-1",
                          "message_id":"message-1"
                        }
                        """),
                "internal-token");

        assertEquals(userId, context.userId());
        assertEquals(sessionId, context.sessionId());
    }

    @Test
    void rejectsUnsafeUserIdentity() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> InternalRequestContext.authenticate(
                headers("Bearer internal-token", "../user", "session_20260912_103100_../user"),
                mapper.readTree("""
                        {
                          "user_id":"../user",
                          "session_id":"session_20260912_103100_../user",
                          "request_id":"request-1",
                          "message_id":"message-1"
                        }
                        """),
                "internal-token"));
    }

    @Test
    void rejectsNonTextMessageId() throws Exception {
        var body = mapper.readTree(validBody());
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("message_id", 1);

        assertThrows(IllegalArgumentException.class, () -> InternalRequestContext.authenticate(
                headers("Bearer internal-token", "dev_user", "session_20260908_101112_dev_user"),
                body,
                "internal-token"));
    }

    @Test
    void rejectsInvalidToken() throws Exception {
        assertThrows(SecurityException.class, () -> InternalRequestContext.authenticate(
                headers("Bearer wrong", "dev_user", "session_20260908_101112_dev_user"),
                mapper.readTree(validBody()),
                "internal-token"));
    }

    @Test
    void rejectsBodyIdentityThatDoesNotMatchHeaders() throws Exception {
        var body = mapper.readTree(validBody());
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("user_id", "another-user");

        assertThrows(IllegalArgumentException.class, () -> InternalRequestContext.authenticate(
                headers("Bearer internal-token", "dev_user", "session_20260908_101112_dev_user"),
                body,
                "internal-token"));
    }

    @Test
    void rejectsUnexpectedSessionFormat() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> InternalRequestContext.authenticate(
                headers("Bearer internal-token", "dev_user", "session-dev_user"),
                mapper.readTree("""
                        {
                          "user_id":"dev_user",
                          "session_id":"session-dev_user",
                          "request_id":"request-1",
                          "message_id":"message-1"
                        }
                        """),
                "internal-token"));
    }

    private static Headers headers(String authorization, String userId, String sessionId) {
        var headers = new Headers();
        headers.set("Authorization", authorization);
        headers.set("X-User-ID", userId);
        headers.set("X-Session-ID", sessionId);
        headers.set("X-Request-ID", "request-1");
        headers.set("X-Message-ID", "message-1");
        return headers;
    }

    private static String validBody() {
        return """
                {
                  "user_id":"dev_user",
                  "session_id":"session_20260908_101112_dev_user",
                  "request_id":"request-1",
                  "message_id":"message-1"
                }
                """;
    }
}
