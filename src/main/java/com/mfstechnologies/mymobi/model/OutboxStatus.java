package com.mfstechnologies.mymobi.model;

/**
 * WORKSTREAM G (outbox pattern): lifecycle of a single OutboxEntry.
 *
 * PENDING -> PROCESSING -> COMPLETED (success)
 * PENDING -> PROCESSING -> PENDING (failure, attempts remain - retried later)
 * PENDING -> PROCESSING -> EXHAUSTED (failure, no attempts remain)
 */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    EXHAUSTED
}
