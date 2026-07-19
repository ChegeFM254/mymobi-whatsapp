package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.IncomingMessage;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private SessionStore sessionStore;
    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;
    @Mock
    private AuthenticationFlowService authFlowService;
    @Mock
    private RegistrationFlowService registrationFlowService;
    @Mock
    private ForgotPinFlowService forgotPinFlowService;
    @Mock
    private OptOutFlowService optOutFlowService;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(
                sessionStore, screenService, messageService,
                authFlowService, registrationFlowService,
                forgotPinFlowService, optOutFlowService
        );
    }

    @Test
    void freshSessionWithTriggerWordSendsWelcomeAndMarksSessionNoLongerNew() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.1", FROM, "hi", null);

        conversationService.handleIncomingMessage(message).block();

        verify(screenService).sendWelcome(FROM);
        assertThat(session.isNewSession()).isFalse();
    }

    @Test
    void debouncedInputWithinTheGuardWindowIsIgnoredEntirely() {
        UserSession session = new UserSession();
        session.setLastProcessedAt(Instant.now());
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);

        IncomingMessage message = new IncomingMessage("wamid.2", FROM, "hi", null);

        conversationService.handleIncomingMessage(message).block();

        verifyNoInteractions(screenService);
        verify(messageService, never()).sendTextMessage(anyString(), anyString());
    }

    @Test
    void forgotPinButtonRoutesToForgotPinFlowService() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(forgotPinFlowService.handleForgotPin(FROM, session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.3", FROM, null, "forgot_pin");

        conversationService.handleIncomingMessage(message).block();

        verify(forgotPinFlowService).handleForgotPin(FROM, session);
    }

    @Test
    void optOutButtonRoutesToOptOutFlowService() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(optOutFlowService.handleOptOut(FROM, session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.4", FROM, null, "opt_out");

        conversationService.handleIncomingMessage(message).block();

        verify(optOutFlowService).handleOptOut(FROM, session);
    }

    @Test
    void forgotPinOtpStepRoutesToForgotPinFlowService() {
        UserSession session = new UserSession();
        session.setStep("forgot_pin_enter_otp");
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(forgotPinFlowService.handleEnterOtp(FROM, "12345", session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.5", FROM, "12345", null);

        conversationService.handleIncomingMessage(message).block();

        verify(forgotPinFlowService).handleEnterOtp(FROM, "12345", session);
    }

    @Test
    void optOutConfirmationStepRoutesToOptOutFlowService() {
        UserSession session = new UserSession();
        session.setStep("opt_out_confirmation");
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(optOutFlowService.handleOptOutConfirmation(FROM, "yes", session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.6", FROM, "yes", null);

        conversationService.handleIncomingMessage(message).block();

        verify(optOutFlowService).handleOptOutConfirmation(FROM, "yes", session);
    }

    @Test
    void unmappedButtonTapsFallBackToTheGenericStub() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.7", FROM, null, "some_unported_button");

        conversationService.handleIncomingMessage(message).block();

        verify(messageService).sendTextMessage(eq(FROM), anyString());
        verifyNoInteractions(authFlowService, registrationFlowService, forgotPinFlowService, optOutFlowService);
    }

    @Test
    void resetSendTurnIsAlwaysCalledBeforeAnyReply() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.8", FROM, "hi", null);

        conversationService.handleIncomingMessage(message).block();

        verify(messageService).resetSendTurn(FROM);
    }
}