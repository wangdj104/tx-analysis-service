package org.familyhealthcare.service.workflow;

import java.time.Instant;
import java.util.UUID;

/** Three total attempts, anchored to a persisted UUID:millisecond cycle; UNKNOWN never retries. */
public final class NotificationAttemptPolicy {
    private NotificationAttemptPolicy() { }
    public static Instant firstAttempt(String requestId) {
        try {
            String[] parts = requestId.split(":");
            if (parts.length != 2 || !UUID.fromString(parts[0]).toString().equals(parts[0])
                    || !Long.toString(Long.parseLong(parts[1])).equals(parts[1])) throw new IllegalArgumentException();
            return Instant.ofEpochMilli(Long.parseLong(parts[1]));
        } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid notification cycle identity."); }
    }
    public static Instant nextAttempt(String requestId, String outcome, boolean retryable, int attempts) {
        return "FAILED".equals(outcome) && retryable && attempts < 3
                ? firstAttempt(requestId).plusSeconds(attempts == 1 ? 60 : 300) : null;
    }
}
