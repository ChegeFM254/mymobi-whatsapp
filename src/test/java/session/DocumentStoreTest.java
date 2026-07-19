package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.StoredDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStoreTest {

    private StoredDocument freshDocument() {
        StoredDocument doc = new StoredDocument();
        doc.setPhoneNumber("254700000001");
        doc.setDocType("payslip");
        doc.setUpn("12345");
        doc.setHtml("<html></html>");
        doc.setCreatedAt(Instant.now());
        return doc;
    }

    @Test
    void freshlySavedDocumentIsFindable() {
        DocumentStore store = new DocumentStore();
        store.save("token1", freshDocument());

        assertThat(store.findValid("token1")).isPresent();
    }

    @Test
    void unknownTokenIsNotFound() {
        DocumentStore store = new DocumentStore();
        assertThat(store.findValid("does-not-exist")).isEmpty();
    }

    @Test
    void documentOlderThan24HoursIsTreatedAsExpired() {
        DocumentStore store = new DocumentStore();
        StoredDocument old = freshDocument();
        old.setCreatedAt(Instant.now().minus(25, ChronoUnit.HOURS));
        store.save("token2", old);

        assertThat(store.findValid("token2")).isEmpty();
    }

    @Test
    void documentWithin24HoursIsStillValid() {
        DocumentStore store = new DocumentStore();
        StoredDocument recent = freshDocument();
        recent.setCreatedAt(Instant.now().minus(23, ChronoUnit.HOURS));
        store.save("token3", recent);

        assertThat(store.findValid("token3")).isPresent();
    }

    @Test
    void fifthFailedAttemptInvalidatesTheLink() {
        DocumentStore store = new DocumentStore();
        store.save("token4", freshDocument());

        boolean invalidated = false;
        for (int i = 0; i < 5; i++) {
            invalidated = store.recordFailedAttempt("token4");
        }

        assertThat(invalidated).isTrue();
        assertThat(store.findValid("token4")).isEmpty();
    }

    @Test
    void fewerThanFiveFailedAttemptsDoesNotInvalidate() {
        DocumentStore store = new DocumentStore();
        store.save("token5", freshDocument());

        store.recordFailedAttempt("token5");
        store.recordFailedAttempt("token5");

        assertThat(store.findValid("token5")).isPresent();
    }
}