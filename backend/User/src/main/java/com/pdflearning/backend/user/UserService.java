package com.pdflearning.backend.user;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * 用户业务逻辑：注册（分配 user_id）、登录、登出、令牌校验、改昵称、改密码。
 *
 * 登录令牌为 256 位随机值，数据库仅保存其 SHA-256 哈希；
 * 前端以 Authorization: Bearer &lt;token&gt; 携带。
 */
public final class UserService {
    static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,24}$");
    static final int MIN_PASSWORD_LENGTH = 6;
    static final int NICKNAME_MAX_LENGTH = 24;

    private final MySqlUserStore store;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final Duration tokenTtl;
    private final RandomGenerator random;

    public UserService(MySqlUserStore store, Duration tokenTtl) {
        this(store, new PasswordHasher(), Clock.systemUTC(), tokenTtl, new SecureRandom());
    }

    UserService(
            MySqlUserStore store,
            PasswordHasher hasher,
            Clock clock,
            Duration tokenTtl,
            RandomGenerator random) {
        this.store = Objects.requireNonNull(store, "store");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("tokenTtl 必须为正时长");
        }
        this.tokenTtl = tokenTtl;
        this.random = Objects.requireNonNull(random, "random");
    }

    public record AuthResult(String token, UserRecord user) {
    }

    /** 注册并直接登录：校验输入 → 分配 user_id → 存储哈希 → 发放令牌。 */
    public AuthResult register(String username, String password, String nickname) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new UserServiceException(400, "用户名需为 3-24 位字母、数字或下划线");
        }
        validatePassword(password);
        var resolvedNickname = resolveNickname(nickname, username);
        var now = now();
        var user = new UserRecord(
                UserIdentity.newUserId(clock, random),
                username,
                resolvedNickname,
                hasher.hash(password),
                now,
                now);
        store.insertUser(user);
        return new AuthResult(issueToken(user.userId()), user.withoutSecrets());
    }

    public AuthResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new UserServiceException(400, "用户名和密码不能为空");
        }
        var user = store.findByUsername(username)
                .orElseThrow(() -> new UserServiceException(401, "用户名或密码错误"));
        if (!hasher.verify(password, user.passwordHash())) {
            throw new UserServiceException(401, "用户名或密码错误");
        }
        return new AuthResult(issueToken(user.userId()), user.withoutSecrets());
    }

    /** 解析 Bearer 令牌对应的当前用户；无效或过期返回 401。 */
    public UserRecord authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new UserServiceException(401, "缺少登录令牌");
        }
        return store.findUserByTokenHash(tokenHash(token), now())
                .orElseThrow(() -> new UserServiceException(401, "登录已过期，请重新登录"));
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        store.deleteToken(tokenHash(token));
    }

    public UserRecord updateNickname(String userId, String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new UserServiceException(400, "昵称不能为空");
        }
        var trimmed = nickname.strip();
        if (trimmed.length() > NICKNAME_MAX_LENGTH) {
            throw new UserServiceException(400, "昵称需为 1-24 个字符");
        }
        store.updateNickname(userId, trimmed, now());
        return store.findByUserId(userId)
                .orElseThrow(() -> new UserServiceException(404, "用户不存在"))
                .withoutSecrets();
    }

    /** 修改密码；成功后吊销该用户其余会话，仅保留当前令牌。 */
    public void changePassword(String userId, String currentToken, String oldPassword, String newPassword) {
        var user = store.findByUserId(userId)
                .orElseThrow(() -> new UserServiceException(404, "用户不存在"));
        if (oldPassword == null || oldPassword.isBlank() || !hasher.verify(oldPassword, user.passwordHash())) {
            throw new UserServiceException(400, "旧密码不正确");
        }
        validatePassword(newPassword);
        store.updatePasswordHash(userId, hasher.hash(newPassword), now());
        store.deleteTokensForUserExcept(userId, tokenHash(currentToken));
    }

    // ---------- 内部工具 ----------

    private String issueToken(String userId) {
        var token = randomToken();
        store.insertToken(tokenHash(token), userId, now().plus(tokenTtl));
        return token;
    }

    private String randomToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String tokenHash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new UserServiceException(400, "密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }

    private static String resolveNickname(String nickname, String fallback) {
        if (nickname == null || nickname.isBlank()) {
            return fallback;
        }
        var trimmed = nickname.strip();
        if (trimmed.isEmpty() || trimmed.length() > NICKNAME_MAX_LENGTH) {
            throw new UserServiceException(400, "昵称需为 1-24 个字符");
        }
        return trimmed;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
