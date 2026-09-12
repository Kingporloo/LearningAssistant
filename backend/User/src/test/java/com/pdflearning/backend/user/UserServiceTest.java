package com.pdflearning.backend.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdflearning.backend.dataport.UserDataPort;
import java.time.Clock;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserServiceTest {
    private UserDataPort store;
    private UserService service;

    @BeforeEach
    void setUp() {
        store = UserTestDatabase.create();
        service = new UserService(
                store, new PasswordHasher(), Clock.systemUTC(), Duration.ofDays(7), new Random(7));
    }

    @AfterEach
    void tearDown() {
        store = null;
        service = null;
    }

    @Test
    void registerAssignsIdHashesPasswordAndReturnsToken() {
        var result = service.register("alice", "secret123", "爱丽丝");

        assertTrue(result.token().length() >= 32, "应返回足够的随机令牌");
        assertTrue(result.user().userId().startsWith("user_"));
        assertEquals("alice", result.user().username());
        assertEquals("爱丽丝", result.user().nickname());
        // 数据库保存的是哈希而不是明文
        var stored = store.findByUsername("alice").orElseThrow();
        assertNotEquals("secret123", stored.passwordHash());
        assertTrue(stored.passwordHash().startsWith("pbkdf2_sha256$"));
    }

    @Test
    void registerDefaultsNicknameToUsername() {
        assertEquals("bob", service.register("bob", "secret123", null).user().nickname());
        // 空白昵称与未提供等价（前端空输入不会提交）
        assertEquals("bob2", service.register("bob2", "secret123", "   ").user().nickname());
    }

    @Test
    void registerRejectsInvalidInput() {
        assertInvalid(() -> service.register("ab", "secret123", null), "用户名");
        assertInvalid(() -> service.register("中文名字", "secret123", null), "用户名");
        assertInvalid(() -> service.register("carol", "12345", null), "密码");
        assertInvalid(() -> service.register("carol", "      ", null), "密码");
        assertInvalid(() -> service.register("carol", "secret123", "x".repeat(25)), "昵称");
    }

    @Test
    void registerRejectsDuplicateUsername() {
        service.register("dave", "secret123", null);
        var exception = assertThrows(UserServiceException.class,
                () -> service.register("dave", "other456", null));
        assertEquals(409, exception.statusCode());
    }

    @Test
    void loginSucceedsWithCorrectPasswordAndFailsOtherwise() {
        service.register("erin", "secret123", null);

        var result = service.login("erin", "secret123");
        assertEquals("erin", result.user().username());

        var wrong = assertThrows(UserServiceException.class,
                () -> service.login("erin", "wrong-password"));
        assertEquals(401, wrong.statusCode());

        var unknown = assertThrows(UserServiceException.class,
                () -> service.login("ghost", "secret123"));
        assertEquals(401, unknown.statusCode());
    }

    @Test
    void authenticateResolvesValidTokenAndRejectsForeignValues() {
        var token = service.register("frank", "secret123", null).token();

        var user = service.authenticate(token);
        assertEquals("frank", user.username());

        assertEquals(401, statusCodeOf(() -> service.authenticate("not-a-token")));
        assertEquals(401, statusCodeOf(() -> service.authenticate(null)));
    }

    @Test
    void expiredTokenIsRejected() {
        var shortLived = new UserService(
                store, new PasswordHasher(), Clock.systemUTC(), Duration.ofMillis(50), new Random(1));
        var token = shortLived.register("grace", "secret123", null).token();

        shortLived.authenticate(token);

        assertThrows(UserServiceException.class, () -> {
            Thread.sleep(120);
            shortLived.authenticate(token);
        });
    }

    @Test
    void logoutRevokesToken() {
        var token = service.register("heidi", "secret123", null).token();
        service.authenticate(token);

        service.logout(token);

        assertEquals(401, statusCodeOf(() -> service.authenticate(token)));
        // 重复登出幂等
        service.logout(token);
    }

    @Test
    void updateNicknamePersistsAndValidates() {
        var token = service.register("ivan", "secret123", null).token();
        var user = service.authenticate(token);

        var updated = service.updateNickname(user.userId(), "新昵称");
        assertEquals("新昵称", updated.nickname());
        assertEquals("新昵称", service.authenticate(token).nickname());

        assertInvalid(() -> service.updateNickname(user.userId(), "x".repeat(25)), "昵称");
        assertInvalid(() -> service.updateNickname(user.userId(), "  "), "昵称");
    }

    @Test
    void changePasswordRequiresOldPasswordAndRevokesOtherSessions() {
        var firstToken = service.register("judy", "secret123", null).token();
        var secondToken = service.login("judy", "secret123").token();

        assertInvalid(() -> service.changePassword(
                service.authenticate(firstToken).userId(), firstToken, "wrong-old", "newpass456"),
                "旧密码");
        assertInvalid(() -> service.changePassword(
                service.authenticate(firstToken).userId(), firstToken, "secret123", "      "),
                "密码");

        service.changePassword(service.authenticate(firstToken).userId(), firstToken, "secret123", "newpass456");

        // 当前会话仍有效，其余会话被吊销
        service.authenticate(firstToken);
        assertEquals(401, statusCodeOf(() -> service.authenticate(secondToken)));
        // 旧密码失效、新密码可登录
        assertEquals(401, statusCodeOf(() -> service.login("judy", "secret123")));
        assertEquals("judy", service.login("judy", "newpass456").user().username());
    }

    private static void assertInvalid(Runnable action, String keyword) {
        var exception = assertThrows(UserServiceException.class, action::run);
        assertEquals(400, exception.statusCode());
        assertTrue(exception.getMessage().contains(keyword), () -> "应提示 " + keyword);
    }

    private static int statusCodeOf(Runnable action) {
        try {
            action.run();
            throw new AssertionError("应当抛出 UserServiceException");
        } catch (UserServiceException exception) {
            return exception.statusCode();
        }
    }
}
