package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.config.WhatsAppProperties;
import com.mfstechnologies.mymobi.document.DocumentHtmlService;
import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.DocumentStore;
import com.mfstechnologies.mymobi.session.LoanStore;
import com.mfstechnologies.mymobi.session.RegisteredUserRepository;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import com.mfstechnologies.mymobi.testsupport.FakeRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WORKSTREAM B (reactive -> synchronous): rewritten for the now-void
 * LoanDocumentFlowService methods. No more .block() calls or
 * Mono.empty() stubs anywhere.
 *
 * WORKSTREAM C (persistence): RegisteredUserStore is now Postgres-backed
 * - userStore here is wired to a fake, in-memory-backed repository (see
 * FakeRepositories) so it keeps behaving like a real, working
 * collaborator, exactly as it did with the old ConcurrentHashMap.
 */
@ExtendWith(MockitoExtension.class)
class LoanDocumentFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;
    @Mock
    private RegisteredUserRepository registeredUserRepository;

    private RegisteredUserStore userStore;
    private LoanStore loanStore;
    private DocumentStore documentStore;
    private DocumentHtmlService documentHtmlService;
    private LoanDocumentFlowService documentFlow;

    @BeforeEach
    void setUp() {
        FakeRepositories.wireAsInMemoryStore(registeredUserRepository, RegisteredUser::getPhoneNumber);
        userStore = new RegisteredUserStore(registeredUserRepository);
        loanStore = new LoanStore();
        documentStore = new DocumentStore();
        documentHtmlService = new DocumentHtmlService();
        WhatsAppProperties properties = new WhatsAppProperties(
                "test_token", "test_phone_id", "test_verify_token", "test_secret",
                "v21.0", "https://mymobi-test.onrender.com"
        );
        documentFlow = new LoanDocumentFlowService(screenService, messageService, userStore, loanStore, documentStore, documentHtmlService, properties);
    }

    // ==================== LOAN STATEMENT ====================

    @Test
    void loanStatementMenuRefusesWhenNoLoanOnRecord() {
        UserSession session = new UserSession();

        documentFlow.handleLoanStatementMenu(FROM, session);

        verify(screenService, never()).sendLoanStatementConfirm(anyString(), anyDouble());
    }

    @Test
    void loanStatementMenuShowsConfirmationWhenALoanExists() {
        loanStore.save(FROM, new Loan());
        UserSession session = new UserSession();

        documentFlow.handleLoanStatementMenu(FROM, session);

        assertThat(session.getPendingDocumentType()).isEqualTo("loan_statement");
        verify(screenService).sendLoanStatementConfirm(FROM, 23.20);
    }

    @Test
    void confirmingLoanStatementSendsStkPushPromptFirst() {
        RegisteredUser user = new RegisteredUser();
        user.setUpn("12345");
        userStore.save(FROM, user);
        loanStore.save(FROM, new Loan());

        UserSession session = new UserSession();

        documentFlow.handleConfirmLoanStatement(FROM, session);

        verify(messageService).sendTextMessage(eq(FROM),
                eq("You are about to pay KES 23.20 to MyMobi account XXXXX. Please enter your Mpesa PIN."));
            }

    @Test
    void confirmingLoanStatementGeneratesADocumentLink() {
        RegisteredUser user = new RegisteredUser();
        user.setUpn("12345");
        userStore.save(FROM, user);
        loanStore.save(FROM, new Loan());

        UserSession session = new UserSession();

        documentFlow.handleConfirmLoanStatement(FROM, session);

        assertThat(session.getPendingDocumentType()).isNull();
        verify(messageService).sendTextMessage(eq(FROM), contains("Please click on this link to access your Loan Statement"));
        verify(messageService).sendTextMessage(eq(FROM), contains("mymobi-test.onrender.com/documents/"));
    }

    // ==================== LOAN CLEARANCE LETTER ====================

    @Test
    void loanClearanceMenuRefusesWhenNoLoanOnRecord() {
        UserSession session = new UserSession();

        documentFlow.handleLoanClearanceMenu(FROM, session);

        verify(screenService, never()).sendLoanClearanceConfirm(anyString(), anyDouble());
    }

    @Test
    void loanClearanceMenuRefusesWhenLoanIsNotFullyPaid() {
        Loan outstanding = new Loan();
        outstanding.setStatus("approved");
        loanStore.save(FROM, outstanding);

        UserSession session = new UserSession();

        documentFlow.handleLoanClearanceMenu(FROM, session);

        verify(messageService).sendTextMessage(eq(FROM), contains("outstanding"));
        verify(screenService, never()).sendLoanClearanceConfirm(anyString(), anyDouble());
    }

    @Test
    void loanClearanceMenuShowsConfirmationWhenLoanIsFullyPaid() {
        Loan paid = new Loan();
        paid.setStatus("paid");
        loanStore.save(FROM, paid);

        UserSession session = new UserSession();

        documentFlow.handleLoanClearanceMenu(FROM, session);

        assertThat(session.getPendingDocumentType()).isEqualTo("loan_clearance");
    }

    @Test
    void confirmingLoanClearanceSendsStkPushPromptFirst() {
        RegisteredUser user = new RegisteredUser();
        user.setUpn("12345");
        userStore.save(FROM, user);
        Loan paid = new Loan();
        paid.setStatus("paid");
        loanStore.save(FROM, paid);

        UserSession session = new UserSession();

        documentFlow.handleConfirmLoanClearance(FROM, session);

        verify(messageService).sendTextMessage(eq(FROM),
                eq("You are about to pay KES 23.20 to MyMobi account XXXXX. Please enter your Mpesa PIN."));
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

        documentFlow.handleConfirmLoanClearance(FROM, session);

        assertThat(session.getPendingDocumentType()).isNull();
        verify(messageService).sendTextMessage(eq(FROM), contains("Please click on this link to access your Loan Clearance Letter"));
    }
}
