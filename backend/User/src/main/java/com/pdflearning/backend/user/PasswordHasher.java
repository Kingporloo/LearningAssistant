package com.pdflearning.backend.user;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.random.RandomGenerator;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 密码哈希：PBKDF2WithHmacSHA256（JDK 内置，无第三方依赖）。
 *
 * 存储格式：pbkdf2_sha256$&lt;iterations&gt;$&lt;saltBase64Url&gt;$&lt;hashBase64Url&gt;
 * 每个用户独立随机盐，校验使用恒定时间比较。
 */
public final class PasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2_sha256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final RandomGenerator random;

    public PasswordHasher() {
        this(new SecureRandom());
    }

    PasswordHasher(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public String hash(String password) {
        var salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        var hash = pbkdf2(password, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS
                + "$" + Base64.getUrlEncoder().withoutPadding().encodeToString(salt)
                + "$" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    public boolean verify(String password, String stored) {
        Objects.requireNonNull(stored, "stored");
        var parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            return false;
        }
        if (iterations < 1) {
            return false;
        }
        try {
            var salt = Base64.getUrlDecoder().decode(parts[2]);
            var expected = Base64.getUrlDecoder().decode(parts[3]);
            var actual = pbkdf2(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("JVM 不支持 " + ALGORITHM, exception);
        }
    }
}
