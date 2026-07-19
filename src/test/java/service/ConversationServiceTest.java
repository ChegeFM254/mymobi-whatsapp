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
    @Mock
    private LoanApplicationFlowService loanApplicationFlowService;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(
                sessionStore, screenService, messageService,
                authFlowService, registrationFlowService,
                forgotPinFlowService, optOutFlowService,
                loanApplicationFlowService
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
    void homeButtonAlwaysGoesToWelcome() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.3", FROM, null, "home");

        conversationService.handleIncomingMessage(message).block();

        verify(screenService).sendWelcome(FROM);
    }

    @Test
    void backButtonRoutesToLoanApplicationFlowServiceForCentralizedHandling() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(loanApplicationFlowService.handleBack(FROM, session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.4", FROM, null, "back");

        conversationService.handleIncomingMessage(message).block();

        verify(loanApplicationFlowService).handleBack(FROM, session);
    }

    @Test
    void emergencyLoanButtonRoutesToLoanApplicationFlowService() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(loanApplicationFlowService.handleEmergencyLoan(FROM, session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.5", FROM, null, "emergency_loan");

        conversationService.handleIncomingMessage(message).block();

        verify(loanApplicationFlowService).handleEmergencyLoan(FROM, session);
    }

    @Test
    void anyTenureButtonRoutesToLoanApplicationFlowServiceTenureSelect() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(loanApplicationFlowService.handleTenureSelect(FROM, "tenure_2", session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.6", FROM, null, "tenure_2");

        conversationService.handleIncomingMessage(message).block();

        verify(loanApplicationFlowService).handleTenureSelect(FROM, "tenure_2", session);
    }

    @Test
    void loanAmountTextStepRoutesToLoanApplicationFlowService() {
        UserSession session = new UserSession();
        session.setStep("enter_loan_amount");
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(loanApplicationFlowService.handleEnterLoanAmount(FROM, "35000", session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.7", FROM, "35000", null);

        conversationService.handleIncomingMessage(message).block();

        verify(loanApplicationFlowService).handleEnterLoanAmount(FROM, "35000", session);
    }

    @Test
    void unmappedButtonTapsFallBackToTheGenericStub() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.8", FROM, null, "some_unported_button");

        conversationService.handleIncomingMessage(message).block();

        verify(messageService).sendTextMessage(eq(FROM), anyString());
        verifyNoInteractions(authFlowService, registrationFlowService, forgotPinFlowService, optOutFlowService, loanApplicationFlowService);
    }

    @Test
    void resetSendTurnIsAlwaysCalledBeforeAnyReply() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.9", FROM, "hi", null);

        conversationService.handleIncomingMessage(message).block();

        verify(messageService).resetSendTurn(FROM);
    }
}