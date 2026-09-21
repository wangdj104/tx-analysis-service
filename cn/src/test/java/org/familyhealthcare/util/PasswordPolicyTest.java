package org.familyhealthcare.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordPolicyTest {
    @Test void acceptsStrongPassword() {
        assertDoesNotThrow(() -> PasswordPolicy.requireStrong("Care@2026safe"));
    }

    @Test void rejectsShortOrSingleCategoryPassword() {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.requireStrong("short@1"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.requireStrong("onlyletters"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.requireStrong("1234567890"));
    }
}
