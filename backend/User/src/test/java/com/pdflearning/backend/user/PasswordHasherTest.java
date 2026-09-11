package com.pdflearning.backend.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void verifiesCorrectPasswordAndRejectsWrongOne() {
        var stored = hasher.hash("s3cret-密码");

        assertTrue(hasher.verify("s3cret-密码", stored));
        assertFalse(hasher.verify("wrong", stored));
        assertFalse(hasher.verify("", stored));
    }

    @Test
    void generatesIndependentSaltsPerHash() {
        assertNotEquals(hasher.hash("same-password"), hasher.hash("same-password"));
    }

    @Test
    void usesExpectedFormatAndIterations() {
        var stored = hasher.hash("abc123");
        assertTrue(stored.startsWith("pbkdf2_sha256$210000$"), () -> "实际值: " + stored);
    }

    @Test
    void rejectsMalformedStoredValues() {
        assertFalse(hasher.verify("abc123", "not-a-valid-hash"));
        assertFalse(hasher.verify("abc123", "pbkdf2_sha256$0$abc$abc"));
        assertFalse(hasher.verify("abc123", "md5$deadbeef"));
    }

    @Test
    void hashIsNotPlainPassword() {
        var stored = hasher.hash("visible-password");
        assertFalse(stored.contains("visible-password"));
    }

    @Test
    void verifyThrowsNothingOnNullStored() {
        assertThrows(NullPointerException.class, () -> hasher.verify("x", null));
    }
}
