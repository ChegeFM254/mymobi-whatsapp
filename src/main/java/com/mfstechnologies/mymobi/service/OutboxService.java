package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.OutboxEntry;
import com.mfstechnologies.mymobi.model.OutboxEntryType;
import com.mfstechnologies.mymobi.model.OutboxStatus;
import com.mfstechnologies.mymobi.session.OutboxRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * WORKSTREAM G (outbox pattern): the write-side API flow services call
 * to durably record "this needs to happen eventually".
 *
 * CRITICAL: enqueue() is deliberately NOT @Transactional itself. The
 * entire point of the outbox pattern is that the outbox row is written
 * in the SAME database transaction as the business action that triggers
 * it (e.g. saving a Loan as pending_approval + enqueueing its
 * APPROVAL_CODE_SMS, atomically - either both happen or neither does).
 * If this method opened its own transaction, that atomicity would be
 * lost. Instead, the CALLING method (in the flow service) must itself
 * be @Transactional, so Spring enlists both the repository.save(loan)
 * call and this enqueue() call in one transaction.
 */
@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Enqueues a new outbox entry. Must be called from within a method
     * annotated @Transactional alongside the business write it needs to
     * stay atomic with - see class-level note above.
     */
    public OutboxEntry enqueue(OutboxEntryType type, Object payload) {
        OutboxRetryPolicy policy = OutboxRetryPolicy.forType(type);

        OutboxEntry entry = new OutboxEntry();
        entry.setType(type);
        entry.setPayload(objectMapper.writeValueAsString(payload));
        entry.setStatus(OutboxStatus.PENDING);
        entry.setAttempts(0);
        entry.setMaxAttempts(policy.maxAttempts());
        entry.setNextAttemptAt(Instant.now());
        entry.setCreatedAt(Instant.now());

        return repository.save(entry);
    }

    /** Used by the monitoring endpoint to list entries that exhausted all retries. */
    public List<OutboxEntry> findExhausted() {
        return repository.findByStatus(OutboxStatus.EXHAUSTED);
    }
}
