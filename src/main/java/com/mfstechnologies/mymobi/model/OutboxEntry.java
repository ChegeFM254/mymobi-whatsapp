package com.mfstechnologies.mymobi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * WORKSTREAM G (outbox pattern): a single durable unit of "work that
 * must eventually happen" - an SMS to send, an STK push to trigger.
 * Written in the SAME database transaction as the business action that
 * triggers it (e.g. saving a Loan as pending_approval + writing the
 * APPROVAL_CODE_SMS entry, atomically) - this is the entire point of
 * the pattern: a crash can lose an in-memory timer, but never this row.
 *
 * Unlike every other entity in this codebase (RegisteredUser, Loan,
 * StoredDocument), which key on a natural id (phone number, token), this
 * is the first entity needing a surrogate key - there's no natural
 * unique identifier for "this particular send attempt", so an
 * auto-generated UUID is used instead. Hibernate 7 generates these
 * natively via GenerationType.UUID, no extra configuration needed.
 *
 * payload is a JSON string (shape depends on type - see
 * OutboxEntryType's per-constant documentation) rather than a wide
 * table of nullable typed columns, since the three current types have
 * genuinely different shapes and the codebase already leans on Jackson
 * elsewhere (IncomingMessage, SessionStore).
 */
@Entity
@Table(name = "outbox_entries")
public class OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private OutboxEntryType type;

    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempts")
    private int attempts = 0;

    @Column(name = "max_attempts")
    private int maxAttempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public OutboxEntry() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public OutboxEntryType getType() { return type; }
    public void setType(OutboxEntryType type) { this.type = type; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public OutboxStatus getStatus() { return status; }
    public void setStatus(OutboxStatus status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
