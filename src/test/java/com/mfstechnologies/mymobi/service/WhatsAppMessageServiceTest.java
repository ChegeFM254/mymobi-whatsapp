package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.config.WhatsAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WORKSTREAM B (reactive -> synchronous): rewritten for the blocking
 * RestClient-based WhatsAppMessageService. No more .block() calls
 * anywhere - every method call here is already synchronous, matching
 * the production code it's testing.
 */
@ExtendWith(MockitoExtension.class)
class WhatsAppMessageServiceTest {

    private static final String TO = "254700000001";

    @Mock
    private MessageDispatchQueue dispatchQueue;

    private WhatsAppMessageService messageService;

    @BeforeEach
    void setUp() {
        WhatsAppProperties properties = new WhatsAppProperties(
                "test_token", "test_phone_id", "test_verify_token", "test_secret",
                "v21.0", "https://mymobi-test.onrender.com"
        );
        messageService = new WhatsAppMessageService(RestClient.builder(), properties, dispatchQueue);
    }

    @Test
    void resetSendTurnDelegatesToTheDispatchQueue() {
        messageService.resetSendTurn(TO);

        verify(dispatchQueue).resetTurn(TO);
    }

    @Test
    void sendMessageRoutesThroughTheDispatchQueueForTheCorrectRecipient() {
        when(dispatchQueue.enqueue(eq(TO), any())).thenReturn(CompletableFuture.completedFuture(null));

        Map<String, Object> payload = Map.of("messaging_product", "whatsapp", "to", TO, "type", "text");
        messageService.sendMessage(TO, payload);

        verify(dispatchQueue).enqueue(eq(TO), any());
    }

    @Test
    void sendTextMessageAlsoRoutesThroughTheDispatchQueueForTheCorrectRecipient() {
        when(dispatchQueue.enqueue(eq(TO), any())).thenReturn(CompletableFuture.completedFuture(null));

        messageService.sendTextMessage(TO, "Hello there");

        verify(dispatchQueue).enqueue(eq(TO), any());
    }

    @Test
    void differentRecipientsAreRoutedIndependently() {
        when(dispatchQueue.enqueue(anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        messageService.sendTextMessage("254700000001", "First");
        messageService.sendTextMessage("254700000002", "Second");

        verify(dispatchQueue).enqueue(eq("254700000001"), any());
        verify(dispatchQueue).enqueue(eq("254700000002"), any());
    }

    @Test
    void sendMessageBlocksUntilTheDispatchQueueFutureCompletes() {
        CompletableFuture<Void> notYetComplete = new CompletableFuture<>();
        when(dispatchQueue.enqueue(eq(TO), any())).thenReturn(notYetComplete);

        Thread sender = new Thread(() -> messageService.sendTextMessage(TO, "Hello"));
        sender.start();

        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
        notYetComplete.complete(null);

        try {
            sender.join(1000);
        } catch (InterruptedException ignored) {
        }

        verify(dispatchQueue).enqueue(eq(TO), any());
    }

    @Test
    void sendMessagePropagatesAFailedDispatchQueueFutureAsARuntimeException() {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("simulated send failure"));
        when(dispatchQueue.enqueue(eq(TO), any())).thenReturn(failed);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> messageService.sendMessage(TO, Map.of("to", TO)))
                .isInstanceOf(RuntimeException.class);
    }
}
