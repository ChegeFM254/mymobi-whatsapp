package com.mfstechnologies.mymobi.controller;

import com.mfstechnologies.mymobi.model.OutboxEntry;

import java.time.Instant;
import java.util.UUID;

/**
 * WORKSTREAM G (outbox pattern): a plain response shape for the
 * monitoring endpoint, rather than serializing OutboxEntry directly -
 * keeps the JPA entity itself decoupled from the JSON wire format, and
 * gives an explicit, stable field list independent of the entity's own
 * structure.
 */
public record OutboxEntryView(
        UUID id,
        String type,
        String payload,
        int attempts,
        int maxAttempts,
        String lastError,
        Instant createdAt
) {
    public static OutboxEntryView from(OutboxEntry entry) {
        return new OutboxEntryView(
                entry.getId(),
                entry.getType().name(),
                entry.getPayload(),
                entry.getAttempts(),
                entry.getMaxAttempts(),
                entry.getLastError(),
                entry.getCreatedAt()
        );
    }
}
