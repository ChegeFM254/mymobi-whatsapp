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
        return loan;
    }

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
        when(screenService.sendEmergencyLoanMenu(FROM, "pending_approval")).thenReturn(Mono.empty());

        paymentFlow.handlePayLoanMenu(FROM, session).block();

        verify(screenService, never()).sendPayLoanOptions(anyString(), anyInt(), anyInt());
    }

    @Test
    void selectingAValidInstallmentCountShowsConfirmation() {
        loanStore.save(FROM, approvedLoan(3, 0));
        UserSession session = new UserSession();
        when(screenService.sendPayLoanConfirm(FROM, 2, 28884, 1)).thenReturn(Mono.empty());

        paymentFlow.handlePayInstallmentsSelect(FROM, "pay_installments_2", session).block();

        assertThat(session.getPendingPaymentInstallments()).isEqualTo(2);
        verify(screenService).sendPayLoanConfirm(FROM, 2, 28884, 1);
    }

    @Test
    void selectingMoreInstallmentsThanRemainingIsRejected() {
        loanStore.save(FROM, approvedLoan(3, 2));
        UserSession session = new UserSession();
        when(screenService.sendPayLoanOptions(FROM, 1, 14442)).thenReturn(Mono.empty());

        paymentFlow.handlePayInstallmentsSelect(FROM, "pay_installments_2", session).block();

        assertThat(session.getPendingPaymentInstallments()).isNull();
    }

    @Test
    void confirmingAPartialPaymentUpdatesInstallmentsAndStaysApproved() {
        Loan loan = approvedLoan(3, 0);
        loanStore.save(FROM, loan);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendEmergencyLoanMenu(FROM, "approved")).thenReturn(Mono.empty());

        paymentFlow.handleConfirmPayLoan(FROM, session).block();

        assertThat(loan.getInstallmentsPaid()).isEqualTo(1);
        assertThat(loan.getStatus()).isEqualTo("approved");
        assertThat(session.getPendingPaymentInstallments()).isNull();
    }

    @Test
    void confirmingTheFinalPaymentMarksTheLoanAsPaid() {
        Loan loan = approvedLoan(3, 2);
        loanStore.save(FROM, loan);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        paymentFlow.handleConfirmPayLoan(FROM, session).block();

        assertThat(loan.getInstallmentsPaid()).isEqualTo(3);
        assertThat(loan.getStatus()).isEqualTo("paid");
        verify(screenService).sendWelcome(FROM);
    }

    @Test
    void paymentInProgressGuardPreventsADoubleTapFromPayingTwice() {
        Loan loan = approvedLoan(3, 0);
        loan.setPaymentInProgress(true);
        loanStore.save(FROM, loan);
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(1);

        paymentFlow.handleConfirmPayLoan(FROM, session).block();

        assertThat(loan.getInstallmentsPaid()).isZero();
        verifyNoInteractions(messageService);
    }

    @Test
    void cancelingPaymentClearsSelectionAndReturnsToEmergencyLoanMenu() {
        UserSession session = new UserSession();
        session.setPendingPaymentInstallments(2);
        when(screenService.sendEmergencyLoanMenu(FROM, "approved")).thenReturn(Mono.empty());

        paymentFlow.handleCancelPayLoan(FROM, session).block();

        assertThat(session.getPendingPaymentInstallments()).isNull();
    }
}