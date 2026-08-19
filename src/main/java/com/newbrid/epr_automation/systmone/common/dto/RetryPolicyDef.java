package com.newbrid.epr_automation.systmone.common.dto;

/**
 * Retry-with-backoff policy for a step (FR-ENG-03, NFR-REL-03). {@code maxAttempts}
 * counts the total attempts (1 = no retry).
 */
public record RetryPolicyDef(
        int maxAttempts,
        long initialBackoffMs,
        double multiplier,
        long maxBackoffMs
) {
    public RetryPolicyDef {
        if (maxAttempts < 1) maxAttempts = 1;
        if (initialBackoffMs < 0) initialBackoffMs = 0;
        if (multiplier < 1.0) multiplier = 1.0;
        if (maxBackoffMs < initialBackoffMs) maxBackoffMs = initialBackoffMs;
    }

    /** Sensible default: 3 attempts, 250ms → 500ms → 1s backoff. */
    public static RetryPolicyDef defaults() {
        return new RetryPolicyDef(3, 250, 2.0, 2000);
    }

    public static RetryPolicyDef none() {
        return new RetryPolicyDef(1, 0, 1.0, 0);
    }

    /** Backoff before the given (1-based) attempt number. */
    public long backoffBeforeAttempt(int attempt) {
        if (attempt <= 1) return 0;
        double backoff = initialBackoffMs * Math.pow(multiplier, attempt - 2);
        return Math.min((long) backoff, maxBackoffMs);
    }
}
