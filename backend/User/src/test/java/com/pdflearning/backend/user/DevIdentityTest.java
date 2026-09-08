package com.pdflearning.backend.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DevIdentityTest {
    @Test
    void createsRequestedDevelopmentSessionId() {
        var clock = Clock.fixed(Instant.parse("2026-09-07T11:22:33Z"), ZoneId.of("Asia/Shanghai"));

        assertEquals(
                "session_20260907_192233_dev_user",
                DevIdentity.newSessionId(clock, DevIdentity.USER_ID));
    }
}
