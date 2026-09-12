package com.pdflearning.backend.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdflearning.backend.dataport.UserDataPort;
import java.time.Clock;
import java.time.Duration;
import java.util.Random;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 校验响应 JSON 与前端契约（Fronted/src/api/real.ts）字段一致：
 * AuthResult = {token, user:{id, username, nickname, createdAt}}；错误 = {message}。
 */
class UserRequestHandlerTest {
    private UserRequestHandler handler;
    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(
                UserTestDatabase.create(),
                new PasswordHasher(),
                Clock.systemUTC(),
                Duration.ofDays(7),
                new Random(3));
        handler = new UserRequestHandler(service, new ObjectMapper());
    }

    @Test
    void registerReturnsTokenAndUserWithContractFieldNames() throws Exception {
        var response = handler.handle(
                "POST", "auth/register", body("""
                        {"username":"alice","password":"secret123","nickname":"爱丽丝"}
                        """), null);

        assertEquals(200, response.statusCode());
        var json = mapper().readTree(response.bodyJson());
        assertEquals("alice", json.path("user").path("username").asText());
        assertEquals("爱丽丝", json.path("user").path("nickname").asText());
        assertFalseMissing(json, "token", "user.id", "user.createdAt");
    }

    @Test
    void loginMeAndProfileUseBearerToken() throws Exception {
        handler.handle("POST", "auth/register", body("""
                {"username":"bob","password":"secret123"}
                """), null);

        var login = mapper().readTree(handler.handle("POST", "auth/login", body("""
                {"username":"bob","password":"secret123"}
                """), null).bodyJson());
        var token = "Bearer " + login.path("token").asText();

        var me = mapper().readTree(handler.handle("GET", "auth/me", body("{}"), token).bodyJson());
        assertEquals("bob", me.path("username").asText());

        var updated = mapper().readTree(handler.handle("PATCH", "users/me", body("""
                {"nickname":"新昵称"}
                """), token).bodyJson());
        assertEquals("新昵称", updated.path("nickname").asText());

        var logout = handler.handle("POST", "auth/logout", body("{}"), token);
        assertEquals(200, logout.statusCode());

        var afterLogout = handler.handle("GET", "auth/me", body("{}"), token);
        assertEquals(401, afterLogout.statusCode());
        assertEquals("登录已过期，请重新登录", errorMessage(afterLogout));
    }

    @Test
    void missingOrBadRequestsReturnJsonErrors() throws Exception {
        var register = handler.handle("POST", "auth/register", body("{}"), null);
        assertEquals(400, register.statusCode());
        assertEquals("用户名需为 3-24 位字母、数字或下划线", errorMessage(register));

        var wrongMethod = handler.handle("GET", "auth/login", body("{}"), null);
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("POST", wrongMethod.allowHeader());

        var unknown = handler.handle("POST", "auth/unknown", body("{}"), null);
        assertEquals(404, unknown.statusCode());

        var noToken = handler.handle("GET", "auth/me", body("{}"), null);
        assertEquals(401, noToken.statusCode());

        var numericPassword = handler.handle("POST", "auth/register", body("""
                {"username":"typed_user","password":123456}
                """), null);
        assertEquals(400, numericPassword.statusCode());
        assertEquals("password 必须是字符串", errorMessage(numericPassword));

        var wrongLogoutMethod = handler.handle("GET", "auth/logout", body("{}"), null);
        assertEquals(405, wrongLogoutMethod.statusCode());

        var wrongMeMethod = handler.handle("POST", "auth/me", body("{}"), null);
        assertEquals(405, wrongMeMethod.statusCode());
        assertEquals("GET", wrongMeMethod.allowHeader());
    }

    @Test
    void changePasswordFollowsContract() throws Exception {
        var token = "Bearer " + mapper().readTree(handler.handle("POST", "auth/register", body("""
                {"username":"carol","password":"secret123"}
                """), null).bodyJson()).path("token").asText();

        var bad = handler.handle("POST", "users/me/password", body("""
                {"oldPassword":"wrong","newPassword":"newpass456"}
                """), token);
        assertEquals(400, bad.statusCode());
        assertEquals("旧密码不正确", errorMessage(bad));

        var good = handler.handle("POST", "users/me/password", body("""
                {"oldPassword":"secret123","newPassword":"newpass456"}
                """), token);
        assertEquals(200, good.statusCode());
        assertEquals("ok", mapper().readTree(good.bodyJson()).path("status").asText());
    }

    @Test
    void parseBodyRequiresJsonObject() {
        var error = assertThrows(
                UserServiceException.class,
                () -> handler.parseBody("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(400, error.statusCode());
    }

    @Test
    void dataPortFailureUsesStablePublicMessage() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:missing_" + System.nanoTime());
        var failingHandler = new UserRequestHandler(
                new UserService(new UserDataPort(dataSource), Duration.ofDays(7)),
                new ObjectMapper());

        var response = failingHandler.handle("POST", "auth/register", body("""
                {"username":"database_test","password":"secret123"}
                """), null);

        assertEquals(503, response.statusCode());
        assertEquals("用户数据服务暂时不可用", errorMessage(response));
    }

    // ---------- 工具 ----------

    private static com.fasterxml.jackson.databind.JsonNode body(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    private ObjectMapper mapper() {
        return new ObjectMapper();
    }

    private static String errorMessage(UserRequestHandler.Response response) throws Exception {
        return new ObjectMapper().readTree(response.bodyJson()).path("message").asText();
    }

    private static void assertFalseMissing(JsonNode json, String... paths) {
        for (var path : paths) {
            var node = json.at("/" + path.replace('.', '/'));
            if (node.isMissingNode() || node.isNull()) {
                throw new AssertionError("响应缺少字段 " + path);
            }
        }
    }
}
