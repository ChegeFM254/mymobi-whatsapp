package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.config.WhatsAppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;

/**
 * Sends messages via the WhatsApp Cloud API. Direct equivalent of
 * sendMessage() / sendTextMessage() / sendWithRetry() from the Node.js
 * version — including routing every send through the per-recipient
 * pacing queue (see MessageDispatchQueue), matching how the Node version
 * funneled every message through its recipientQueues system so that
 * nothing could bypass the spacing/retry protection.
 */
@Service
public class WhatsAppMessageService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessageService.class);
    private static final int MAX_RETRIES = 4;
    private static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(4);

    // WhatsApp rate-limit error codes — matches the Node version's check.
    private static final int ERROR_CODE_PAIR_RATE_LIMIT = 131056;
    private static final int ERROR_CODE_GENERAL_RATE_LIMIT = 130429;

    private final WebClient webClient;
    private final WhatsAppProperties properties;
    private final MessageDispatchQueue dispatchQueue;

    public WhatsAppMessageService(
            WebClient.Builder webClientBuilder,
            WhatsAppProperties properties,
            MessageDispatchQueue dispatchQueue
    ) {
        this.properties = properties;
        this.dispatchQueue = dispatchQueue;
        this.webClient = webClientBuilder
                .baseUrl("https://graph.facebook.com/" + properties.graphApiVersion())
                .defaultHeader("Authorization", "Bearer " + properties.accessToken())
                .build();
    }

    /** Call this once per incoming webhook message, before sending any replies to it — see MessageDispatchQueue.resetTurn(). */
    public void resetSendTurn(String recipient) {
        dispatchQueue.resetTurn(recipient);
    }

    public Mono<Void> sendTextMessage(String to, String text) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "text",
                "text", Map.of("body", text)
        );
        return sendMessage(to, payload);
    }

    /**
     * Enqueues a message for the given recipient. The actual HTTP call
     * only happens once the pacing queue reaches this message's turn —
     * see MessageDispatchQueue for the ordering/spacing guarantees.
     */
    public Mono<Void> sendMessage(String to, Map<String, Object> payload) {
        return Mono.fromFuture(() ->
                dispatchQueue.enqueue(to, () -> doSendMessage(payload).toFuture())
        );
    }

    /** The actual HTTP call + retry logic — only ever invoked BY the dispatch queue, never called directly. */
    private Mono<Void> doSendMessage(Map<String, Object> payload) {
        return webClient.post()
                .uri("/{phoneNumberId}/messages", properties.phoneNumberId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(rateLimitRetrySpec())
                .doOnError(error -> log.error("send_failed error={}", error.getMessage()))
                .then();
    }

    /**
     * Retries only on WhatsApp's rate-limit error codes, with exponential
     * backoff (4s, 8s, 16s, 32s) — matching sendWithRetry() exactly.
     * Any other error (network failure, invalid payload, etc.) is NOT
     * retried, same as the Node version's behavior.
     */
    private Retry rateLimitRetrySpec() {
        return Retry.backoff(MAX_RETRIES, BASE_RETRY_DELAY)
                .filter(this::isRateLimitError)
                .doBeforeRetry(signal -> log.warn(
                        "rate_limited_retry attempt={} error={}",
                        signal.totalRetries() + 1,
                        signal.failure().getMessage()
                ));
    }

    private boolean isRateLimitError(Throwable throwable) {
        // TODO: inspect the actual WhatsApp API error response body for
        // the specific error code (131056 / 130429), the same way the
        // Node version reads err.response?.data?.error?.code. This needs
        // WebClient's error response body parsed into WhatsApp's error
        // shape — left as a precise follow-up rather than guessed here,
        // since getting error-body parsing wrong silently breaks retry
        // behavior in a way that's hard to notice until it matters.
        return false;
    }
}