package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.config.WhatsAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppMessageServiceTest {

    private static final String TO = "254700000001";

    @Mock
    private MessageDispatchQueue dispatchQueue;

    @Captor
    private ArgumentCaptor<String> recipientCaptor;
    @Captor
    private ArgumentCaptor<Supplier<CompletableFuture<Void>>> actionCaptor;

    private WhatsAppMessageService messageService;

    @BeforeEach
    void setUp() {
        WhatsAppProperties properties = new WhatsAppProperties(
                "test_token", "test_phone_id", "test_verify_token", "test_secret",
                "v21.0", "https://mymobi-test.onrender.com"
        );
        messageService = new WhatsAppMessageService(WebClient.builder(), properties, dispatchQueue);
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
        messageService.sendMessage(TO, payload).block();

        verify(dispatchQueue).enqueue(eq(TO), any());
    }

    @Test
    void sendTextMessageAlsoRoutesThroughTheDispatchQueueForTheCorrectRecipient() {
        when(dispatchQueue.enqueue(eq(TO), any())).thenReturn(CompletableFuture.completedFuture(null));

        messageService.sendTextMessage(TO, "Hello there").block();

        verify(dispatchQueue).enqueue(eq(TO), any());
    }

    @Test
    void differentRecipientsAreRoutedIndependently() {
        when(dispatchQueue.enqueue(anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        messageService.sendTextMessage("254700000001", "First").block();
        messageService.sendTextMessage("254700000002", "Second").block();

        verify(dispatchQueue).enqueue(eq("254700000001"), any());
        verify(dispatchQueue).enqueue(eq("254700000002"), any());
    }

    @Test
    void sendMessageIsLazyAndOnlyCallsTheDispatchQueueOnSubscription() {
        when(dispatchQueue.enqueue(eq(TO), any())).thenReturn(CompletableFuture.completedFuture(null));

        Map<String, Object> payload = Map.of("to", TO);
        var mono = messageService.sendMessage(TO, payload); // not subscribed yet

        verifyNoInteractions(dispatchQueue);

        mono.block(); // subscribing now triggers it

        verify(dispatchQueue).enqueue(eq(TO), any());
    }
}
