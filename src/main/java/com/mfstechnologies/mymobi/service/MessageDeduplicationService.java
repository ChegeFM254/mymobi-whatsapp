package com.mfstechnologies.mymobi.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks WhatsApp message IDs already processed, so a redelivered webhook
 * event (Meta retries these under certain network conditions) never gets
 * handled twice — e.g. never sends a duplicate reply for the same tap.
 *
 * Direct equivalent of isDuplicateMessage() / processedMessageIds from the
 * Node.js version, including the same cleanup approach: entries older
 * than RETENTION are removed opportunistically whenever a new ID comes
 * in, rather than on a separate timer — since this only grows when
 * there's actual traffic, there's no "quiet period drift" risk that
 * would need a different cleanup strategy (see the equivalent comment
 * in the Node version's recipientQueues, which DID need a timer for a
 * different reason).
 */
@Service
public class MessageDeduplicationService {

    private static final Duration RETENTION = Duration.ofMinutes(10);

    private final Map<String, Instant> processedMessageIds = new ConcurrentHashMap<>();

    /**
     * @return true if this message ID has already been processed (i.e. this
     *         call is a duplicate delivery and should be ignored), false if
     *         this is the first time this ID has been seen (and it's now
     *         recorded, so a second call with the same ID will return true)
     */
    public boolean isDuplicate(String messageId) {
        if (messageId == null) {
            return false; // can't dedupe without an id — let it through, matching the Node version
        }

        Instant now = Instant.now();
        cleanupExpiredEntries(now);

        // putIfAbsent is atomic: if it returns a non-null previous value,
        // this ID was already present (duplicate). If it returns null, we
        // just inserted it for the first time (not a duplicate).
        Instant previouslySeenAt = processedMessageIds.putIfAbsent(messageId, now);
        return previouslySeenAt != null;
    }

    private void cleanupExpiredEntries(Instant now) {
        processedMessageIds.entrySet().removeIf(
                entry -> Duration.between(entry.getValue(), now).compareTo(RETENTION) > 0
        );
    }
}