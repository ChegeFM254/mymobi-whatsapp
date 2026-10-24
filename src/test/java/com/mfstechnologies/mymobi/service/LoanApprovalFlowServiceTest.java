package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.LoanBreakdown;
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
 * LoanApprovalFlowService methods. No more .block() calls or
 * Mono.empty() stubs anywhere.
 *
 * WORKSTREAM C (persistence): RegisteredUserStore and LoanStore are now
 * Postgres-backed - both are wired to fake, in-memory-backed
 * repositories (see FakeRepositories) so they keep behaving like real,
 * working collaborators, exactly as they did with the old
 * ConcurrentHashMap.
 *
 * PERSISTENCE FIX: these tests used to assert only on the in-memory
 * state of the same Loan object the test itself created. That could
 * never have caught a missing loanStore.save(...) call, because
 * FakeRepositories' backing HashMap stores and returns that very same
 * Java object reference - a real database hands back a fresh, detached
 * copy on every read, where mutating it without saving loses the
 * change. So every test that expects a mutation to stick now also
 * verifies loanRepository.save(...) was genuinely called, after
 * clearInvocations(...) wipes the setup's own save() from the history
 * (otherwise that earlier call alone would satisfy the verification).
 *
 * WORKSTREAM G (outbox pattern): OutboxService is mocked here, with
 * enqueueWithBusinessWrite stubbed to actually run the given business
 * write (the loan save) as the real one does inside its transaction -
 * otherwise the mock would silently swallow the Runnable and the
 * approval would never be saved.
 */
@ExtendWith(MockitoExtension.class)
class LoanApprovalFlowServiceTest {

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
    private LoanApprovalFlowService approvalFlow;

    @BeforeEach
    void setUp() {
        FakeRepositories.wireAsInMemoryStore(loanRepository, Loan::getPhoneNumber);
        loanStore = new LoanStore(loanRepository);
        FakeRepositories.wireAsInMemoryStore(registeredUserRepository, RegisteredUser::getPhoneNumber);
        userStore = new RegisteredUserStore(registeredUserRepository);
        approvalFlow = new LoanApprovalFlowService(screenService, messageService, outboxService, loanStore, userStore);

        // enqueueWithBusinessWrite runs the given business write (here,
        // the loan save) just as the real one does inside its
        // transaction - without this the mock would silently swallow the
        // Runnable and the approval would never actually be saved.
        lenient().when(outboxService.enqueueWithBusinessWrite(any(), any(), any())).thenAnswer(invocation -> {
            Runnable businessWrite = invocation.getArgument(2);
            businessWrite.run();
            return new OutboxEntry();
        });
    }

    private Loan pendingLoan() {
        Loan loan = new Loan();
        loan.setLoanAmount(15000);
        loan.setTenureMonths(1);
        loan.setRefNo("REF12345");
        loan.setApprovalCode("654321");
        loan.setStatus("pending_approval");
        loan.setDueDate("2026-08-19");
        loan.setBreakdown(new LoanBreakdown(15000, 2943, 32057, 14442, 14592, 150));
        return loan;
    }

    // ==================== APPROVE LOAN MENU ====================

    @Test
    void approveLoanMenuShowsDetailsWhenLoanIsPendingApproval() {
        loanStore.save(FROM, pendingLoan());
        UserSession session = new UserSession();

        approvalFlow.handleApproveLoanMenu(FROM, session);

        verify(screenService).sendApproveLoanDetails(eq(FROM), any());
    }

    @Test
    void approveLoanMenuRedirectsWhenNoLoanExists() {
        UserSession session = new UserSession();

        approvalFlow.handleApproveLoanMenu(FROM, session);

        verify(screenService).sendMainMenu(FROM);
        verify(screenService, never()).sendApproveLoanDetails(anyString(), any());
    }

    // ==================== APPROVAL CODE ====================

    @Test
    void correctApprovalCodeAdvancesToPayrollNumberStep() {
        loanStore.save(FROM, pendingLoan());
        UserSession session = new UserSession();

        approvalFlow.handleEnterApprovalCode(FROM, "654321", session);

        assertThat(session.getStep()).isEqualTo("approval_payroll_number");
    }

    @Test
    void wrongApprovalCodeIncrementsAndPersistsTheAttemptCounter() {
        Loan loan = pendingLoan();
        loanStore.save(FROM, loan);
        clearInvocations(loanRepository);
        UserSession session = new UserSession();

        approvalFlow.handleEnterApprovalCode(FROM, "000000", session);

        assertThat(loan.getApprovalCodeAttempts()).isEqualTo(1);
        assertThat(session.getStep()).isNotEqualTo("approval_payroll_number");
        // PERSISTENCE FIX: the incremented counter must actually be saved,
        // or the 3-attempt lockout can never accumulate across messages.
            verify(loanRepository).save(loan);
        // WORKSTREAM E: the Flow must be re-sent so the retry also
        // happens securely, not by falling back to typing in chat.
        verify(screenService).sendCodeEntryFlow(eq(FROM), anyString(), anyString());
    }

    @Test
    void thirdWrongApprovalCodeCancelsTheLoanWithoutAnUnnecessarySave() {
        Loan loan = pendingLoan();
        loan.setApprovalCodeAttempts(2);
        loanStore.save(FROM, loan);
        clearInvocations(loanRepository);
        UserSession session = new UserSession();

        approvalFlow.handleEnterApprovalCode(FROM, "000000", session);

        assertThat(loanStore.findByPhoneNumber(FROM)).isEmpty();
        verify(loanRepository).deleteById(FROM);
        verify(loanRepository, never()).save(any());
    }

    // ==================== APPROVAL PAYROLL NUMBER ====================

    @Test
    void matchingPayrollNumberApprovesAndPersistsTheLoan() {
        Loan loan = pendingLoan();
        loanStore.save(FROM, loan);
        RegisteredUser user = new RegisteredUser();
        user.setUpn("19999999");
        userStore.save(FROM, user);
        clearInvocations(loanRepository);

        UserSession session = new UserSession();

        approvalFlow.handleApprovalPayrollNumber(FROM, "19999999", session);

        assertThat(loan.getStatus()).isEqualTo("approved");
        assertThat(loan.getApprovedAt()).isNotNull();
        // PERSISTENCE FIX: without this save, approving never stuck - the
        // database kept pending_approval and Main Menu kept offering
        // Approve Loan.
        verify(loanRepository).save(loan);
        verify(screenService).sendHomeScreen(FROM, session);
        verify(screenService, never()).sendWelcome(anyString());
    }

    @Test
    void nonMatchingPayrollNumberIncrementsAndPersistsASeparateAttemptCounter() {
        Loan loan = pendingLoan();
        loanStore.save(FROM, loan);
        RegisteredUser user = new RegisteredUser();
        user.setUpn("19999999");
        userStore.save(FROM, user);
        clearInvocations(loanRepository);

        UserSession session = new UserSession();

        approvalFlow.handleApprovalPayrollNumber(FROM, "10000000", session);

        assertThat(loan.getApprovalPayrollAttempts()).isEqualTo(1);
        assertThat(loan.getApprovalCodeAttempts()).isZero();
        assertThat(loan.getStatus()).isEqualTo("pending_approval");
        verify(loanRepository).save(loan);
    }
        @Test
    void thirdWrongPayrollNumberCancelsTheLoanWithoutAnUnnecessarySave() {
        Loan loan = pendingLoan();
        loan.setApprovalPayrollAttempts(2);
        loanStore.save(FROM, loan);
        RegisteredUser user = new RegisteredUser();
        user.setUpn("19999999");
        userStore.save(FROM, user);
        clearInvocations(loanRepository);

        UserSession session = new UserSession();

        approvalFlow.handleApprovalPayrollNumber(FROM, "10000000", session);

        assertThat(loanStore.findByPhoneNumber(FROM)).isEmpty();
        verify(loanRepository).deleteById(FROM);
        verify(loanRepository, never()).save(any());
    }

    // ==================== CANCEL LOAN ====================

    @Test
    void confirmingCancelRemovesTheLoan() {
        loanStore.save(FROM, pendingLoan());
        UserSession session = new UserSession();

        approvalFlow.handleCancelLoanYes(FROM, session);

        assertThat(loanStore.findByPhoneNumber(FROM)).isEmpty();
    }

    @Test
    void decliningCancelKeepsTheLoanPending() {
        loanStore.save(FROM, pendingLoan());
        UserSession session = new UserSession();

        approvalFlow.handleCancelLoanNo(FROM, session);

        assertThat(loanStore.findByPhoneNumber(FROM)).isPresent();
                verify(screenService).sendMainMenu(FROM);
    }

    // ==================== LOAN_APPROVAL (WORKSTREAM G) ====================

    @Test
    void approvingALoanRaisesALoanApprovalRequestCarryingTheDisbursementAmount() {
        loanStore.save(FROM, pendingLoan());
        RegisteredUser user = new RegisteredUser();
        user.setUpn("19999999");
        userStore.save(FROM, user);
        UserSession session = new UserSession();

        approvalFlow.handleApprovalPayrollNumber(FROM, "19999999", session);

        // pendingLoan() uses a breakdown whose disbursement is 32057.
        verify(outboxService).enqueueWithBusinessWrite(
                eq(OutboxEntryType.LOAN_APPROVAL),
                eq(new LoanService.ApprovalRequest(FROM, "REF12345", 32057)),
                any()
        );
    }

    @Test
    void theApprovalIsOnlySavedInsideTheOutboxCallNeverSeparately() {
        // Replaces the setUp stub with one that does NOT run the business
        // write. If handleApprovalPayrollNumber also saved the loan
        // directly, outside enqueueWithBusinessWrite, the repository would
        // see a save() here - which would break the atomicity with the
        // LOAN_APPROVAL request. (The in-memory fake shares object
        // references, so checking the loan's status couldn't tell us
        // this; checking for the save() call itself can.)
        doReturn(new OutboxEntry()).when(outboxService).enqueueWithBusinessWrite(any(), any(), any());
        loanStore.save(FROM, pendingLoan());
        RegisteredUser user = new RegisteredUser();
        user.setUpn("19999999");
        userStore.save(FROM, user);
        clearInvocations(loanRepository);
        UserSession session = new UserSession();

        approvalFlow.handleApprovalPayrollNumber(FROM, "19999999", session);

        verify(loanRepository, never()).save(any());
    }

    @Test
    void aWrongPayrollNumberRaisesNoApprovalRequest() {
        loanStore.save(FROM, pendingLoan());
        RegisteredUser user = new RegisteredUser();
        user.setUpn("19999999");
        userStore.save(FROM, user);
        UserSession session = new UserSession();

        approvalFlow.handleApprovalPayrollNumber(FROM, "10000000", session);

        verifyNoInteractions(outboxService);
    }

    @Test
    void aWrongApprovalCodeRaisesNoApprovalRequest() {
        loanStore.save(FROM, pendingLoan());
        UserSession session = new UserSession();

        approvalFlow.handleEnterApprovalCode(FROM, "000000", session);

        verifyNoInteractions(outboxService);
    }

    @Test
    void anApprovalAttemptWithNoPendingLoanRaisesNoApprovalRequest() {
        UserSession session = new UserSession();

        approvalFlow.handleApprovalPayrollNumber(FROM, "19999999", session);

        verifyNoInteractions(outboxService);
    }
}
