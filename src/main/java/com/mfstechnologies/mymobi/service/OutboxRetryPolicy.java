package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.OutboxEntryType;

import java.time.Duration;
import java.util.Map;

/**
 * WORKSTREAM G (outbox pattern): per-type retry configuration.
 * maxAttempts and baseDelay together define exponential backoff with a
 * cap: delay = min(baseDelay * 2^(attempts-1), maxDelay), where attempts
 * counts tries made so far, including the one that just failed. Even the
 * very first try waits for the dispatcher's next poll rather than
 * happening at enqueue time.
 *
 * Settings per kind of message, based on how time-sensitive and how
 * long-lived each is:
 *  - OTP_SMS: short attempt window (OTPs expire quickly in practice;
 *    retrying for 10+ minutes after the person gave up makes no sense).
 *  - APPROVAL_CODE_SMS: longer tail - approval codes stay valid for the
 *    life of the pending loan, so it's worth trying harder.
 *  - MPESA_STK_PUSH: fast initial retries, since the person is actively
 *    waiting for a popup right now.
 *  - Backend requests (LOAN_* and CUSTOMER_*): nobody is waiting on
 *    these in real time, but they must eventually land, so they retry 8
 *    times over roughly 30 minutes (15s, 30s, 1m, 2m, 4m, 8m, then 15m)
 *    before giving up and surfacing in the exhausted-entries endpoint.
 */
public record OutboxRetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    private static final OutboxRetryPolicy BACKEND_REQUEST =
            new OutboxRetryPolicy(8, Duration.ofSeconds(15), Duration.ofMinutes(15));

    private static final Map<OutboxEntryType, OutboxRetryPolicy> POLICIES = Map.of(
            OutboxEntryType.OTP_SMS, new OutboxRetryPolicy(3, Duration.ofSeconds(10), Duration.ofMinutes(2)),
            OutboxEntryType.APPROVAL_CODE_SMS, new OutboxRetryPolicy(5, Duration.ofSeconds(10), Duration.ofMinutes(10)),
            OutboxEntryType.MPESA_STK_PUSH, new OutboxRetryPolicy(5, Duration.ofSeconds(5), Duration.ofMinutes(5)),
            OutboxEntryType.LOAN_APPLICATION_SUBMIT, BACKEND_REQUEST,
            OutboxEntryType.LOAN_APPROVAL, BACKEND_REQUEST,
            OutboxEntryType.LOAN_REPAYMENT_RECORD, BACKEND_REQUEST,
            OutboxEntryType.CUSTOMER_CREATE, BACKEND_REQUEST,
            OutboxEntryType.CUSTOMER_PIN_RESET, BACKEND_REQUEST
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
