package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.LoanStore;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import com.mfstechnologies.mymobi.validation.FieldValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Optional;

/**
 * Approve Loan and Cancel Loan flows - both reached from a loan that is
 * currently pending_approval. Direct equivalent of the corresponding
 * sections of handleButton() / handleTextInput() in the Node.js
 * version.
 *
 * NOTE ON SCOPE: this covers Approve/Cancel only. Pay Loan (early
 * repayment on an approved loan) is the next piece to port.
 */
@Service
public class LoanApprovalFlowService {

    private static final Logger log = LoggerFactory.getLogger(LoanApprovalFlowService.class);
    private static final int MAX_APPROVAL_ATTEMPTS = 3;

    private final ScreenMessageService screenService;
    private final WhatsAppMessageService messageService;
    private final LoanStore loanStore;
    private final RegisteredUserStore userStore;

    public LoanApprovalFlowService(
            ScreenMessageService screenService,
            WhatsAppMessageService messageService,
            LoanStore loanStore,
            RegisteredUserStore userStore
    ) {
        this.screenService = screenService;
        this.messageService = messageService;
        this.loanStore = loanStore;
        this.userStore = userStore;
    }

    // ==================== APPROVE LOAN ====================

    public Mono<Void> handleApproveLoanMenu(String to, UserSession session) {
        Optional<Loan> loan = loanStore.findByPhoneNumber(to);
        if (loan.isEmpty() || !"pending_approval".equals(loan.get().getStatus())) {
            return screenService.sendMainMenu(to);
        }

        return screenService.sendApproveLoanDetails(to, loan.get());
    }

    public Mono<Void> handleEnterApprovalCodeMenu(String to, UserSession session) {
        session.setStep("enter_approval_code");
        return messageService.sendTextMessage(to, "Enter Approval Code:");
    }

    public Mono<Void> handleEnterApprovalCode(String to, String text, UserSession session) {
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);
        if (loanOpt.isEmpty() || !"pending_approval".equals(loanOpt.get().getStatus())) {
            session.setStep("welcome");
            return screenService.sendWelcome(to);
        }
        Loan loan = loanOpt.get();

        if (text == null || !text.matches("^\\d{6}$")) {
            return messageService.sendTextMessage(to, "Invalid code. Please enter the 6-character Approval Code exactly as sent.");
        }

        if (!text.equals(loan.getApprovalCode())) {
            return recordFailedApprovalAttempt(to, session, loan, true);
        }

        session.setStep("approval_payroll_number");
        return messageService.sendTextMessage(to, "Enter Payroll Number to confirm approval:");
    }

    public Mono<Void> handleApprovalPayrollNumber(String to, String text, UserSession session) {
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);
        if (loanOpt.isEmpty() || !"pending_approval".equals(loanOpt.get().getStatus())) {
            session.setStep("welcome");
            return screenService.sendWelcome(to);
        }
        Loan loan = loanOpt.get();

        if (!FieldValidators.isValidUpn(text)) {
            return messageService.sendTextMessage(to, "UPN should be up to 11 digits and start with 1 or 2. Please try again.");
        }

        Optional<RegisteredUser> registeredUser = userStore.findByPhoneNumber(to);
        boolean matches = registeredUser.isPresent() && text.equals(registeredUser.get().getUpn());

        if (!matches) {
            return recordFailedApprovalAttempt(to, session, loan, false);
        }

        loan.setStatus("approved");
        loan.setApprovedAt(Instant.now());
        session.setStep("welcome");

        log.info("loan_approved to={} refNo={}", to, loan.getRefNo());

        return messageService.sendTextMessage(to, "Your loan approval has been received. Thank you for using MyMobi.")
                .then(screenService.sendWelcome(to));
    }

    private Mono<Void> recordFailedApprovalAttempt(String to, UserSession session, Loan loan, boolean isCodeAttempt) {
        int attempts;
        if (isCodeAttempt) {
            loan.setApprovalCodeAttempts(loan.getApprovalCodeAttempts() + 1);
            attempts = loan.getApprovalCodeAttempts();
        } else {
            loan.setApprovalPayrollAttempts(loan.getApprovalPayrollAttempts() + 1);
            attempts = loan.getApprovalPayrollAttempts();
        }

        if (attempts >= MAX_APPROVAL_ATTEMPTS) {
            loanStore.delete(to);
            session.setStep("welcome");
            return messageService.sendTextMessage(to, "Too many incorrect attempts. Your loan application has been cancelled for your security.")
                    .then(screenService.sendWelcome(to));
        }

        int attemptsLeft = MAX_APPROVAL_ATTEMPTS - attempts;
        String what = isCodeAttempt ? "Approval Code" : "Payroll Number";
        return messageService.sendTextMessage(to, "Incorrect " + what + ". You have " + attemptsLeft + " attempt(s) remaining.");
    }

    // ==================== CANCEL LOAN ====================

    public Mono<Void> handleCancelLoan(String to, UserSession session) {
        return screenService.sendCancelLoanConfirm(to);
    }

    public Mono<Void> handleCancelLoanYes(String to, UserSession session) {
        loanStore.delete(to);
        log.info("loan_cancelled to={}", to);
        return messageService.sendTextMessage(to, "Your loan application has been cancelled.")
                .then(screenService.sendMainMenu(to));
    }

    public Mono<Void> handleCancelLoanNo(String to, UserSession session) {
        return screenService.sendMainMenu(to);
    }
}
