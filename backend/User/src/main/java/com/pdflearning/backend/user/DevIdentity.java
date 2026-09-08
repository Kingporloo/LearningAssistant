package com.pdflearning.backend.user;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class DevIdentity {
    public static final String USER_ID = "dev_user";

    private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private DevIdentity() {
    }

    public static String newSessionId() {
        return newSessionId(Clock.systemDefaultZone(), USER_ID);
    }

    static String newSessionId(Clock clock, String userId) {
        Objects.requireNonNull(clock, "clock");
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return "session_" + LocalDateTime.now(clock).format(SESSION_TIME) + "_" + userId;
    }
}
