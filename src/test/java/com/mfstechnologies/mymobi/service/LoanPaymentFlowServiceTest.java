package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.LoanBreakdown;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.LoanStore;
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
class LoanPaymentFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;

    private LoanStore loanStore;
    private LoanPaymentFlowService paymentFlow;

    @BeforeEach
    void setUp() {
        loanStore = new LoanStore();
        paymentFlow = new LoanPaymentFlowService(screenService, messageService, loanStore);
    }

    private Loan approvedLoan(int tenureMonths, int installmentsPaid) {
        Loan loan = new Loan();
        loan.setTenureMonths(tenureMonths);
        loan.setInstallmentsPaid(installmentsPaid);
        loan.setBreakdown(new LoanBreakdown(15000, 2943, 32057, 14442, 150));
        loan.setStatus("approved");
        loan.setRefNo("MVCAGHD1");
        return loan;
    }

    // ==================== PAY LOAN MENU ====================

    @Test
    void payLoanMenuShowsOptionsForAnApprovedLoan() {
        loanStore.save(FROM, approvedLoan(3, 0));
        UserSession session = new UserSession();
        when(screenService.sendPayLoanOptions(FROM, 3, 14442)).thenReturn(Mono.empty());

        paymentFlow.handlePayLoanMenu(FROM, session).block();

        verify(screenService).sendPayLoanOptions(FROM, 3, 14442);
    }

    @Test
    void payLoanMenuRedirectsWhenLoanIsNotApproved() {
        Loan pending = approvedLoan(3, 0);
        pending.setStatus("pending_approval");
        loanStore.save(FROM, pending);

        UserSession session = new UserSession();
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        paymentFlow.handlePayLoanMenu(FROM, session).block();

        verify(screenService, never()).sendPayLoanOptions(anyString(), anyInt(), anyInt());
    }

    // ==================== INSTALLMENT SELECTION ====================

    @Test
    void selectingAValidInstallmentCountShowsConfirmation() {
        loanStore.save(FROM, approvedLoan(3, 0));
        UserSession session = new UserSession();
        when(screenService.sendPayLoanConfirm(FROM, 2, 28884, 14442, 1)).thenReturn(Mono.empty());

        paymentFlow.handlePayInstallmentsSelect(FROM, "pay_installments_2", session).block();

        assertThat(session.getPendingPaymentInstallments()).isEqualTo(2);
        verify(screenService).sendPayLoanConfirm(FROM, 2, 28884, 14442, 1);
    }

    @Test
    void selectingMoreInstallmentsThanRemainingIsRejected() {
        loanStore.save(FROM, approvedLoan(3, 2)); // only 1 remaining
        UserSession session = new UserSession();
        when(screenService.sendPayLoanOptions(FROM, 1, 14442)).thenReturn(Mono.empty());

        paymentFlow.handlePayInstallmentsSelect(FROM, "pay_installments_2", session).block();

        assertThat(session.getPendingPaymentInstallments()).isNull();
    }

    // ==================== CONFIRM PAYMENT ====================
@Test
    void confirmingAPartialPaymentUpdatesInstallmentsAndStaysApproved() {
        Loan loan = approvedLoan(3, 0);
        loanStore.save(FROM, loan);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        paymentFlow.handleConfirmPayLoan(FROM, session).block();

        assertThat(loan.getInstallmentsPaid()).isEqualTo(1);
        assertThat(loan.getStatus()).isEqualTo("approved");
        assertThat(session.getPendingPaymentInstallments()).isNull();
        verify(messageService).sendTextMessage(eq(FROM),
                eq("Your installment of KES 14,442 Ref: MVCAGHD1 has been paid. You have a loan balance of KES 28,884. Thank you for using MyMobi services."));
    }

    @Test
    void confirmingTheFinalPaymentMarksTheLoanAsPaid() {
        Loan loan = approvedLoan(3, 2); // 1 remaining
        loanStore.save(FROM, loan);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendHomeScreen(FROM, session)).thenReturn(Mono.empty());

        paymentFlow.handleConfirmPayLoan(FROM, session).block();

        assertThat(loan.getInstallmentsPaid()).isEqualTo(3);
        assertThat(loan.getStatus()).isEqualTo("paid");
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
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        paymentFlow.handleConfirmPayLoan(FROM, session).block();

        assertThat(loan.getInstallmentsPaid()).isEqualTo(2);
        verify(messageService).sendTextMessage(eq(FROM),
                eq("Your installment of KES 28,884 Ref: MVCAGHD1 has been paid. You have a loan balance of KES 14,442. Thank you for using MyMobi services."));
    }
    @Test
    void paymentInProgressGuardPreventsADoubleTapFromPayingTwice() {
        Loan loan = approvedLoan(3, 0);
        loan.setPaymentInProgress(true); // simulate a payment already underway
        loanStore.save(FROM, loan);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);

        paymentFlow.handleConfirmPayLoan(FROM, session).block();

        assertThat(loan.getInstallmentsPaid()).isZero(); // unaffected by the second tap
        verifyNoInteractions(messageService);
    }

    // ==================== CANCEL ====================

    @Test
    void cancelingPaymentClearsSelectionAndReturnsToMainMenu() {
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(2);
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        paymentFlow.handleCancelPayLoan(FROM, session).block();

        assertThat(session.getPendingPaymentInstallments()).isNull();
    }
}
