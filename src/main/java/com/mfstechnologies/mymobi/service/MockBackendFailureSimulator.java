package com.mfstechnologies.mymobi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * WORKSTREAM G (outbox pattern): lets the mock backend services fail a
 * configurable fraction of their calls, so the outbox's retry,
 * backoff and exhaustion behavior can be watched working on a live
 * deployment. Without it the mocks always succeed, so those paths would
 * only ever be exercised by unit tests, and the exhausted-entries
 * endpoint could never show anything.
 *
 * Controlled by app.mock-backend-failure-rate (env var
 * MOCK_BACKEND_FAILURE_RATE), a number from 0.0 (never fail, the
 * default) to 1.0 (always fail). Anything outside that range is
 * rejected at startup rather than silently clamped, so a typo like "50"
 * for "50%" fails loudly instead of quietly behaving as "always fail".
 *
 * Only used by MockLoanService and MockCustomerService, and goes away
 * with them when real backend integrations replace the mocks.
 */
@Service
public class MockBackendFailureSimulator {

    private final double failureRate;

    public MockBackendFailureSimulator(@Value("${app.mock-backend-failure-rate:0.0}") double failureRate) {
        if (failureRate < 0.0 || failureRate > 1.0) {
            throw new IllegalArgumentException(
                    "app.mock-backend-failure-rate must be between 0.0 and 1.0, but was " + failureRate);
        }
        this.failureRate = failureRate;
    }

    /** Throws with probability failureRate; otherwise does nothing. */
    public void maybeFail(String operation) {
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new IllegalStateException("Simulated backend failure during " + operation);
        }
    }
}
