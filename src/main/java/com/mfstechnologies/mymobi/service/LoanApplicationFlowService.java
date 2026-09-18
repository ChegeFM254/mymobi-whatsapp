package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.LoanBreakdown;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.TenureOption;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.LoanStore;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import com.mfstechnologies.mymobi.validation.CodeGenerator;
import com.mfstechnologies.mymobi.validation.FieldValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * WORKSTREAM B (reactive -> synchronous): every method here used to
 * return Mono<Void>, chaining follow-up screens with .then(). All
 * converted to plain blocking void methods with sequential statements.
 * deliverApprovalCodeAfterDelay's error handling changed from
 * .doOnError().subscribe() to a plain try/catch, since sendTextMessage()
 * now throws directly instead of carrying errors on a reactive error
 * channel.
 */
@Service
public class LoanApplicationFlowService {

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationFlowService.class);
    private static final int MIN_LOAN_AMOUNT = 1000;
    private static final int MAX_PAYROLL_ATTEMPTS = 3;

    private static final Map<String, TenureOption> TENURE_OPTIONS = Map.of(
            "tenure_1", new TenureOption(1, 20000, "1 Month"),
            "tenure_2", new TenureOption(2, 40000, "2 Months"),
            "tenure_3", new TenureOption(3, 60000, "3 Months")
    );

    private final ScreenMessageService screenService;
    private final WhatsAppMessageService messageService;
    private final LoanStore loanStore;
    private final RegisteredUserStore userStore;
    private final LoanCalculationService calculationService;

    public LoanApplicationFlowService(
            ScreenMessageService screenService,
            WhatsAppMessageService messageService,
            LoanStore loanStore,
            RegisteredUserStore userStore,
            LoanCalculationService calculationService
    ) {
        this.screenService = screenService;
        this.messageService = messageService;
        this.loanStore = loanStore;
        this.userStore = userStore;
        this.calculationService = calculationService;
    }

    // ==================== APPLY LOAN ====================

    public void handleApplyLoan(String to, UserSession session) {
        Optional<Loan> existing = loanStore.findByPhoneNumber(to);
        boolean hasActiveLoan = existing.isPresent()
                && ("pending_approval".equals(existing.get().getStatus()) || "approved".equals(existing.get().getStatus()));

        if (hasActiveLoan) {
            messageService.sendTextMessage(to, "You already have an active loan. Please complete or repay it before applying for a new one.");
            screenService.sendMainMenu(to);
                        return;
        }

        session.setCurrentMenu("loan_tenure_menu");
        screenService.sendLoanTenureOptions(to);
    }

    public void handleTenureSelect(String to, String tenureId, UserSession session) {
        TenureOption tenure = TENURE_OPTIONS.get(tenureId);
        if (tenure == null) {
            log.warn("Unknown tenure id {} for {}", tenureId, to);
            screenService.sendLoanTenureOptions(to);
            return;
        }

        session.setLoanTenureMonths(tenure.months());
        session.setLoanLimit(tenure.limit());
        sendEnterLoanAmountPrompt(to, session);
    }

    public void handleStartLoanAmountEntry(String to, UserSession session) {
        sendEnterLoanAmountPrompt(to, session);
    }

    private void sendEnterLoanAmountPrompt(String to, UserSession session) {
        session.setStep("enter_loan_amount");
        messageService.sendTextMessage(to,
                "Enter Loan Amount (e.g., 35000). Your limit is KES " + session.getLoanLimit() + ":");
    }

    public void handleEnterLoanAmount(String to, String text, UserSession session) {
        Integer amount = parsePositiveInteger(text);
        if (amount == null) {
            messageService.sendTextMessage(to, "Please enter a valid loan amount in KES (numbers only, e.g. 35000).");
            return;
        }
        if (amount < MIN_LOAN_AMOUNT) {
            messageService.sendTextMessage(to, "Minimum loan amount is KES 1,000. Please enter a higher amount.");
            return;
        }
        if (amount > session.getLoanLimit()) {
            messageService.sendTextMessage(to,
                    "That exceeds your loan limit of KES " + session.getLoanLimit() + ". Please enter a lower amount.");
            return;
        }

        session.setLoanAmount(amount);
        session.setStep("loan_confirm");
        session.setCurrentMenu("loan_breakdown_menu");

        LoanBreakdown breakdown = calculationService.calculateBreakdown(amount, session.getLoanTenureMonths());
        screenService.sendLoanBreakdown(to, breakdown, session.getLoanTenureMonths());
    }

    // ==================== BREAKDOWN: ACCEPT / DECLINE ====================

    public void handleAcceptLoan(String to, UserSession session) {
        session.setStep("enter_loan_payroll_number");
        messageService.sendTextMessage(to, "Please Enter Payroll Number to complete the transaction:");
    }

    public void handleDeclineLoan(String to, UserSession session) {
        clearLoanApplicationFields(session);
        messageService.sendTextMessage(to, "Loan application declined.");
        screenService.sendMainMenu(to);
    }

    // ==================== PAYROLL NUMBER + SUBMISSION ====================

    public void handleEnterPayrollNumber(String to, String text, UserSession session) {
        if (!FieldValidators.isValidUpn(text)) {
            messageService.sendTextMessage(to, "UPN should be up to 11 digits and start with 1 or 2. Please try again.");
            return;
        }
                Optional<RegisteredUser> registeredUser = userStore.findByPhoneNumber(to);
        boolean matches = registeredUser.isPresent() && text.equals(registeredUser.get().getUpn());

        if (!matches) {
            session.setPayrollNumberAttempts(session.getPayrollNumberAttempts() + 1);

            if (session.getPayrollNumberAttempts() >= MAX_PAYROLL_ATTEMPTS) {
                clearLoanApplicationFields(session);
                messageService.sendTextMessage(to, "Too many incorrect attempts. Your loan application has been cancelled for your security.");
                screenService.sendMainMenu(to);
                return;
            }

            int attemptsLeft = MAX_PAYROLL_ATTEMPTS - session.getPayrollNumberAttempts();
            messageService.sendTextMessage(to, "That Payroll Number does not match our records. You have " + attemptsLeft + " attempt(s) remaining.");
            return;
        }

        submitLoanApplication(to, text, session);
    }

    private void submitLoanApplication(String to, String payrollNumber, UserSession session) {
        LoanBreakdown breakdown = calculationService.calculateBreakdown(session.getLoanAmount(), session.getLoanTenureMonths());
        String refNo = CodeGenerator.generateLoanRefNo();
        String approvalCode = CodeGenerator.generateSixDigitCode();
        String dueDate = LocalDate.now().plusMonths(session.getLoanTenureMonths()).toString();

        Loan loan = new Loan();
        loan.setLoanAmount(session.getLoanAmount());
        loan.setTenureMonths(session.getLoanTenureMonths());
        loan.setBreakdown(breakdown);
        loan.setPayrollNumber(payrollNumber);
        loan.setRefNo(refNo);
        loan.setApprovalCode(approvalCode);
        loan.setDueDate(dueDate);
        loan.setStatus("pending_approval");
        loan.setInstallmentsPaid(0);
        loan.setSubmittedAt(Instant.now());
        loanStore.save(to, loan);

        log.info("loan_application_submitted to={} amount={} tenureMonths={} refNo={}",
                to, session.getLoanAmount(), session.getLoanTenureMonths(), refNo);

        clearLoanApplicationFields(session);
        session.setPayrollNumberAttempts(0);

        deliverApprovalCodeAfterDelay(to, approvalCode, refNo);

        messageService.sendTextMessage(to, "Your loan request has been submitted. Please wait for the approval code SMS from MyMobi.");
    }

    /**
     * Simulates SMS delivery of the approval code, arriving as a
     * separate WhatsApp message a few seconds later, followed directly
     * by the Approve Loan screen itself - skipping Main Menu entirely,
     * so the person doesn't need an extra tap to get to Approve Loan
     * right when the code they're waiting for actually arrives.
     * TODO: remove once a real SMS/backend delivers this for real.
     *
     * Includes a staleness check: only delivers if the loan is STILL the
     * same one, still pending - avoiding a confusing stale delivery if
     * the loan was cancelled or superseded in the meantime.
     */
    private void deliverApprovalCodeAfterDelay(String to, String approvalCode, String refNo) {
        CompletableFuture.runAsync(
                () -> {
                    Optional<Loan> current = loanStore.findByPhoneNumber(to);
                    boolean stillPending = current.isPresent()
                            && refNo.equals(current.get().getRefNo())
                            && "pending_approval".equals(current.get().getStatus());
                    
                    if (!stillPending) {
                        log.info("stale_approval_code_delivery_skipped to={} refNo={}", to, refNo);
                        return;
                    }

                    try {
                        messageService.sendTextMessage(to, "Approval Code " + approvalCode);
                        screenService.sendApproveLoanDetails(to, current.get());
                    } catch (Exception err) {
                        log.error("Failed to deliver approval code to {}: {}", to, err.getMessage());
                    }
                },
                CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS)
        );
    }

    // ==================== BACK NAVIGATION ====================

    /**
     * Centralized "Back" handling. Currently the only screens with
     * contextual back-navigation are the loan screens; anything else
     * (or no tracked context) falls back to sendHomeScreen (Main Menu
     * if authenticated, Welcome otherwise).
     */
    public void handleBack(String to, UserSession session) {
        String currentMenu = session.getCurrentMenu();

        if ("loan_tenure_menu".equals(currentMenu)) {
            screenService.sendMainMenu(to);
            return;
        }
        if ("loan_amount_menu".equals(currentMenu)) {
            session.setCurrentMenu("loan_tenure_menu");
            screenService.sendLoanTenureOptions(to);
            return;
        }
        if ("loan_breakdown_menu".equals(currentMenu)) {
            session.setCurrentMenu("loan_amount_menu");
            screenService.sendLoanAmountMenu(to, session.getLoanLimit(), session.getLoanTenureMonths());
            return;
        }

        screenService.sendHomeScreen(to, session);
    }

    private void clearLoanApplicationFields(UserSession session) {
        session.setLoanTenureMonths(null);
        session.setLoanLimit(null);
        session.setLoanAmount(null);
        session.setCurrentMenu(null);
    }

    private Integer parsePositiveInteger(String text) {
        if (text == null || !text.matches("^\\d+$")) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
