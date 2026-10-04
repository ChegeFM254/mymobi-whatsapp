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

import java.time.Instant;
import java.util.Optional;

/**
 * WORKSTREAM B (reactive -> synchronous): every method here used to
 * return Mono<Void>, chaining follow-up screens with .then(). All
 * converted to plain blocking void methods with sequential statements.
 *
 * PERSISTENCE FIX (regression from WORKSTREAM C): loanStore.findByPhoneNumber()
 * returns a detached entity (it's Postgres-backed now, not the old
 * in-memory map that handed back the same live object every time), so
 * mutating the returned Loan alone never persisted anything. Before this
 * fix, approving a loan never stuck (the status stayed pending_approval
 * in the database, so Main Menu kept offering Approve Loan), and the
 * failed-attempt counters never accumulated, so the 3-attempt lockout
 * could never actually trigger. Every mutation here is now followed by an
 * explicit loanStore.save(...).
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

    public void handleApproveLoanMenu(String to, UserSession session) {
        Optional<Loan> loan = loanStore.findByPhoneNumber(to);
        if (loan.isEmpty() || !"pending_approval".equals(loan.get().getStatus())) {
            screenService.sendMainMenu(to);
            return;
        }

        screenService.sendApproveLoanDetails(to, loan.get());
    }
    
    public void handleEnterApprovalCodeMenu(String to, UserSession session) {
        session.setStep("enter_approval_code");
        screenService.sendCodeEntryFlow(to, "Enter Approval Code:", "Enter Code");
    }

    public void handleEnterApprovalCode(String to, String text, UserSession session) {
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);
        if (loanOpt.isEmpty() || !"pending_approval".equals(loanOpt.get().getStatus())) {
            session.setStep("welcome");
            screenService.sendHomeScreen(to, session);
            return;
        }
        Loan loan = loanOpt.get();

        if (text == null || !text.matches("^\\d{6}$")) {
            messageService.sendTextMessage(to, "Invalid code. Please enter the 6-character Approval Code exactly as sent.");
            return;
        }

        if (!text.equals(loan.getApprovalCode())) {
            recordFailedApprovalAttempt(to, session, loan, true);
            return;
        }

        session.setStep("approval_payroll_number");
        messageService.sendTextMessage(to, "Enter Payroll Number to confirm approval:");
    }

    public void handleApprovalPayrollNumber(String to, String text, UserSession session) {
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);
        if (loanOpt.isEmpty() || !"pending_approval".equals(loanOpt.get().getStatus())) {
            session.setStep("welcome");
            screenService.sendHomeScreen(to, session);
            return;
        }
        Loan loan = loanOpt.get();

        if (!FieldValidators.isValidUpn(text)) {
            messageService.sendTextMessage(to, "UPN should be up to 11 digits and start with 1 or 2. Please try again.");
            return;
        }

        Optional<RegisteredUser> registeredUser = userStore.findByPhoneNumber(to);
        boolean matches = registeredUser.isPresent() && text.equals(registeredUser.get().getUpn());

        if (!matches) {
            recordFailedApprovalAttempt(to, session, loan, false);
            return;
        }

        loan.setStatus("approved");
        loan.setApprovedAt(Instant.now());
        loanStore.save(to, loan);
        session.setStep("welcome");

        log.info("loan_approved to={} refNo={}", to, loan.getRefNo());

        String message = String.format(
                "Your loan approval has been submitted. KES %,d will be sent to your M-Pesa account. Thank you for using MyMobi.",
                loan.getBreakdown().disbursement()
        );
        messageService.sendTextMessage(to, message);
        screenService.sendHomeScreen(to, session);
    }
    
    private void recordFailedApprovalAttempt(String to, UserSession session, Loan loan, boolean isCodeAttempt) {
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
            messageService.sendTextMessage(to, "Too many incorrect attempts. Your loan application has been cancelled for your security.");
            screenService.sendHomeScreen(to, session);
            return;
        }

        // Persist the incremented counter so it actually accumulates
        // across messages - without this, every attempt started from 0
        // again and the lockout above could never trigger.
        loanStore.save(to, loan);

        int attemptsLeft = MAX_APPROVAL_ATTEMPTS - attempts;
        String what = isCodeAttempt ? "Approval Code" : "Payroll Number";
        messageService.sendTextMessage(to, "Incorrect " + what + ". You have " + attemptsLeft + " attempt(s) remaining.");

        // WORKSTREAM E: only the Approval Code is Flow-based - re-send it
        // so the retry also happens securely. Payroll Number was never a
        // Flow to begin with, so it keeps its existing plain-text retry
        // behavior (no re-prompt needed - the person just retypes it).
        if (isCodeAttempt) {
            screenService.sendCodeEntryFlow(to, "Enter Approval Code:", "Enter Code");
        }
    }

    // ==================== CANCEL LOAN ====================

    public void handleCancelLoan(String to, UserSession session) {
        screenService.sendCancelLoanConfirm(to);
    }

    public void handleCancelLoanYes(String to, UserSession session) {
        loanStore.delete(to);
        log.info("loan_cancelled to={}", to);
        messageService.sendTextMessage(to, "Your loan application has been cancelled.");
        screenService.sendMainMenu(to);
    }

    public void handleCancelLoanNo(String to, UserSession session) {
        screenService.sendMainMenu(to);
    }
}
