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
 * enqueue() itself is NOT @Transactional - calling it alone just writes
 * one row with the repository's own default transaction, which is fine
 * on its own but does NOT give the atomicity-with-a-business-write
 * guarantee that's the entire point of the pattern.
 *
 * enqueueWithBusinessWrite() is how callers actually get that guarantee.
 * IMPORTANT Spring detail: @Transactional only works through Spring's
 * proxy, which means it does NOT apply to self-invocation (a method on
 * some bean calling another method on `this` within the same class) -
 * annotating a flow service's own private submission method with
 * @Transactional would silently do nothing. Calling
 * outboxService.enqueueWithBusinessWrite(...) instead - a call to a
 * genuinely different bean - goes through the proxy correctly, so both
 * the business write and the outbox write land in one transaction.
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
