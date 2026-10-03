package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.OutboxEntryType;

import java.time.Duration;
import java.util.Map;

/**
 * WORKSTREAM G (outbox pattern): per-type retry configuration.
 * maxAttempts and baseDelay together define exponential backoff with a
 * cap: delay = min(baseDelay * 2^(attempts-1), maxDelay). attempts=0 is
 * the very first try (no delay at all - that happens synchronously at
 * enqueue time... actually no, see OutboxDispatcher: even the first
 * attempt goes through the normal poll cycle, so "immediately" really
 * means "next poll").
 *
 * Rates chosen per type based on how time-sensitive and how long-lived
 * each kind of message is:
 *  - OTP_SMS: short attempt window (OTPs expire quickly in practice;
 *    retrying for 10+ minutes after the person gave up makes no sense).
 *  - APPROVAL_CODE_SMS: longer tail - approval codes stay valid for the
 *    life of the pending loan, so it's worth trying harder.
 *  - MPESA_STK_PUSH: fast initial retries, since the person is actively
 *    waiting for a popup right now.
 */
public record OutboxRetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    private static final Map<OutboxEntryType, OutboxRetryPolicy> POLICIES = Map.of(
            OutboxEntryType.OTP_SMS, new OutboxRetryPolicy(3, Duration.ofSeconds(10), Duration.ofMinutes(2)),
            OutboxEntryType.APPROVAL_CODE_SMS, new OutboxRetryPolicy(5, Duration.ofSeconds(10), Duration.ofMinutes(10)),
            OutboxEntryType.MPESA_STK_PUSH, new OutboxRetryPolicy(5, Duration.ofSeconds(5), Duration.ofMinutes(5))
    );

    public static OutboxRetryPolicy forType(OutboxEntryType type) {
        OutboxRetryPolicy policy = POLICIES.get(type);
        if (policy == null) {
            throw new IllegalArgumentException("No retry policy configured for outbox type: " + type);
        }
        return policy;
    }

    /**
     * Computes the delay before the next attempt, given how many
     * attempts have already been made (including the one that just
     * failed). Exponential backoff capped at maxDelay.
     */
    public Duration delayForAttempt(int attemptsSoFar) {
        long multiplier = 1L << Math.min(attemptsSoFar - 1, 20); // cap the shift to avoid overflow on pathological inputs
        Duration computed = baseDelay.multipliedBy(multiplier);
        return computed.compareTo(maxDelay) > 0 ? maxDelay : computed;
    }
}
