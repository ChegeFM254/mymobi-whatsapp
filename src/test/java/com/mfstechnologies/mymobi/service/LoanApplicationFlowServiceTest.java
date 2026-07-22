package com.mfstechnologies.mymobi.service;

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
class LoanApplicationFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;

    private LoanStore loanStore;
    private RegisteredUserStore userStore;
    private LoanCalculationService calculationService;
    private LoanApplicationFlowService loanFlow;

    @BeforeEach
    void setUp() {
        loanStore = new LoanStore();
        userStore = new RegisteredUserStore();
        calculationService = new LoanCalculationService();
        loanFlow = new LoanApplicationFlowService(screenService, messageService, loanStore, userStore, calculationService);
    }

    // ==================== APPLY LOAN ====================

    @Test
    void applyLoanStartsTenureSelectionWhenNoActiveLoan() {
        UserSession session = new UserSession();
        when(screenService.sendLoanTenureOptions(FROM)).thenReturn(Mono.empty());

        loanFlow.handleApplyLoan(FROM, session).block();

        assertThat(session.getCurrentMenu()).isEqualTo("loan_tenure_menu");
        verify(screenService).sendLoanTenureOptions(FROM);
    }

    @Test
    void applyLoanIsBlockedWhenALoanIsAlreadyPendingApproval() {
        Loan existing = new Loan();
        existing.setStatus("pending_approval");
        loanStore.save(FROM, existing);

        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        loanFlow.handleApplyLoan(FROM, session).block();

        verify(screenService, never()).sendLoanTenureOptions(anyString());
    }

    // ==================== TENURE SELECTION ====================

    @Test
    void selectingATenurePopulatesSessionAndPromptsForAmount() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        loanFlow.handleTenureSelect(FROM, "tenure_2", session).block();

        assertThat(session.getLoanTenureMonths()).isEqualTo(2);
        assertThat(session.getLoanLimit()).isEqualTo(40000);
        assertThat(session.getStep()).isEqualTo("enter_loan_amount");
        verify(messageService).sendTextMessage(eq(FROM), contains("40000"));
    }

    // ==================== LOAN AMOUNT ====================

    @Test
    void nonNumericLoanAmountIsRejected() {
        UserSession session = new UserSession();
        session.setLoanLimit(20000);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        loanFlow.handleEnterLoanAmount(FROM, "abc", session).block();

        assertThat(session.getLoanAmount()).isNull();
    }

    @Test
    void loanAmountBelowMinimumIsRejected() {
        UserSession session = new UserSession();
        session.setLoanLimit(20000);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        loanFlow.handleEnterLoanAmount(FROM, "500", session).block();

        assertThat(session.getLoanAmount()).isNull();
    }

    @Test
    void loanAmountAboveTenureLimitIsRejected() {
        UserSession session = new UserSession();
        session.setLoanLimit(20000);
        session.setLoanTenureMonths(1);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        loanFlow.handleEnterLoanAmount(FROM, "25000", session).block();

        assertThat(session.getLoanAmount()).isNull();
    }

    @Test
    void validLoanAmountShowsBreakdown() {
        UserSession session = new UserSession();
        session.setLoanLimit(20000);
        session.setLoanTenureMonths(1);
        when(screenService.sendLoanBreakdown(eq(FROM), any())).thenReturn(Mono.empty());

        loanFlow.handleEnterLoanAmount(FROM, "15000", session).block();

        assertThat(session.getLoanAmount()).isEqualTo(15000);
        assertThat(session.getStep()).isEqualTo("loan_confirm");
        assertThat(session.getCurrentMenu()).isEqualTo("loan_breakdown_menu");
    }

    // ==================== ACCEPT / DECLINE ====================

    @Test
    void acceptingTheLoanPromptsForPayrollNumber() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        loanFlow.handleAcceptLoan(FROM, session).block();

        assertThat(session.getStep()).isEqualTo("enter_loan_payroll_number");
    }

    @Test
    void decliningClearsSessionAndReturnsToMainMenu() {
        UserSession session = new UserSession();
        session.setLoanAmount(15000);
        session.setLoanTenureMonths(1);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        loanFlow.handleDeclineLoan(FROM, session).block();

        assertThat(session.getLoanAmount()).isNull();
        assertThat(session.getLoanTenureMonths()).isNull();
    }

    // ==================== PAYROLL NUMBER + SUBMISSION ====================
@Test
    void payrollNumberNotMatchingRegisteredUpnIncrementsAttempts() {
        RegisteredUser user = new RegisteredUser();
        user.setUpn("19999999");
        userStore.save(FROM, user);

        UserSession session = new UserSession();
        session.setLoanAmount(15000);
        session.setLoanTenureMonths(1);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        loanFlow.handleEnterPayrollNumber(FROM, "10000000", session).block(); // wrong UPN

        assertThat(session.getPayrollNumberAttempts()).isEqualTo(1);
        assertThat(loanStore.findByPhoneNumber(FROM)).isEmpty();
    }

    @Test
    void thirdWrongPayrollNumberCancelsTheApplication() {
        RegisteredUser user = new RegisteredUser();
        user.setUpn("19999999");
        userStore.save(FROM, user);

        UserSession session = new UserSession();
        session.setLoanAmount(15000);
        session.setLoanTenureMonths(1);
        session.setPayrollNumberAttempts(2);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        loanFlow.handleEnterPayrollNumber(FROM, "10000000", session).block();

        assertThat(session.getLoanAmount()).isNull(); // cleared
        assertThat(loanStore.findByPhoneNumber(FROM)).isEmpty();
    }

    @Test
    void matchingPayrollNumberSubmitsTheLoan() {
        RegisteredUser user = new RegisteredUser();
        user.setUpn("19999999");
        userStore.save(FROM, user);

        UserSession session = new UserSession();
        session.setLoanAmount(15000);
        session.setLoanTenureMonths(1);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        loanFlow.handleEnterPayrollNumber(FROM, "19999999", session).block();

        Loan submitted = loanStore.findByPhoneNumber(FROM).orElseThrow();
        assertThat(submitted.getStatus()).isEqualTo("pending_approval");
        assertThat(submitted.getLoanAmount()).isEqualTo(15000);
        assertThat(submitted.getPayrollNumber()).isEqualTo("19999999");
        assertThat(submitted.getRefNo()).isNotBlank();
        assertThat(submitted.getApprovalCode()).matches("^\\d{6}$");
        assertThat(session.getLoanAmount()).isNull(); // session fields cleared after submission
    }

    // ==================== BACK NAVIGATION ====================
@Test
    void backFromLoanTenureMenuGoesToMainMenu() {
        UserSession session = new UserSession();
        session.setCurrentMenu("loan_tenure_menu");
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        loanFlow.handleBack(FROM, session).block();

        verify(screenService).sendMainMenu(FROM);
    }

    @Test
    void backFromLoanAmountMenuGoesToTenureOptions() {
        UserSession session = new UserSession();
        session.setCurrentMenu("loan_amount_menu");
        when(screenService.sendLoanTenureOptions(FROM)).thenReturn(Mono.empty());

        loanFlow.handleBack(FROM, session).block();

        verify(screenService).sendLoanTenureOptions(FROM);
    }

    @Test
    void backFromBreakdownGoesToLoanAmountMenu() {
        UserSession session = new UserSession();
        session.setCurrentMenu("loan_breakdown_menu");
        session.setLoanLimit(20000);
        session.setLoanTenureMonths(1);
        when(screenService.sendLoanAmountMenu(FROM, 20000, 1)).thenReturn(Mono.empty());

        loanFlow.handleBack(FROM, session).block();

        verify(screenService).sendLoanAmountMenu(FROM, 20000, 1);
    }

    @Test
    void backWithNoTrackedContextIsContextAware() {
        UserSession session = new UserSession(); // currentMenu is null
        when(screenService.sendHomeScreen(FROM, session)).thenReturn(Mono.empty());

        loanFlow.handleBack(FROM, session).block();

        verify(screenService).sendHomeScreen(FROM, session);
    }
}
