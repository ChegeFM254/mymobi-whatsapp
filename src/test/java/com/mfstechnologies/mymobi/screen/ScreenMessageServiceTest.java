package com.mfstechnologies.mymobi.screen;

import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.service.WhatsAppMessageService;
import com.mfstechnologies.mymobi.session.LoanStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenMessageServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private WhatsAppMessageService messageService;

    @Captor
    private ArgumentCaptor<Map<String, Object>> payloadCaptor;

    private LoanStore loanStore;
    private ScreenMessageService screenService;

    @BeforeEach
    void setUp() {
        loanStore = new LoanStore();
        screenService = new ScreenMessageService(messageService, loanStore);
        when(messageService.sendMessage(eq(FROM), payloadCaptor.capture())).thenReturn(Mono.empty());
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedRowIds() {
        Map<String, Object> payload = payloadCaptor.getValue();
        Map<String, Object> interactive = (Map<String, Object>) payload.get("interactive");
        Map<String, Object> action = (Map<String, Object>) interactive.get("action");
        List<Map<String, Object>> sections = (List<Map<String, Object>>) action.get("sections");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) sections.get(0).get("rows");
        return rows.stream().map(row -> (String) row.get("id")).toList();
    }

    @Test
    void showsApplyLoanWhenThereIsNoLoan() {
        screenService.sendMainMenu(FROM).block();

        assertThat(capturedRowIds()).startsWith("apply_loan");
    }

    @Test
    void showsApproveLoanAndCancelLoanTogetherWhenPendingApproval() {
        Loan loan = new Loan();
        loan.setStatus("pending_approval");
        loanStore.save(FROM, loan);

        screenService.sendMainMenu(FROM).block();

        List<String> rowIds = capturedRowIds();
        assertThat(rowIds).containsSubsequence("approve_loan_menu", "cancel_loan");
    }

    @Test
    void showsPayLoanWhenApproved() {
        Loan loan = new Loan();
        loan.setStatus("approved");
        loanStore.save(FROM, loan);

        screenService.sendMainMenu(FROM).block();

        assertThat(capturedRowIds()).startsWith("pay_loan_menu");
    }

    @Test
  void showsApplyLoanWhenPreviousLoanIsFullyPaid() {
        Loan loan = new Loan();
        loan.setStatus("paid");
        loanStore.save(FROM, loan);

        screenService.sendMainMenu(FROM).block();

        assertThat(capturedRowIds()).startsWith("apply_loan");
    }

    @Test
    void alwaysIncludesTheStandardNavigationRows() {
        screenService.sendMainMenu(FROM).block();

        List<String> rowIds = capturedRowIds();
        assertThat(rowIds).contains("payslip_menu", "loan_statement_menu", "loan_clearance_menu", "back", "home", "logout");
    }

    // ==================== sendHomeScreen ====================

    @Test
    void homeScreenShowsMainMenuWhenAuthenticated() {
        com.mfstechnologies.mymobi.model.UserSession session = new com.mfstechnologies.mymobi.model.UserSession();
        session.setAuthenticated(true);

        screenService.sendHomeScreen(FROM, session).block();

        assertThat(capturedRowIds()).contains("payslip_menu", "loan_statement_menu");
    }

    @Test
    void homeScreenShowsWelcomeWhenNotAuthenticated() {
        com.mfstechnologies.mymobi.model.UserSession session = new com.mfstechnologies.mymobi.model.UserSession();

        screenService.sendHomeScreen(FROM, session).block();

        assertThat(capturedRowIds()).contains("civil_servants", "buy_airtime");
    }

    @Test
    void homeScreenShowsWelcomeWhenSessionIsNull() {
        screenService.sendHomeScreen(FROM, null).block();

        assertThat(capturedRowIds()).contains("civil_servants", "buy_airtime");
    }
}
