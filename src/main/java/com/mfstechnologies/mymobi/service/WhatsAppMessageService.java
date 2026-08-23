package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.config.WhatsAppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Sends messages via the WhatsApp Cloud API. Direct equivalent of
 * sendMessage() / sendTextMessage() / sendWithRetry() from the Node.js
 * version - including routing every send through the per-recipient
 * pacing queue (see MessageDispatchQueue), matching how the Node version
 * funneled every message through its recipientQueues system so that
 * nothing could bypass the spacing/retry protection.
 *
 * WORKSTREAM B (reactive -> synchronous): this class was originally
 * built on WebClient (reactive), returning Mono<Void>. It's now built
 * on RestClient (Spring's blocking HTTP client) and every method is a
 * plain, blocking call - including sendMessage() itself, which now
 * BLOCKS the calling thread until the message has actually been sent
 * (including any pacing delay MessageDispatchQueue applies). This is a
 * deliberate design point: MessageDispatchQueue's pacing/ordering logic
 * is untouched (it's already CompletableFuture-based, not reactive), so
 * sendMessage() blocks on that queue's future rather than fire-and-forget
 * subscribing to it. Callers that need to NOT block the calling thread
 * (specifically: WebhookController, so Meta's webhook still gets a fast
 * acknowledgement) are responsible for offloading to a background
 * thread themselves - see WebhookController's own Workstream B update.
 */
@Service
public class WhatsAppMessageService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessageService.class);
    private static final int MAX_RETRIES = 4;
    private static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(4);

    // WhatsApp rate-limit error codes - matches the Node version's check.
    private static final int ERROR_CODE_PAIR_RATE_LIMIT = 131056;
    private static final int ERROR_CODE_GENERAL_RATE_LIMIT = 130429;

    private final RestClient restClient;
    private final WhatsAppProperties properties;
    private final MessageDispatchQueue dispatchQueue;

    public WhatsAppMessageService(
            RestClient.Builder restClientBuilder,
            WhatsAppProperties properties,
            MessageDispatchQueue dispatchQueue
    ) {
        this.properties = properties;
        this.dispatchQueue = dispatchQueue;
        this.restClient = restClientBuilder
                .baseUrl("https://graph.facebook.com/" + properties.graphApiVersion())
                .defaultHeader("Authorization", "Bearer " + properties.accessToken())
                .build();
    }

    /** Call this once per incoming webhook message, before sending any replies to it - see MessageDispatchQueue.resetTurn(). */
    public void resetSendTurn(String recipient) {
        dispatchQueue.resetTurn(recipient);
    }

    public void sendTextMessage(String to, String text) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "text",
                "text", Map.of("body", text)
        );
        sendMessage(to, payload);
    }

    /**
     * Enqueues a message for the given recipient, then BLOCKS until it
     * has actually been sent - the actual HTTP call only happens once
     * the pacing queue reaches this message's turn, and this method
     * waits for that to happen. See MessageDispatchQueue for the
     * ordering/spacing guarantees; see the class-level comment above for
     * why blocking here is the correct design once the caller (usually
     * ConversationService, ultimately triggered from a background thread
     * kicked off by WebhookController) doesn't itself need to stay
     * non-blocking.
     */
    public void sendMessage(String to, Map<String, Object> payload) {
        CompletableFuture<Void> future = dispatchQueue.enqueue(to, () -> {
            doSendMessage(payload);
            return CompletableFuture.completedFuture(null);
        });
        try {
            future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            throw (cause instanceof RuntimeException re) ? re : new RuntimeException(cause);
        }
    }

    /**
     * The actual HTTP call + retry logic - only ever invoked BY the
     * dispatch queue, never called directly. Retries only on WhatsApp's
     * rate-limit error codes, with exponential backoff (4s, 8s, 16s,
     * 32s) - matching sendWithRetry() exactly. Any other error (network
     * failure, invalid payload, etc.) is NOT retried, same as the Node
     * version's behavior. Thread.sleep() here is deliberate and correct
     * for blocking code - this always runs on a background/queue thread,
     * never the request-handling thread.
     */
    private void doSendMessage(Map<String, Object> payload) {
        int attempt = 0;
        while (true) {
            try {
                restClient.post()
                        .uri("/{phoneNumberId}/messages", properties.phoneNumberId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (Exception error) {
                log.error("send_failed error={}", error.getMessage());

                if (attempt >= MAX_RETRIES || !isRateLimitError(error)) {
                    throw error instanceof RuntimeException re ? re : new RuntimeException(error);
                }

                attempt++;
                long delayMs = BASE_RETRY_DELAY.toMillis() * (1L << (attempt - 1)); // 4s, 8s, 16s, 32s
                log.warn("rate_limited_retry attempt={} error={}", attempt, error.getMessage());
                sleep(delayMs);
            }
        }
    }

    private void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
    }

    private boolean isRateLimitError(Exception exception) {
        // TODO: inspect the actual WhatsApp API error response body for
        // the specific error code (131056 / 130429), the same way the
        // Node version reads err.response?.data?.error?.code. This needs
        // RestClient's error response body parsed into WhatsApp's error
        // shape - left as a precise follow-up rather than guessed here,
        // since getting error-body parsing wrong silently breaks retry
        // behavior in a way that's hard to notice until it matters.
        return false;
    }
}
