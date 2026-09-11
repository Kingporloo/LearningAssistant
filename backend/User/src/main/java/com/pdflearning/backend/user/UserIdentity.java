package com.pdflearning.backend.user;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;

/**
 * 用户与登录令牌的标识符分配。沿用 DevIdentity 的时间戳 + 随机后缀风格。
 */
public final class UserIdentity {
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final int RANDOM_SUFFIX_CHARS = 8;

    private UserIdentity() {
    }

    /** 形如 user_20260911_153040_1a2b3c4d，秒级时间戳 + 8 位随机十六进制保证唯一性。 */
    public static String newUserId() {
        return newUserId(Clock.systemDefaultZone(), RandomGenerator.getDefault());
    }

    static String newUserId(Clock clock, RandomGenerator random) {
        return "user_" + LocalDateTime.now(clock).format(ID_TIME) + "_" + randomHex(random);
    }

    static String randomHex(RandomGenerator random) {
        var builder = new StringBuilder(RANDOM_SUFFIX_CHARS);
        for (int i = 0; i < RANDOM_SUFFIX_CHARS; i++) {
            builder.append(Character.forDigit(random.nextInt(16), 16));
        }
        return builder.toString();
    }
}
