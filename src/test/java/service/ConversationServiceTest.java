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

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(sessionStore, screenService, messageService, authFlowService, registrationFlowService);
    }

    @Test
    void freshSessionWithTriggerWordSendsWelcomeAndMarksSessionNoLongerNew() {
        UserSession session = new UserSession(); // defaults: step="welcome", newSession=true
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.1", FROM, "hi", null);

        conversationService.handleIncomingMessage(message).block();

        verify(screenService).sendWelcome(FROM);
        assertThat(session.isNewSession()).isFalse();
    }

    @Test
    void triggerWordMatchingIsCaseInsensitiveAndTrimsWhitespace() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.2", FROM, "  HELLO  ", null);

        conversationService.handleIncomingMessage(message).block();

        verify(screenService).sendWelcome(FROM);
    }

    @Test
    void welcomeIsNotResentOnceSessionIsNoLongerNew() {
        UserSession session = new UserSession();
        session.setNewSession(false); // already saw Welcome once
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(messageService.sendTextMessage(anyString(), anyString())).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.3", FROM, "hi", null);

        conversationService.handleIncomingMessage(message).block();

        verify(screenService, never()).sendWelcome(anyString());
    }

    @Test
    void debouncedInputWithinTheGuardWindowIsIgnoredEntirely() {
        UserSession session = new UserSession();
        session.setLastProcessedAt(Instant.now()); // just processed something
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);

        IncomingMessage message = new IncomingMessage("wamid.4", FROM, "hi", null);

        conversationService.handleIncomingMessage(message).block();

        verifyNoInteractions(screenService);
        verify(messageService, never()).sendTextMessage(anyString(), anyString());
    }

    @Test
    void unmappedButtonTapsFallBackToTheGenericStub() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.5", FROM, null, "some_unported_button");

        conversationService.handleIncomingMessage(message).block();

        verify(messageService).sendTextMessage(eq(FROM), anyString());
        verifyNoInteractions(authFlowService, registrationFlowService);
    }

    @Test
    void civilServantsButtonRoutesToAuthenticationFlowService() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(authFlowService.handleCivilServants(FROM, session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.6", FROM, null, "civil_servants");

        conversationService.handleIncomingMessage(message).block();

        verify(authFlowService).handleCivilServants(FROM, session);
    }

    @Test
    void registerMenuButtonRoutesToRegistrationFlowService() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(registrationFlowService.handleRegisterMenu(FROM, session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.7", FROM, null, "register_menu");

        conversationService.handleIncomingMessage(message).block();

        verify(registrationFlowService).handleRegisterMenu(FROM, session);
    }

    @Test
    void editFieldButtonsRouteToRegistrationFlowServiceRegardlessOfWhichFieldIsPicked() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(registrationFlowService.handleEditFieldSelect(FROM, "edit_upn", session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.8", FROM, null, "edit_upn");

        conversationService.handleIncomingMessage(message).block();

        verify(registrationFlowService).handleEditFieldSelect(FROM, "edit_upn", session);
    }

    @Test
    void kycTextStepsRouteToRegistrationFlowService() {
        UserSession session = new UserSession();
        session.setStep("first_name");
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(registrationFlowService.handleFirstName(FROM, "Jane", session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.9", FROM, "Jane", null);

        conversationService.handleIncomingMessage(message).block();

        verify(registrationFlowService).handleFirstName(FROM, "Jane", session);
    }

    @Test
    void editFieldTextStepsRouteToRegistrationFlowServiceEditHandler() {
        UserSession session = new UserSession();
        session.setStep("edit_lastname");
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(registrationFlowService.handleEditFieldText(FROM, "Doe", session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.10", FROM, "Doe", null);

        conversationService.handleIncomingMessage(message).block();

        verify(registrationFlowService).handleEditFieldText(FROM, "Doe", session);
    }

    @Test
    void loginStepsRouteTextInputToAuthenticationFlowService() {
        UserSession session = new UserSession();
        session.setStep("login_enter_upn");
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(authFlowService.handleLoginEnterUpn(FROM, "12345", session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.11", FROM, "12345", null);

        conversationService.handleIncomingMessage(message).block();

        verify(authFlowService).handleLoginEnterUpn(FROM, "12345", session);
    }

    @Test
    void resetSendTurnIsAlwaysCalledBeforeAnyReply() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.12", FROM, "hi", null);

        conversationService.handleIncomingMessage(message).block();

        verify(messageService).resetSendTurn(FROM);
    }
}