package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.OutboxEntry;
import com.mfstechnologies.mymobi.model.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WORKSTREAM G (outbox pattern): Spring Data JPA repository backing
 * OutboxEntry. OutboxEntry's @Id is an auto-generated UUID (unlike
 * every other repository in this codebase, which keys on a natural id),
 * so this is a JpaRepository<OutboxEntry, UUID>.
 *
 * findByStatusAndNextAttemptAtLessThanEqual is the dispatcher's main
 * query - "what's ready to attempt right now". Spring Data derives the
 * implementation from the method name; no @Query annotation needed for
 * something this simple.
 *
 * findByStatus is used both by the dispatcher (PENDING entries with no
 * next_attempt_at filter would be unusual, but the derived query stays
 * available for that) and by the monitoring endpoint (EXHAUSTED).
 */
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    List<OutboxEntry> findByStatusAndNextAttemptAtLessThanEqual(OutboxStatus status, Instant now);

    List<OutboxEntry> findByStatus(OutboxStatus status);
}
