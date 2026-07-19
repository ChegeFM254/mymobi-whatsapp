package com.mfstechnologies.mymobi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Per-recipient outbound message pacing — direct equivalent of the
 * recipientQueues system in the Node.js version (BUG FIX #5 and BUG FIX
 * #10 in the original codebase's comments).
 *
 * WhatsApp enforces a per-(business number, recipient) throttle. Sending
 * two messages to the same person too close together can trip a rate
 * limit error (code 131056), even when both messages are legitimate.
 * This class:
 *   1. Sends messages to a given recipient strictly one at a time, in order.
 *   2. Enforces a minimum gap between messages the SERVER chains together
 *      on its own within one incoming-message "turn" (e.g. a confirmation
 *      text immediately followed by a menu).
 *   3. Does NOT delay the first reply to a fresh incoming message/tap —
 *      only chained follow-up messages within the same turn are paced.
 *      Delaying the first reply would create a pointless lag between a
 *      user's tap and the bot's response.
 *
 * ⚠️ IMPORTANT CAVEAT: this was a genuinely hard-won piece of the
 * original Node.js build — several real concurrency bugs were found and
 * fixed getting this exact behavior right, through live testing. This
 * Java port has been written carefully but, unlike the Node version,
 * has NOT been compiled or run (see project README). The concurrency
 * logic here — particularly the synchronized block serializing queue
 * updates per recipient — deserves careful review and, ideally, a
 * dedicated concurrent-stress test once this compiles, before trusting
 * it the way the Node version earned that trust through real bug fixes.
 */
@Service
public class MessageDispatchQueue {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatchQueue.class);
    private static final long MIN_GAP_MS = 3000;

    private final Map<String, RecipientState> recipients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /** Holds the pacing state for a single recipient's message queue. */
    private static final class RecipientState {
        volatile CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        final AtomicLong lastSentAt = new AtomicLong(0);
        final AtomicInteger turnMessageCount = new AtomicInteger(0);
    }

    /**
     * Marks the start of a new "turn" for this recipient — call this
     * once, at the top of processing each fresh incoming webhook message.
     * The first message enqueued after this call sends immediately; any
     * further messages enqueued before the NEXT resetTurn() call are
     * treated as chained follow-ups and paced against MIN_GAP_MS.
     */
    public void resetTurn(String recipient) {
        recipientState(recipient).turnMessageCount.set(0);
    }

    /**
     * Enqueues a message send for the given recipient. sendAction is only
     * actually invoked once it's this message's turn in the queue —
     * strictly after any prior message to the same recipient has
     * finished sending, and after any required pacing delay.
     *
     * @return a future that completes once the send (including any
     *         pacing delay) has finished, or fails if the send itself failed
     */
    public CompletableFuture<Void> enqueue(String recipient, Supplier<CompletableFuture<Void>> sendAction) {
        RecipientState state = recipientState(recipient);
        boolean isFirstInTurn = state.turnMessageCount.getAndIncrement() == 0;

        // Synchronized to make the read-modify-write of `tail` atomic.
        // Without this, two enqueue() calls for the SAME recipient
        // arriving in quick succession could both read the old tail
        // before either writes the new one — causing their sends to run
        // in parallel instead of strictly in order, which defeats the
        // entire purpose of this class. The lock is held only briefly
        // (reassigning a reference, then chaining a non-blocking async
        // step), so this isn't a throughput concern.
        synchronized (state) {
            CompletableFuture<Void> newTail = state.tail
                    .exceptionally(err -> {
                        log.warn("Previous send in queue for {} failed, continuing queue anyway: {}", recipient, err.getMessage());
                        return null; // never let a prior failure break the chain for this recipient
                    })
                    .thenCompose(ignored -> {
                        long waitMs = 0;
                        if (!isFirstInTurn) {
                            long elapsed = System.currentTimeMillis() - state.lastSentAt.get();
                            waitMs = Math.max(0, MIN_GAP_MS - elapsed);
                        }
                        return delayedSend(waitMs, sendAction, state);
                    });
            state.tail = newTail;
            return newTail;
        }
    }

    private CompletableFuture<Void> delayedSend(long delayMs, Supplier<CompletableFuture<Void>> sendAction, RecipientState state) {
        CompletableFuture<Void> result = new CompletableFuture<>();

        Runnable task = () -> {
            try {
                sendAction.get().whenComplete((v, err) -> {
                    state.lastSentAt.set(System.currentTimeMillis());
                    if (err != null) {
                        result.completeExceptionally(err);
                    } else {
                        result.complete(null);
                    }
                });
            } catch (Exception e) {
                // Guards against sendAction.get() itself throwing
                // synchronously, not just the returned future failing.
                state.lastSentAt.set(System.currentTimeMillis());
                result.completeExceptionally(e);
            }
        };

        if (delayMs <= 0) {
            task.run();
        } else {
            scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        }
        return result;
    }

    private RecipientState recipientState(String recipient) {
        return recipients.computeIfAbsent(recipient, key -> new RecipientState());
    }
}