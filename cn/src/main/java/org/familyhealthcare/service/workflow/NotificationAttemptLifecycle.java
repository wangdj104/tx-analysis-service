package org.familyhealthcare.service.workflow;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Persisted attempt transitions only; callers retain their own locks, eligibility and send gates. */
public final class NotificationAttemptLifecycle {
    private static final long LEASE_SECONDS = 300;

    private NotificationAttemptLifecycle() { }

    public enum Kind { START, RECOVER, COMPLETE }

    /** Immutable persisted snapshot, never an authority or a new claim. */
    public static final class Attempt {
        private final long id;
        private final String status, claimToken, requestId, lastResult;
        private final Instant claimedAt;
        private final int attemptCount;

        public Attempt(long id, String status, String claimToken, Instant claimedAt,
                       String requestId, int attemptCount, String lastResult) {
            this.id = id; this.status = status; this.claimToken = claimToken; this.claimedAt = claimedAt;
            this.requestId = requestId; this.attemptCount = attemptCount; this.lastResult = lastResult;
        }
        public long getId() { return id; }
        public String getStatus() { return status; }
        public String getClaimToken() { return claimToken; }
        public Instant getClaimedAt() { return claimedAt; }
        public String getRequestId() { return requestId; }
        public int getAttemptCount() { return attemptCount; }
        public String getLastResult() { return lastResult; }
    }

    public static final class Outcome {
        private final String status;
        private final boolean retryable;

        public Outcome(String status, boolean retryable) {
            if (!Arrays.asList("DELIVERED", "NO_CHANNEL", "FAILED", "UNKNOWN", "SUPPRESSED").contains(status))
                throw new IllegalArgumentException("Invalid notification outcome.");
            this.status = status; this.retryable = retryable;
        }
        public String getStatus() { return status; }
        public boolean isRetryable() { return retryable; }
    }

    /**
     * Field mask: START writes request/count/result/updated; RECOVER writes status/next/result/updated
     * and clears claim token/time; COMPLETE writes status/next/result/delivered/updated and clears
     * only the token. Fields outside a kind's mask must not be written, including null values.
     */
    public static final class Change {
        private final Kind kind;
        private final String status, requestId, lastResult;
        private final int attemptCount;
        private final Instant nextAttemptAt, deliveredAt, updatedAt;

        private Change(Kind kind, String status, String requestId, int attemptCount, String lastResult,
                       Instant nextAttemptAt, Instant deliveredAt, Instant updatedAt) {
            this.kind = kind; this.status = status; this.requestId = requestId; this.attemptCount = attemptCount;
            this.lastResult = lastResult; this.nextAttemptAt = nextAttemptAt;
            this.deliveredAt = deliveredAt; this.updatedAt = updatedAt;
        }
        public Kind getKind() { return kind; }
        public String getStatus() { return status; }
        public String getRequestId() { return requestId; }
        public int getAttemptCount() { return attemptCount; }
        public String getLastResult() { return lastResult; }
        public Instant getNextAttemptAt() { return nextAttemptAt; }
        public Instant getDeliveredAt() { return deliveredAt; }
        public Instant getUpdatedAt() { return updatedAt; }
    }

    /** Fixed domain SQL inside the caller's transaction; false means the expected row no longer owns it. */
    @FunctionalInterface
    public interface Store {
        boolean apply(Attempt expected, Change change);
    }

    public static Attempt markStarted(Attempt current, String anchoredRequestId, Instant now, Store store) {
        Objects.requireNonNull(now, "now");
        if (!"CLAIMED".equals(current.getStatus()) || current.getClaimToken() == null) return null;
        NotificationAttemptPolicy.firstAttempt(anchoredRequestId);
        int count = current.getAttemptCount() + 1;
        Change change = new Change(Kind.START, "CLAIMED", anchoredRequestId, count, "ATTEMPT_STARTED", null, null, now);
        if (!store.apply(current, change)) return null;
        return new Attempt(current.getId(), current.getStatus(), current.getClaimToken(), current.getClaimedAt(),
                anchoredRequestId, count, "ATTEMPT_STARTED");
    }

    public static boolean recoverClaim(Attempt current, Instant now, Store store) {
        Objects.requireNonNull(now, "now");
        if (!"CLAIMED".equals(current.getStatus()) || current.getClaimedAt() == null
                || current.getClaimedAt().plusSeconds(LEASE_SECONDS).isAfter(now)) return false;
        String status = "ATTEMPT_STARTED".equals(current.getLastResult()) ? "UNKNOWN" : "QUEUED";
        return store.apply(current, new Change(Kind.RECOVER, status, current.getRequestId(), current.getAttemptCount(),
                status, "QUEUED".equals(status) ? now : null, null, now));
    }

    public static boolean complete(Attempt expected, Outcome outcome, Instant now, Store store) {
        Objects.requireNonNull(now, "now");
        if (!"CLAIMED".equals(expected.getStatus()) || expected.getClaimToken() == null) return false;
        String status = outcome.getStatus();
        Instant next = NotificationAttemptPolicy.nextAttempt(expected.getRequestId(), status, outcome.isRetryable(), expected.getAttemptCount());
        String result = "FAILED".equals(status)
                ? outcome.isRetryable() ? "FAILED_RETRYABLE" : "FAILED_MANUAL_RETRY_REQUIRED" : status;
        return store.apply(expected, new Change(Kind.COMPLETE, status, expected.getRequestId(), expected.getAttemptCount(),
                result, next, "DELIVERED".equals(status) ? now : null, now));
    }
}
