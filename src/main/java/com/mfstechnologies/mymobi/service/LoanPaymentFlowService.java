package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.LoanStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;

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

    public Mono<Void> handlePayLoanMenu(String to, UserSession session) {
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);
        if (loanOpt.isEmpty() || !"approved".equals(loanOpt.get().getStatus())) {
            String status = loanOpt.map(Loan::getStatus).orElse(null);
            return screenService.sendEmergencyLoanMenu(to, status);
        }

        Loan loan = loanOpt.get();
        int remaining = loan.getTenureMonths() - loan.getInstallmentsPaid();
        if (remaining <= 0) {
            return screenService.sendWelcome(to);
        }

        int monthlyInstallment = loan.getBreakdown() != null ? loan.getBreakdown().monthlyInstallment() : 14442;
        return screenService.sendPayLoanOptions(to, remaining, monthlyInstallment);
    }

    public Mono<Void> handlePayInstallmentsSelect(String to, String installmentsButtonId, UserSession session) {
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);
        if (loanOpt.isEmpty() || !"approved".equals(loanOpt.get().getStatus())) {
            return screenService.sendWelcome(to);
        }
        Loan loan = loanOpt.get();

        Integer selected = parseInstallmentsCount(installmentsButtonId);
        int remaining = loan.getTenureMonths() - loan.getInstallmentsPaid();

        if (selected == null || selected < 1 || selected > remaining) {
            log.warn("Invalid installment selection {} for {} (remaining={})", installmentsButtonId, to, remaining);
            int monthlyInstallment = loan.getBreakdown() != null ? loan.getBreakdown().monthlyInstallment() : 14442;
            return screenService.sendPayLoanOptions(to, remaining, monthlyInstallment);
        }

        session.setPendingPaymentInstallments(selected);
        int monthlyInstallment = loan.getBreakdown() != null ? loan.getBreakdown().monthlyInstallment() : 14442;
        int total = monthlyInstallment * selected;
        int remainingAfter = remaining - selected;

        return screenService.sendPayLoanConfirm(to, selected, total, remainingAfter);
    }

    public Mono<Void> handleConfirmPayLoan(String to, UserSession session) {
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);
        if (loanOpt.isEmpty() || !"approved".equals(loanOpt.get().getStatus()) || session.getPendingPaymentInstallments() == null) {
            session.setPendingPaymentInstallments(null);
            return screenService.sendWelcome(to);
        }
        Loan loan = loanOpt.get();

        if (loan.isPaymentInProgress()) {
            return Mono.empty();
        }
        loan.setPaymentInProgress(true);

        int installments = session.getPendingPaymentInstallments();

        log.info("mpesa_stk_push_simulated to={} installments={}", to, installments);

        loan.setInstallmentsPaid(loan.getInstallmentsPaid() + installments);
        session.setPendingPaymentInstallments(null);
        loan.setPaymentInProgress(false);

        boolean fullyPaid = loan.getInstallmentsPaid() >= loan.getTenureMonths();

        if (fullyPaid) {
            loan.setStatus("paid");
            log.info("loan_fully_paid to={} refNo={}", to, loan.getRefNo());
            return messageService.sendTextMessage(to, "Payment received. Your loan is now fully paid off. Thank you for using MyMobi.")
                    .then(screenService.sendWelcome(to));
        }

        int remaining = loan.getTenureMonths() - loan.getInstallmentsPaid();
        return messageService.sendTextMessage(to, "Payment received. You have " + remaining + " installment(s) remaining.")
                .then(screenService.sendEmergencyLoanMenu(to, "approved"));
    }

    public Mono<Void> handleCancelPayLoan(String to, UserSession session) {
        session.setPendingPaymentInstallments(null);
        return screenService.sendEmergencyLoanMenu(to, "approved");
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
