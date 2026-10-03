package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.OutboxEntry;
import com.mfstechnologies.mymobi.model.OutboxEntryType;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.LoanRepository;
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
 * LoanApplicationFlowService methods. No more .block() calls or
 * Mono.empty() stubs anywhere.
 *
 * WORKSTREAM C (persistence): RegisteredUserStore and LoanStore are now
 * Postgres-backed - both are wired to fake, in-memory-backed
 * repositories (see FakeRepositories) so they keep behaving like real,
 * working collaborators, exactly as they did with the old
 * ConcurrentHashMap.
 *
 * WORKSTREAM G (outbox pattern): Approval Code delivery now goes through
 * outboxService.enqueueWithBusinessWrite(...) instead of a direct
 * SmsService call. OutboxService itself is mocked here (not wired via
 * FakeRepositories like LoanStore/RegisteredUserStore) since OutboxEntry
 * has an auto-generated id that FakeRepositories' generic in-memory
 * wiring doesn't model - OutboxService gets its own dedicated test for
 * that. The mock's enqueueWithBusinessWrite is stubbed to actually
 * invoke the given Runnable, since that's what runs the real
 * loanStore.save(...) call this flow depends on - without that stub,
 * the mock would silently swallow the runnable and the loan would never
 * actually be saved.
 */
@ExtendWith(MockitoExtension.class)
class LoanApplicationFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;
    @Mock
    private OutboxService outboxService;
    @Mock
    private RegisteredUserRepository registeredUserRepository;
    @Mock
    private LoanRepository loanRepository;

    private LoanStore loanStore;
    private RegisteredUserStore userStore;
    private LoanCalculationService calculationService;
        private LoanApplicationFlowService loanFlow;

    @BeforeEach
    void setUp() {
        FakeRepositories.wireAsInMemoryStore(loanRepository, Loan::getPhoneNumber);
        loanStore = new LoanStore(loanRepository);
        FakeRepositories.wireAsInMemoryStore(registeredUserRepository, RegisteredUser::getPhoneNumber);
        userStore = new RegisteredUserStore(registeredUserRepository);
        calculationService = new MockLoanCalculationService();
        loanFlow = new LoanApplicationFlowService(screenService, messageService, outboxService, loanStore, userStore, calculationService);

        lenient().when(outboxService.enqueueWithBusinessWrite(any(), any(), any())).thenAnswer(invocation -> {
            Runnable businessWrite = invocation.getArgument(2);
            businessWrite.run();
            return new OutboxEntry();
        });
    }

    // ==================== APPLY LOAN ====================

    @Test
    void applyLoanStartsTenureSelectionWhenNoActiveLoan() {
        UserSession session = new UserSession();

        loanFlow.handleApplyLoan(FROM, session);

        assertThat(session.getCurrentMenu()).isEqualTo("loan_tenure_menu");
        verify(screenService).sendLoanTenureOptions(FROM);
    }

    @Test
    void applyLoanIsBlockedWhenALoanIsAlreadyPendingApproval() {
        Loan existing = new Loan();
        existing.setStatus("pending_approval");
        loanStore.save(FROM, existing);

        UserSession session = new UserSession();

        loanFlow.handleApplyLoan(FROM, session);

        verify(screenService, never()).sendLoanTenureOptions(anyString());
    }

    // ==================== TENURE SELECTION ====================

    @Test
    void selectingATenurePopulatesSessionAndPromptsForAmount() {
        UserSession session = new UserSession();

        loanFlow.handleTenureSelect(FROM, "tenure_2", session);

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

        loanFlow.handleEnterLoanAmount(FROM, "abc", session);
        
        assertThat(session.getLoanAmount()).isNull();
    }

    @Test
    void loanAmountBelowMinimumIsRejected() {
        UserSession session = new UserSession();
        session.setLoanLimit(20000);

        loanFlow.handleEnterLoanAmount(FROM, "500", session);

        assertThat(session.getLoanAmount()).isNull();
    }

    @Test
    void loanAmountAboveTenureLimitIsRejected() {
        UserSession session = new UserSession();
        session.setLoanLimit(20000);
        session.setLoanTenureMonths(1);

        loanFlow.handleEnterLoanAmount(FROM, "25000", session);

        assertThat(session.getLoanAmount()).isNull();
    }

    @Test
    void validLoanAmountShowsBreakdown() {
        UserSession session = new UserSession();
        session.setLoanLimit(20000);
        session.setLoanTenureMonths(1);

        loanFlow.handleEnterLoanAmount(FROM, "15000", session);

        assertThat(session.getLoanAmount()).isEqualTo(15000);
        assertThat(session.getStep()).isEqualTo("loan_confirm");
        assertThat(session.getCurrentMenu()).isEqualTo("loan_breakdown_menu");
        verify(screenService).sendLoanBreakdown(eq(FROM), any(), eq(1));
    }

    // ==================== ACCEPT / DECLINE ====================

    @Test
    void acceptingTheLoanPromptsForPayrollNumber() {
        UserSession session = new UserSession();

        loanFlow.handleAcceptLoan(FROM, session);

        assertThat(session.getStep()).isEqualTo("enter_loan_payroll_number");
    }

    @Test
    void decliningClearsSessionAndReturnsToMainMenu() {
        UserSession session = new UserSession();
        session.setLoanAmount(15000);
        session.setLoanTenureMonths(1);

        loanFlow.handleDeclineLoan(FROM, session);

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

        loanFlow.handleEnterPayrollNumber(FROM, "10000000", session); // wrong UPN

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

        loanFlow.handleEnterPayrollNumber(FROM, "10000000", session);

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

        loanFlow.handleEnterPayrollNumber(FROM, "19999999", session);

        Loan submitted = loanStore.findByPhoneNumber(FROM).orElseThrow();
        assertThat(submitted.getStatus()).isEqualTo("pending_approval");
        assertThat(submitted.getLoanAmount()).isEqualTo(15000);
        assertThat(submitted.getPayrollNumber()).isEqualTo("19999999");
        assertThat(submitted.getRefNo()).isNotBlank();
        assertThat(submitted.getApprovalCode()).matches("^\\d{6}$");
        assertThat(session.getLoanAmount()).isNull(); // session fields cleared after submission

        // WORKSTREAM G: Approval Code delivery now goes through the
        // outbox, atomically with the loan save.
        verify(outboxService).enqueueWithBusinessWrite(
                eq(OutboxEntryType.APPROVAL_CODE_SMS),
                argThat(payload -> payload instanceof OutboxPayloads.ApprovalCodeSmsPayload p
                        && p.phoneNumber().equals(FROM)
                        && p.message().contains(submitted.getApprovalCode())
                        && p.refNo().equals(submitted.getRefNo())),
                any()
        );
    }

    // ==================== BACK NAVIGATION ====================

    @Test
    void backFromLoanTenureMenuGoesToMainMenu() {
        UserSession session = new UserSession();
        session.setCurrentMenu("loan_tenure_menu");

        loanFlow.handleBack(FROM, session);

        verify(screenService).sendMainMenu(FROM);
    }

    @Test
    void backFromLoanAmountMenuGoesToTenureOptions() {
        UserSession session = new UserSession();
        session.setCurrentMenu("loan_amount_menu");

        loanFlow.handleBack(FROM, session);

        verify(screenService).sendLoanTenureOptions(FROM);
    }

    @Test
    void backFromBreakdownGoesToLoanAmountMenu() {
        UserSession session = new UserSession();
        session.setCurrentMenu("loan_breakdown_menu");
        session.setLoanLimit(20000);
        session.setLoanTenureMonths(1);

        loanFlow.handleBack(FROM, session);

        verify(screenService).sendLoanAmountMenu(FROM, 20000, 1);
    }

    @Test
    void backWithNoTrackedContextIsContextAware() {
        UserSession session = new UserSession(); // currentMenu is null

        loanFlow.handleBack(FROM, session);

        verify(screenService).sendHomeScreen(FROM, session);
    }
}
