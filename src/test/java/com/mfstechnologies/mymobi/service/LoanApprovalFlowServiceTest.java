package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.LoanBreakdown;
import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
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
class LoanApprovalFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;

    private LoanStore loanStore;
    private RegisteredUserStore userStore;
    private LoanApprovalFlowService approvalFlow;

    @BeforeEach
    void setUp() {
        loanStore = new LoanStore();
        userStore = new RegisteredUserStore();
        approvalFlow = new LoanApprovalFlowService(screenService, messageService, loanStore, userStore);
    }

    private Loan pendingLoan() {
        Loan loan = new Loan();
        loan.setLoanAmount(15000);
        loan.setTenureMonths(1);
        loan.setRefNo("REF12345");
        loan.setApprovalCode("654321");
        loan.setStatus("pending_approval");
        loan.setDueDate("2026-08-19");
        loan.setBreakdown(new LoanBreakdown(15000, 2943, 32057, 14442, 150));
        return loan;
    }

    // ==================== APPROVE LOAN MENU ====================

    @Test
    void approveLoanMenuShowsDetailsWhenLoanIsPendingApproval() {
        loanStore.save(FROM, pendingLoan());
        UserSession session = new UserSession();
        when(screenService.sendApproveLoanDetails(eq(FROM), any())).thenReturn(Mono.empty());

        approvalFlow.handleApproveLoanMenu(FROM, session).block();

        verify(screenService).sendApproveLoanDetails(eq(FROM), any());
    }

    @Test
    void approveLoanMenuRedirectsWhenNoLoanExists() {
        UserSession session = new UserSession();
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        approvalFlow.handleApproveLoanMenu(FROM, session).block();

        verify(screenService).sendMainMenu(FROM);
        verify(screenService, never()).sendApproveLoanDetails(anyString(), any());
    }

    // ==================== APPROVAL CODE ====================

    @Test
    void correctApprovalCodeAdvancesToPayrollNumberStep() {
        loanStore.save(FROM, pendingLoan());
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        approvalFlow.handleEnterApprovalCode(FROM, "654321", session).block();

        assertThat(session.getStep()).isEqualTo("approval_payroll_number");
    }

    @Test
    void wrongApprovalCodeIncrementsAttemptsOnTheLoanItself() {
        Loan loan = pendingLoan();
        loanStore.save(FROM, loan);
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        approvalFlow.handleEnterApprovalCode(FROM, "000000", session).block();

        assertThat(loan.getApprovalCodeAttempts()).isEqualTo(1);
        assertThat(session.getStep()).isNotEqualTo("approval_payroll_number");
    }

    @Test
    void thirdWrongApprovalCodeCancelsTheLoan() {
        Loan loan = pendingLoan();
        loan.setApprovalCodeAttempts(2);
        loanStore.save(FROM, loan);
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendHomeScreen(FROM, session)).thenReturn(Mono.empty());

        approvalFlow.handleEnterApprovalCode(FROM, "000000", session).block();

        assertThat(loanStore.findByPhoneNumber(FROM)).isEmpty();
    }

    // ==================== APPROVAL PAYROLL NUMBER ====================

    @Test
    void matchingPayrollNumberApprovesTheLoan() {
        Loan loan = pendingLoan();
        loanStore.save(FROM, loan);
        RegisteredUser user = new RegisteredUser();
        user.setUpn("19999999");
        userStore.save(FROM, user);

        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendHomeScreen(FROM, session)).thenReturn(Mono.empty());

        approvalFlow.handleApprovalPayrollNumber(FROM, "19999999", session).block();

        assertThat(loan.getStatus()).isEqualTo("approved");
        assertThat(loan.getApprovedAt()).isNotNull();
        verify(screenService).sendHomeScreen(FROM, session);
        verify(screenService, never()).sendWelcome(anyString());
    }

    @Test
    void nonMatchingPayrollNumberIncrementsSeparateAttemptCounter() {
        Loan loan = pendingLoan();
        loanStore.save(FROM, loan);
        RegisteredUser user = new RegisteredUser();
        user.setUpn("19999999");
        userStore.save(FROM, user);

        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        approvalFlow.handleApprovalPayrollNumber(FROM, "10000000", session).block();

        assertThat(loan.getApprovalPayrollAttempts()).isEqualTo(1);
        assertThat(loan.getApprovalCodeAttempts()).isZero();
        assertThat(loan.getStatus()).isEqualTo("pending_approval");
    }

    // ==================== CANCEL LOAN ====================

    @Test
    void confirmingCancelRemovesTheLoan() {
        loanStore.save(FROM, pendingLoan());
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        approvalFlow.handleCancelLoanYes(FROM, session).block();

        assertThat(loanStore.findByPhoneNumber(FROM)).isEmpty();
    }

    @Test
    void decliningCancelKeepsTheLoanPending() {
        loanStore.save(FROM, pendingLoan());
        UserSession session = new UserSession();
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        approvalFlow.handleCancelLoanNo(FROM, session).block();

        assertThat(loanStore.findByPhoneNumber(FROM)).isPresent();
        verify(screenService).sendMainMenu(FROM);
    }
}
