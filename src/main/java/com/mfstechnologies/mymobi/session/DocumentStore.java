package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.StoredDocument;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Generated-document storage (payslips, loan statements, loan clearance
 * letters), keyed by a one-time access token rather than phone number.
 *
 * WORKSTREAM C (persistence): now backed by Postgres via
 * DocumentRepository, rather than an in-memory ConcurrentHashMap. The
 * public method signatures are UNCHANGED from the in-memory version on
 * purpose: nothing calling this class needs to change.
 *
 * recordFailedAttempt() reads, mutates, then explicitly calls
 * repository.save(doc) again to persist the change - findById() returns
 * a detached entity outside any transaction boundary here, so mutating
 * the returned object alone would NOT be written back without this
 * explicit save call.
 *
 * save(token, document) sets token onto the entity itself before
 * persisting, since the in-memory version's callers never needed to set
 * that field themselves (it was only ever the external Map key before).
 */
@Service
public class DocumentStore {

    private static final Duration TTL = Duration.ofHours(24);
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final DocumentRepository repository;

    public DocumentStore(DocumentRepository repository) {
        this.repository = repository;
    }

    public void save(String token, StoredDocument document) {
        document.setToken(token);
        repository.save(document);
    }

    public Optional<StoredDocument> findValid(String token) {
        Optional<StoredDocument> docOpt = repository.findById(token);
        if (docOpt.isEmpty()) {
            return Optional.empty();
        }
        StoredDocument doc = docOpt.get();
        if (doc.isInvalidated()) {
            return Optional.empty();
        }
        if (Duration.between(doc.getCreatedAt(), Instant.now()).compareTo(TTL) > 0) {
            repository.deleteById(token);
            return Optional.empty();
        }
        return Optional.of(doc);
    }

    public boolean recordFailedAttempt(String token) {
        Optional<StoredDocument> docOpt = repository.findById(token);
        if (docOpt.isEmpty()) {
            return false;
        }
        StoredDocument doc = docOpt.get();
        doc.setFailedAttempts(doc.getFailedAttempts() + 1);
        boolean nowInvalidated = doc.getFailedAttempts() >= MAX_FAILED_ATTEMPTS;
        if (nowInvalidated) {
            doc.setInvalidated(true);
        }
        repository.save(doc);
        return nowInvalidated;
    }

    public int getMaxFailedAttempts() {
        return MAX_FAILED_ATTEMPTS;
    }
}
