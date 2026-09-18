package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.LoanStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * WORKSTREAM B (reactive -> synchronous): every method here used to
 * return Mono<Void>, chaining follow-up screens with .then() (or
 * returning Mono.empty() as a no-op duplicate-payment guard). All
 * converted to plain blocking void methods with sequential statements;
 * the guard is now a plain early return.
 */
@Service
public class LoanPaymentFlowService {

    private static final Logger log = LoggerFactory.getLogger(LoanPaymentFlowService.class);

    private final ScreenMessageService screenService;
    private final WhatsAppMessageService messageService;
    private final LoanStore loanStore;

    public LoanPaymentFlowService(
            ScreenMessageService screenService,
            WhatsAppMessageService messageService,
            LoanStore loanStore
    ) {
        this.screenService = screenService;
        this.messageService = messageService;
        this.loanStore = loanStore;
    }

    public void handlePayLoanMenu(String to, UserSession session) {
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);
        if (loanOpt.isEmpty() || !"approved".equals(loanOpt.get().getStatus())) {
            screenService.sendMainMenu(to);
            return;
        }

        Loan loan = loanOpt.get();
        int remaining = loan.getTenureMonths() - loan.getInstallmentsPaid();
        if (remaining <= 0) {
            screenService.sendHomeScreen(to, session);
            return;
        }

        int monthlyInstallment = loan.getBreakdown() != null ? loan.getBreakdown().monthlyInstallment() : 14442;
        screenService.sendPayLoanOptions(to, remaining, monthlyInstallment);
    }

    public void handlePayInstallmentsSelect(String to, String installmentsButtonId, UserSession session) {
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);
        if (loanOpt.isEmpty() || !"approved".equals(loanOpt.get().getStatus())) {
            screenService.sendHomeScreen(to, session);
            return;
        }
        Loan loan = loanOpt.get();

        Integer selected = parseInstallmentsCount(installmentsButtonId);
        int remaining = loan.getTenureMonths() - loan.getInstallmentsPaid();

        if (selected == null || selected < 1 || selected > remaining) {
            log.warn("Invalid installment selection {} for {} (remaining={})", installmentsButtonId, to, remaining);
            int monthlyInstallment = loan.getBreakdown() != null ? loan.getBreakdown().monthlyInstallment() : 14442;
            screenService.sendPayLoanOptions(to, remaining, monthlyInstallment);
            return;
        }

        session.setPendingPaymentInstallments(selected);
        int monthlyInstallment = loan.getBreakdown() != null ? loan.getBreakdown().monthlyInstallment() : 14442;
        int total = monthlyInstallment * selected;
        int remainingAfter = remaining - selected;
        int remainingBalanceAfter = monthlyInstallment * remainingAfter;

        screenService.sendPayLoanConfirm(to, selected, total, remainingBalanceAfter, remainingAfter);
    }

    public void handleConfirmPayLoan(String to, UserSession session) {
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);
        if (loanOpt.isEmpty() || !"approved".equals(loanOpt.get().getStatus()) || session.getPendingPaymentInstallments() == null) {
            session.setPendingPaymentInstallments(null);
            screenService.sendHomeScreen(to, session);
            return;
        }
        Loan loan = loanOpt.get();

        if (loan.isPaymentInProgress()) {
            return;
        }
        loan.setPaymentInProgress(true);

        int installments = session.getPendingPaymentInstallments();
        int monthlyInstallment = loan.getBreakdown() != null ? loan.getBreakdown().monthlyInstallment() : 14442;
        int payAmount = monthlyInstallment * installments;

        log.info("mpesa_stk_push_simulated to={} installments={} payAmount={}", to, installments, payAmount);

        loan.setInstallmentsPaid(loan.getInstallmentsPaid() + installments);
        session.setPendingPaymentInstallments(null);
        loan.setPaymentInProgress(false);

        boolean fullyPaid = loan.getInstallmentsPaid() >= loan.getTenureMonths();

        if (fullyPaid) {
            loan.setStatus("paid");
            log.info("loan_fully_paid to={} refNo={}", to, loan.getRefNo());
            String message = String.format(
                    "Your installment of KES %,d Ref: %s has been paid. Your loan has been fully paid. Thank you for using MyMobi services.",
                    payAmount, loan.getRefNo()
            );
            messageService.sendTextMessage(to, message);
            screenService.sendHomeScreen(to, session);
            return;
        }

        int remainingInstallments = loan.getTenureMonths() - loan.getInstallmentsPaid();
        int remainingBalance = monthlyInstallment * remainingInstallments;
        String message = String.format(
                "Your installment of KES %,d Ref: %s has been paid. You have a loan balance of KES %,d. Thank you for using MyMobi services.",
                payAmount, loan.getRefNo(), remainingBalance
        );
        messageService.sendTextMessage(to, message);
        screenService.sendMainMenu(to);
    }

    public void handleCancelPayLoan(String to, UserSession session) {
        session.setPendingPaymentInstallments(null);
        screenService.sendMainMenu(to);
    }

    private Integer parseInstallmentsCount(String buttonId) {
        if (buttonId == null || !buttonId.startsWith("pay_installments_")) {
            return null;
        }
        try {
            return Integer.parseInt(buttonId.substring("pay_installments_".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
