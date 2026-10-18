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
 * findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc is the
 * dispatcher's main query - "what's ready to attempt right now,
 * oldest first". The explicit ordering matters: without it the database
 * is free to return due entries in any order, so two entries for the
 * same person that were enqueued in a particular sequence could be
 * dispatched in the opposite one. Spring Data derives the implementation
 * from the method name; no @Query annotation needed.
 *
 * findByStatus is used by the monitoring endpoint (EXHAUSTED).
 */
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    List<OutboxEntry> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(OutboxStatus status, Instant now);

    List<OutboxEntry> findByStatus(OutboxStatus status);
}
