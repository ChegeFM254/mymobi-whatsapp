package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.StoredDocument;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentStore {

    private static final Duration TTL = Duration.ofHours(24);
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final Map<String, StoredDocument> documents = new ConcurrentHashMap<>();

    public void save(String token, StoredDocument document) {
        documents.put(token, document);
    }

    public Optional<StoredDocument> findValid(String token) {
        StoredDocument doc = documents.get(token);
        if (doc == null) {
            return Optional.empty();
        }
        if (doc.isInvalidated()) {
            return Optional.empty();
        }
        if (Duration.between(doc.getCreatedAt(), Instant.now()).compareTo(TTL) > 0) {
            documents.remove(token);
            return Optional.empty();
        }
        return Optional.of(doc);
    }

    public boolean recordFailedAttempt(String token) {
        StoredDocument doc = documents.get(token);
        if (doc == null) {
            return false;
        }
        doc.setFailedAttempts(doc.getFailedAttempts() + 1);
        if (doc.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
            doc.setInvalidated(true);
            return true;
        }
        return false;
    }

    public int getMaxFailedAttempts() {
        return MAX_FAILED_ATTEMPTS;
    }
}