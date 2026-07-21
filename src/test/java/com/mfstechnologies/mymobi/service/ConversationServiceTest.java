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
    @Mock
    private LoanApprovalFlowService loanApprovalFlowService;
    @Mock
    private LoanPaymentFlowService loanPaymentFlowService;
    @Mock
    private PayslipFlowService payslipFlowService;
    @Mock
    private LoanDocumentFlowService loanDocumentFlowService;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(
                sessionStore, screenService, messageService,
                authFlowService, registrationFlowService,
                forgotPinFlowService, optOutFlowService,
                loanApplicationFlowService, loanApprovalFlowService,
                loanPaymentFlowService, payslipFlowService,
                loanDocumentFlowService
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
    void loanStatementMenuButtonRoutesToLoanDocumentFlowService() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(loanDocumentFlowService.handleLoanStatementMenu(FROM, session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.3", FROM, null, "loan_statement_menu");

        conversationService.handleIncomingMessage(message).block();

        verify(loanDocumentFlowService).handleLoanStatementMenu(FROM, session);
    }

    @Test
    void loanClearanceMenuButtonRoutesToLoanDocumentFlowService() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(loanDocumentFlowService.handleLoanClearanceMenu(FROM, session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.4", FROM, null, "loan_clearance_menu");

        conversationService.handleIncomingMessage(message).block();

        verify(loanDocumentFlowService).handleLoanClearanceMenu(FROM, session);
    }

    @Test
    void payslipMenuButtonRoutesToPayslipFlowService() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(payslipFlowService.handlePayslipMenu(FROM, session)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.5", FROM, null, "payslip_menu");

        conversationService.handleIncomingMessage(message).block();

        verify(payslipFlowService).handlePayslipMenu(FROM, session);
    }

    @Test
    void unmappedButtonTapsFallBackToTheGenericStub() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.6", FROM, null, "some_unported_button");

        conversationService.handleIncomingMessage(message).block();

        verify(messageService).sendTextMessage(eq(FROM), anyString());
        verifyNoInteractions(authFlowService, registrationFlowService, forgotPinFlowService, optOutFlowService,
                loanApplicationFlowService, loanApprovalFlowService, loanPaymentFlowService, payslipFlowService,
                loanDocumentFlowService);
    }

    @Test
    void resetSendTurnIsAlwaysCalledBeforeAnyReply() {
        UserSession session = new UserSession();
        when(sessionStore.getOrCreate(FROM)).thenReturn(session);
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        IncomingMessage message = new IncomingMessage("wamid.7", FROM, "hi", null);

        conversationService.handleIncomingMessage(message).block();

        verify(messageService).resetSendTurn(FROM);
    }
}
