package com.pdflearning.backend.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Random;
import org.junit.jupiter.api.Test;

class UserIdentityTest {
    @Test
    void createsDeterministicUserIdFromClockAndRandom() {
        var clock = Clock.fixed(Instant.parse("2026-09-11T07:30:40Z"), ZoneId.of("UTC"));

        var first = UserIdentity.newUserId(clock, new Random(42));
        var second = UserIdentity.newUserId(clock, new Random(42));

        assertEquals(first, second, "相同种子应生成相同 user_id");
        assertTrue(first.matches("^user_20260911_073040_[0-9a-f]{8}$"), () -> "实际值: " + first);
    }

    @Test
    void realIdsMatchExpectedShape() {
        var id = UserIdentity.newUserId();
        assertTrue(id.matches("^user_\\d{8}_\\d{6}_[0-9a-f]{8}$"), () -> "实际值: " + id);
    }
}
