package org.familyhealthcare.util;

/** Shared password rules for account creation, reset, and self-service changes. */
public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static void requireStrong(String password) {
        if (password == null || password.length() < 10 || password.length() > 128) {
            throw new IllegalArgumentException("Password must be 10 to 128 characters long");
        }
        boolean letter = false;
        boolean digit = false;
        boolean symbol = false;
        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) letter = true;
            else if (Character.isDigit(c)) digit = true;
            else if (!Character.isWhitespace(c)) symbol = true;
        }
        if (!letter || !digit || !symbol) {
            throw new IllegalArgumentException("Password must contain letters, numbers, and special characters");
        }
    }
}
