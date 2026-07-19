package com.mfstechnologies.mymobi.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NOTE: these tests exercise real timing (MIN_GAP_MS = 3000ms internally),
 * so this test class genuinely takes several seconds to run — that's
 * expected, not a hang. Kept deliberately few and focused given that cost.
 *
 * Same caveat as everywhere else in this rewrite: written carefully, but
 * not yet actually run — this is exactly the kind of concurrency-timing
 * test that's most valuable to run for real as soon as this compiles.
 */
class MessageDispatchQueueTest {

    private record TimedSend(String label, long atMillis) {}

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void messagesToTheSameRecipientSendInOrderWithSpacingBetweenChainedSends() throws Exception {
        MessageDispatchQueue queue = new MessageDispatchQueue();
        List<TimedSend> sent = new CopyOnWriteArrayList<>();
        String recipient = "254700000001";

        queue.resetTurn(recipient); // start of a fresh incoming-message "turn"

        CompletableFuture<Void> first = queue.enqueue(recipient, () -> recordSend(sent, "first"));
        CompletableFuture<Void> second = queue.enqueue(recipient, () -> recordSend(sent, "second"));

        CompletableFuture.allOf(first, second).get(15, TimeUnit.SECONDS);

        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).label()).isEqualTo("first");
        assertThat(sent.get(1).label()).isEqualTo("second");

        long gap = sent.get(1).atMillis() - sent.get(0).atMillis();
        // "first" is the first message of the turn -> sends immediately.
        // "second" is chained -> should be paced by ~3000ms (MIN_GAP_MS).
        assertThat(gap).isGreaterThanOrEqualTo(2900); // small tolerance
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void firstMessageOfANewTurnSendsImmediatelyEvenSoonAfterAPreviousTurn() throws Exception {
        MessageDispatchQueue queue = new MessageDispatchQueue();
        String recipient = "254700000002";

        queue.resetTurn(recipient);
        queue.enqueue(recipient, () -> CompletableFuture.completedFuture(null)).get(5, TimeUnit.SECONDS);

        // A brand new turn (e.g. a fresh incoming tap) — should NOT be
        // held back by MIN_GAP_MS just because the previous turn's last
        // send was recent.
        queue.resetTurn(recipient);
        long start = System.currentTimeMillis();
        queue.enqueue(recipient, () -> CompletableFuture.completedFuture(null)).get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(500); // should be near-instant, not ~3000ms
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void differentRecipientsAreNotBlockedByEachOthersQueue() throws Exception {
        MessageDispatchQueue queue = new MessageDispatchQueue();

        queue.resetTurn("254700000003");
        queue.enqueue("254700000003", () -> CompletableFuture.completedFuture(null)).get(5, TimeUnit.SECONDS);
        queue.enqueue("254700000003", () -> CompletableFuture.completedFuture(null)); // chained, will be paced — NOT awaited

        // A different recipient's first-in-turn message should send
        // immediately, unaffected by recipient 254700000003's pacing.
        queue.resetTurn("254700000004");
        long start = System.currentTimeMillis();
        queue.enqueue("254700000004", () -> CompletableFuture.completedFuture(null)).get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(500);
    }

    private CompletableFuture<Void> recordSend(List<TimedSend> sent, String label) {
        sent.add(new TimedSend(label, System.currentTimeMillis()));
        return CompletableFuture.completedFuture(null);
    }
}