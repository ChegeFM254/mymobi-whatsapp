package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.config.WhatsAppProperties;
import com.mfstechnologies.mymobi.document.DocumentHtmlService;
import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.DocumentStore;
import com.mfstechnologies.mymobi.session.LoanStore;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoanDocumentFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;

    private RegisteredUserStore userStore;
    private LoanStore loanStore;
    private DocumentStore documentStore;
    private DocumentHtmlService documentHtmlService;
    private LoanDocumentFlowService documentFlow;

    @BeforeEach
    void setUp() {
        userStore = new RegisteredUserStore();
        loanStore = new LoanStore();
        documentStore = new DocumentStore();
        documentHtmlService = new DocumentHtmlService();
        WhatsAppProperties properties = new WhatsAppProperties(
                "test_token", "test_phone_id", "test_verify_token", "test_secret",
                "v21.0", "https://mymobi-test.onrender.com"
        );
        documentFlow = new LoanDocumentFlowService(screenService, messageService, userStore, loanStore, documentStore, documentHtmlService, properties);
    }

    @Test
    void loanStatementMenuRefusesWhenNoLoanOnRecord() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        documentFlow.handleLoanStatementMenu(FROM, session).block();

        verify(screenService, never()).sendLoanStatementConfirm(anyString(), anyDouble());
    }

    @Test
    void loanStatementMenuShowsConfirmationWhenALoanExists() {
        loanStore.save(FROM, new Loan());
        UserSession session = new UserSession();
        when(screenService.sendLoanStatementConfirm(FROM, 23.20)).thenReturn(Mono.empty());

        documentFlow.handleLoanStatementMenu(FROM, session).block();

        assertThat(session.getPendingDocumentType()).isEqualTo("loan_statement");
        verify(screenService).sendLoanStatementConfirm(FROM, 23.20);
    }

    @Test
    void confirmingLoanStatementGeneratesADocumentLink() {
        RegisteredUser user = new RegisteredUser();
        user.setUpn("12345");
        userStore.save(FROM, user);
        loanStore.save(FROM, new Loan());

        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), contains("mymobi-test.onrender.com/documents/"))).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        documentFlow.handleConfirmLoanStatement(FROM, session).block();

        assertThat(session.getPendingDocumentType()).isNull();
        verify(messageService).sendTextMessage(eq(FROM), contains("mymobi-test.onrender.com/documents/"));
    }

    @Test
    void loanClearanceMenuRefusesWhenNoLoanOnRecord() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        documentFlow.handleLoanClearanceMenu(FROM, session).block();

        verify(screenService, never()).sendLoanClearanceConfirm(anyString(), anyDouble());
    }

    @Test
    void loanClearanceMenuRefusesWhenLoanIsNotFullyPaid() {
        Loan outstanding = new Loan();
        outstanding.setStatus("approved");
        loanStore.save(FROM, outstanding);

        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), contains("outstanding"))).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        documentFlow.handleLoanClearanceMenu(FROM, session).block();

        verify(screenService, never()).sendLoanClearanceConfirm(anyString(), anyDouble());
    }

    @Test
    void loanClearanceMenuShowsConfirmationWhenLoanIsFullyPaid() {
        Loan paid = new Loan();
        paid.setStatus("paid");
        loanStore.save(FROM, paid);

        UserSession session = new UserSession();
        when(screenService.sendLoanClearanceConfirm(FROM, 23.20)).thenReturn(Mono.empty());

        documentFlow.handleLoanClearanceMenu(FROM, session).block();

        assertThat(session.getPendingDocumentType()).isEqualTo("loan_clearance");
    }

    @Test
    void confirmingLoanClearanceGeneratesADocumentLink() {
        RegisteredUser user = new RegisteredUser();
        user.setUpn("12345");
        userStore.save(FROM, user);
        Loan paid = new Loan();
        paid.setStatus("paid");
        loanStore.save(FROM, paid);

        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), contains("mymobi-test.onrender.com/documents/"))).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        documentFlow.handleConfirmLoanClearance(FROM, session).block();

        assertThat(session.getPendingDocumentType()).isNull();
    }
}
