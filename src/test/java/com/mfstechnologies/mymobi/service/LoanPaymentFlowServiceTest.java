package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.LoanBreakdown;
import com.mfstechnologies.mymobi.model.OutboxEntry;
import com.mfstechnologies.mymobi.model.OutboxEntryType;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.LoanRepository;
import com.mfstechnologies.mymobi.session.LoanStore;
import com.mfstechnologies.mymobi.testsupport.FakeRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WORKSTREAM B (reactive -> synchronous): rewritten for the now-void
 * LoanPaymentFlowService methods. No more .block() calls or Mono.empty()
 * stubs anywhere.
 *
 * WORKSTREAM C (persistence): LoanStore is now Postgres-backed -
 * loanStore here is wired to a fake, in-memory-backed repository (see
 * FakeRepositories) so it keeps behaving like a real, working
 * collaborator, exactly as it did with the old ConcurrentHashMap.
 *
 * WORKSTREAM F (mock service abstraction layer): the inline M-Pesa STK
 * push simulation is now delegated to MpesaService (mocked here) -
 * confirmingAPartialPaymentUpdatesAndPersistsInstallmentsAndStaysApproved
 * and confirmingTheFinalPaymentMarksAndPersistsTheLoanAsPaid both verify
 * the call directly, since this flow previously never sent any STK push
 * message at all.
 *
 * PERSISTENCE FIX: these tests used to assert only on the in-memory
 * state of the same Loan object the test itself created, which could
 * never have caught a missing loanStore.save(...) call - FakeRepositories'
 * backing HashMap stores and returns that very same Java object
 * reference, whereas a real database hands back a fresh detached copy on
 * every read. Every test expecting a change to stick now also verifies
 * loanRepository.save(...), after clearInvocations(...) wipes the
 * setup's own save() from the history (otherwise that earlier call alone
 * would satisfy the verification).
 *
 * WORKSTREAM G (outbox pattern): OutboxService is mocked here, with
 * enqueueWithBusinessWrite stubbed to actually run the given business
 * write (the final loan save) as the real one does inside its
 * transaction - otherwise the mock would silently swallow the Runnable
 * and the payment would never be saved. The two guard saves around the
 * STK push are plain saves, so a successful payment is still two
 * save() calls in total: one direct, one inside the outbox call.
 */
@ExtendWith(MockitoExtension.class)
class LoanPaymentFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;
    @Mock
    private MpesaService mpesaService;
    @Mock
    private OutboxService outboxService;
    @Mock
    private LoanRepository loanRepository;

    private LoanStore loanStore;
    private LoanPaymentFlowService paymentFlow;
        @BeforeEach
    void setUp() {
        FakeRepositories.wireAsInMemoryStore(loanRepository, Loan::getPhoneNumber);
        loanStore = new LoanStore(loanRepository);
        paymentFlow = new LoanPaymentFlowService(screenService, messageService, mpesaService, outboxService, loanStore);

        // enqueueWithBusinessWrite runs the given business write (here,
        // the final loan save) just as the real one does inside its
        // transaction - without this the mock would silently swallow the
        // Runnable and the payment would never actually be saved.
        lenient().when(outboxService.enqueueWithBusinessWrite(any(), any(), any())).thenAnswer(invocation -> {
            Runnable businessWrite = invocation.getArgument(2);
            businessWrite.run();
            return new OutboxEntry();
        });
    }
    
    private Loan approvedLoan(int tenureMonths, int installmentsPaid) {
        Loan loan = new Loan();
        loan.setTenureMonths(tenureMonths);
        loan.setInstallmentsPaid(installmentsPaid);
        loan.setBreakdown(new LoanBreakdown(15000, 2943, 32057, 14442, 14592, 150));
        loan.setStatus("approved");
        loan.setRefNo("MVCAGHD1");
        return loan;
    }

    // ==================== PAY LOAN MENU ====================

    @Test
    void payLoanMenuShowsOptionsForAnApprovedLoan() {
        loanStore.save(FROM, approvedLoan(3, 0));
        UserSession session = new UserSession();

        paymentFlow.handlePayLoanMenu(FROM, session);

        verify(screenService).sendPayLoanOptions(FROM, 3, 14442);
    }

    @Test
    void payLoanMenuRedirectsWhenLoanIsNotApproved() {
        Loan pending = approvedLoan(3, 0);
        pending.setStatus("pending_approval");
        loanStore.save(FROM, pending);

        UserSession session = new UserSession();

        paymentFlow.handlePayLoanMenu(FROM, session);

        verify(screenService, never()).sendPayLoanOptions(anyString(), anyInt(), anyInt());
    }

    // ==================== INSTALLMENT SELECTION ====================

    @Test
    void selectingAValidInstallmentCountShowsConfirmation() {
        loanStore.save(FROM, approvedLoan(3, 0));
        UserSession session = new UserSession();

        paymentFlow.handlePayInstallmentsSelect(FROM, "pay_installments_2", session);

        assertThat(session.getPendingPaymentInstallments()).isEqualTo(2);
        verify(screenService).sendPayLoanConfirm(FROM, 2, 28884, 14442, 1);
    }

    @Test
    void selectingMoreInstallmentsThanRemainingIsRejected() {
        loanStore.save(FROM, approvedLoan(3, 2)); // only 1 remaining
        UserSession session = new UserSession();

        paymentFlow.handlePayInstallmentsSelect(FROM, "pay_installments_2", session);

        assertThat(session.getPendingPaymentInstallments()).isNull();
    }
        // ==================== CONFIRM PAYMENT ====================

    @Test
    void confirmingAPartialPaymentUpdatesAndPersistsInstallmentsAndStaysApproved() {
        Loan loan = approvedLoan(3, 0);
        loanStore.save(FROM, loan);
        clearInvocations(loanRepository);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);

        paymentFlow.handleConfirmPayLoan(FROM, session);

        assertThat(loan.getInstallmentsPaid()).isEqualTo(1);
        assertThat(loan.getStatus()).isEqualTo("approved");
        assertThat(loan.isPaymentInProgress()).isFalse();
        assertThat(session.getPendingPaymentInstallments()).isNull();
        // PERSISTENCE FIX: saved twice - once to persist the in-progress
        // guard before the STK push, once for the final state afterward.
        verify(loanRepository, times(2)).save(loan);
        verify(mpesaService).initiateStkPush(FROM, 14442, "loan_payment");
        verify(messageService).sendTextMessage(eq(FROM),
                eq("Your installment of KES 14,442 Ref: MVCAGHD1 has been paid. You have a loan balance of KES 28,884. Thank you for using MyMobi services."));
    }

    @Test
    void theGuardIsPersistedBeforeTheStkPushAndTheFinalStateAfterIt() {
        Loan loan = approvedLoan(3, 0);
        loanStore.save(FROM, loan);
        clearInvocations(loanRepository);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);

        paymentFlow.handleConfirmPayLoan(FROM, session);

        // The ordering is the whole point of the guard: it only protects
        // against a duplicate tap if it's already saved by the time the
        // (potentially slow) STK push is underway.
        InOrder inOrder = inOrder(loanRepository, mpesaService);
        inOrder.verify(loanRepository).save(loan);
        inOrder.verify(mpesaService).initiateStkPush(FROM, 14442, "loan_payment");
        inOrder.verify(loanRepository).save(loan);
    }

    @Test
    void confirmingTheFinalPaymentMarksAndPersistsTheLoanAsPaid() {
        Loan loan = approvedLoan(3, 2); // 1 remaining
        loanStore.save(FROM, loan);
        clearInvocations(loanRepository);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);

        paymentFlow.handleConfirmPayLoan(FROM, session);

        assertThat(loan.getInstallmentsPaid()).isEqualTo(3);
        assertThat(loan.getStatus()).isEqualTo("paid");
        // PERSISTENCE FIX: without these saves the loan never reached
        // "paid" in the database, so a Loan Clearance Letter could never
        // be generated.
        verify(loanRepository, times(2)).save(loan);
        verify(mpesaService).initiateStkPush(FROM, 14442, "loan_payment");
        verify(messageService).sendTextMessage(eq(FROM),
                eq("Your installment of KES 14,442 Ref: MVCAGHD1 has been paid. Your loan has been fully paid. Thank you for using MyMobi services."));
        verify(screenService).sendHomeScreen(FROM, session);
        verify(screenService, never()).sendWelcome(anyString());
    }
    
    @Test
    void payingTwoInstallmentsAtOnceShowsTheCorrectAmountAndRemainingBalance() {
        Loan loan = approvedLoan(3, 0);
        loanStore.save(FROM, loan);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(2); // paying 2 of 3 in one go

        paymentFlow.handleConfirmPayLoan(FROM, session);

        assertThat(loan.getInstallmentsPaid()).isEqualTo(2);
        verify(mpesaService).initiateStkPush(FROM, 28884, "loan_payment");
        verify(messageService).sendTextMessage(eq(FROM),
                eq("Your installment of KES 28,884 Ref: MVCAGHD1 has been paid. You have a loan balance of KES 14,442. Thank you for using MyMobi services."));
    }

    @Test
    void aFailedStkPushReleasesTheGuardAndLeavesInstallmentsUntouched() {
        Loan loan = approvedLoan(3, 0);
        loanStore.save(FROM, loan);
        clearInvocations(loanRepository);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);
        doThrow(new RuntimeException("stk failed")).when(mpesaService).initiateStkPush(anyString(), anyDouble(), anyString());

        assertThatThrownBy(() -> paymentFlow.handleConfirmPayLoan(FROM, session))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("stk failed");

        // Without releasing the guard, one failed STK push would leave
        // paymentInProgress stuck at true and block every future payment
        // attempt for this person permanently.
        assertThat(loan.isPaymentInProgress()).isFalse();
        assertThat(loan.getInstallmentsPaid()).isZero();
        assertThat(loan.getStatus()).isEqualTo("approved");
        verify(loanRepository, times(2)).save(loan); // guard set, then guard released
        verifyNoInteractions(messageService);
    }

    @Test
    void paymentInProgressGuardPreventsADoubleTapFromPayingTwice() {
        Loan loan = approvedLoan(3, 0);
        loan.setPaymentInProgress(true); // simulate a payment already underway
        loanStore.save(FROM, loan);
        clearInvocations(loanRepository);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);

        paymentFlow.handleConfirmPayLoan(FROM, session);

        assertThat(loan.getInstallmentsPaid()).isZero(); // unaffected by the second tap
        verifyNoInteractions(messageService);
        verifyNoInteractions(mpesaService);
        verify(loanRepository, never()).save(any());
    }

    // ==================== CANCEL ====================

    @Test
    void cancelingPaymentClearsSelectionAndReturnsToMainMenu() {
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(2);

        paymentFlow.handleCancelPayLoan(FROM, session);

                assertThat(session.getPendingPaymentInstallments()).isNull();
    }

    // ==================== LOAN_REPAYMENT_RECORD (WORKSTREAM G) ====================

    @Test
    void confirmingAPaymentRaisesARepaymentRequestCarryingTheInstallmentsAndAmount() {
        loanStore.save(FROM, approvedLoan(3, 0));
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(2);

        paymentFlow.handleConfirmPayLoan(FROM, session);

        verify(outboxService).enqueueWithBusinessWrite(
                eq(OutboxEntryType.LOAN_REPAYMENT_RECORD),
                eq(new LoanService.RepaymentRequest(FROM, "MVCAGHD1", 2, 28884)),
                any()
        );
    }

    @Test
    void theFinalPaymentRaisesARepaymentRequestToo() {
        loanStore.save(FROM, approvedLoan(3, 2)); // 1 remaining
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);

        paymentFlow.handleConfirmPayLoan(FROM, session);

        verify(outboxService).enqueueWithBusinessWrite(
                eq(OutboxEntryType.LOAN_REPAYMENT_RECORD),
                eq(new LoanService.RepaymentRequest(FROM, "MVCAGHD1", 1, 14442)),
                any()
        );
    }

    @Test
    void theFinalStateIsOnlySavedInsideTheOutboxCallNeverSeparately() {
        // Replaces the setUp stub with one that does NOT run the business
        // write. The only direct save left is the in-progress guard
        // before the STK push, so the repository must see exactly one
        // save() here. If the final state were also saved directly,
        // outside enqueueWithBusinessWrite, there would be two - which
        // would break the atomicity with the LOAN_REPAYMENT_RECORD request.
        doReturn(new OutboxEntry()).when(outboxService).enqueueWithBusinessWrite(any(), any(), any());
        Loan loan = approvedLoan(3, 0);
        loanStore.save(FROM, loan);
        clearInvocations(loanRepository);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);

        paymentFlow.handleConfirmPayLoan(FROM, session);

        verify(loanRepository, times(1)).save(loan);
    }

    @Test
    void aFailedStkPushRaisesNoRepaymentRequest() {
        loanStore.save(FROM, approvedLoan(3, 0));
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);
        doThrow(new RuntimeException("stk failed")).when(mpesaService).initiateStkPush(anyString(), anyDouble(), anyString());

        assertThatThrownBy(() -> paymentFlow.handleConfirmPayLoan(FROM, session))
                .hasMessage("stk failed");

        verifyNoInteractions(outboxService);
    }

    @Test
    void aBlockedDoubleTapRaisesNoRepaymentRequest() {
        Loan loan = approvedLoan(3, 0);
        loan.setPaymentInProgress(true);
        loanStore.save(FROM, loan);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);

        paymentFlow.handleConfirmPayLoan(FROM, session);

        verifyNoInteractions(outboxService);
    }
}
