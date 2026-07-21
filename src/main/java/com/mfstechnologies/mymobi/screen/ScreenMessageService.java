package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.IncomingMessage;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private static final Set<String> TRIGGER_WORDS = Set.of("hi", "hello", "loan", "start");
    private static final String TRIGGER_SUBSTRING = "531";

    private static final Duration DEBOUNCE = Duration.ofMillis(800);

    private static final Set<String> EDIT_FIELD_IDS = Set.of(
            "edit_firstname", "edit_lastname", "edit_upn", "edit_nationalid", "edit_mobilenumber"
    );

    private static final Set<String> TENURE_IDS = Set.of("tenure_1", "tenure_2", "tenure_3");
    private static final String PAY_INSTALLMENTS_PREFIX = "pay_installments_";

    private final SessionStore sessionStore;
    private final ScreenMessageService screenService;
    private final WhatsAppMessageService messageService;
    private final AuthenticationFlowService authFlowService;
    private final RegistrationFlowService registrationFlowService;
    private final ForgotPinFlowService forgotPinFlowService;
    private final OptOutFlowService optOutFlowService;
    private final LoanApplicationFlowService loanApplicationFlowService;
    private final LoanApprovalFlowService loanApprovalFlowService;
    private final LoanPaymentFlowService loanPaymentFlowService;
    private final PayslipFlowService payslipFlowService;
    private final LoanDocumentFlowService loanDocumentFlowService;
    private final InactivityTimeoutService inactivityTimeoutService;

    public ConversationService(
            SessionStore sessionStore,
            ScreenMessageService screenService,
            WhatsAppMessageService messageService,
            AuthenticationFlowService authFlowService,
            RegistrationFlowService registrationFlowService,
            ForgotPinFlowService forgotPinFlowService,
            OptOutFlowService optOutFlowService,
            LoanApplicationFlowService loanApplicationFlowService,
            LoanApprovalFlowService loanApprovalFlowService,
            LoanPaymentFlowService loanPaymentFlowService,
            PayslipFlowService payslipFlowService,
            LoanDocumentFlowService loanDocumentFlowService,
            InactivityTimeoutService inactivityTimeoutService
    ) {
        this.sessionStore = sessionStore;
        this.screenService = screenService;
        this.messageService = messageService;
        this.authFlowService = authFlowService;
        this.registrationFlowService = registrationFlowService;
        this.forgotPinFlowService = forgotPinFlowService;
        this.optOutFlowService = optOutFlowService;
        this.loanApplicationFlowService = loanApplicationFlowService;
        this.loanApprovalFlowService = loanApprovalFlowService;
        this.loanPaymentFlowService = loanPaymentFlowService;
        this.payslipFlowService = payslipFlowService;
        this.loanDocumentFlowService = loanDocumentFlowService;
        this.inactivityTimeoutService = inactivityTimeoutService;
    }

    public Mono<Void> handleIncomingMessage(IncomingMessage message) {
        String from = message.from();
        if (from == null) {
            log.warn("message_missing_from_field");
            return Mono.empty();
        }

        messageService.resetSendTurn(from);

        UserSession session = sessionStore.getOrCreate(from);

        inactivityTimeoutService.resetTimeout(from);

        if (isDebounced(session)) {
            log.info("debounced_duplicate_input from={}", from);
            return Mono.empty();
        }
        session.setLastProcessedAt(Instant.now());

        boolean isFreshWelcomeTrigger = message.hasText()
                && isTriggerWord(message.text())
                && "welcome".equals(session.getStep())
                && session.isNewSession();

        if (isFreshWelcomeTrigger) {
            session.setNewSession(false);
            return screenService.sendWelcome(from);
        }

        if (message.hasButton()) {
            return handleButton(from, message.buttonId(), session);
        }

        if (message.hasText()) {
            return handleText(from, message.text(), session);
        }

        return Mono.empty();
    }

    private boolean isDebounced(UserSession session) {
        Instant lastProcessed = session.getLastProcessedAt();
        if (lastProcessed == null) {
            return false;
        }
        return Duration.between(lastProcessed, Instant.now()).compareTo(DEBOUNCE) < 0;
    }

    private boolean isTriggerWord(String text) {
        String normalized = text.trim().toLowerCase();
        return TRIGGER_WORDS.contains(normalized) || normalized.contains(TRIGGER_SUBSTRING);
    }

    private Mono<Void> handleButton(String to, String buttonId, UserSession session) {
        log.info("button_tapped to={} buttonId={}", to, buttonId);

        if (EDIT_FIELD_IDS.contains(buttonId)) {
            return registrationFlowService.handleEditFieldSelect(to, buttonId, session);
        }
        if (TENURE_IDS.contains(buttonId)) {
            return loanApplicationFlowService.handleTenureSelect(to, buttonId, session);
        }
        if (buttonId != null && buttonId.startsWith(PAY_INSTALLMENTS_PREFIX)) {
            return loanPaymentFlowService.handlePayInstallmentsSelect(to, buttonId, session);
        }

        return switch (buttonId) {
            case "civil_servants" -> authFlowService.handleCivilServants(to, session);
            case "login_menu" -> authFlowService.handleLoginMenu(to, session);
            case "logout" -> authFlowService.handleLogout(to, session);
            case "home" -> screenService.sendHomeScreen(to, session);
            case "back" -> loanApplicationFlowService.handleBack(to, session);

            case "buy_airtime" -> messageService.sendTextMessage(to, "Buy Airtime is coming soon. Thank you for your patience.");

            case "register_menu" -> registrationFlowService.handleRegisterMenu(to, session);
            case "optin_yes" -> registrationFlowService.handleOptInYes(to, session);
            case "optin_no" -> registrationFlowService.handleOptInNo(to, session);
            case "accept_tc" -> registrationFlowService.handleAcceptTerms(to, session);
            case "decline_tc" -> registrationFlowService.handleDeclineTerms(to, session);
            case "confirm_details" -> registrationFlowService.handleConfirmDetails(to, session);
            case "edit_details" -> registrationFlowService.handleEditDetails(to, session);
            case "exit_edit" -> registrationFlowService.handleExitEdit(to, session);

            case "forgot_pin" -> forgotPinFlowService.handleForgotPin(to, session);
            case "opt_out" -> optOutFlowService.handleOptOut(to, session);

            case "apply_loan" -> loanApplicationFlowService.handleApplyLoan(to, session);
            case "start_loan_amount_entry" -> loanApplicationFlowService.handleStartLoanAmountEntry(to, session);
            case "accept_loan" -> loanApplicationFlowService.handleAcceptLoan(to, session);
            case "decline_loan" -> loanApplicationFlowService.handleDeclineLoan(to, session);

            case "approve_loan_menu" -> loanApprovalFlowService.handleApproveLoanMenu(to, session);
            case "enter_approval_code_menu" -> loanApprovalFlowService.handleEnterApprovalCodeMenu(to, session);
            case "cancel_loan" -> loanApprovalFlowService.handleCancelLoan(to, session);
            case "confirm_cancel_loan_yes" -> loanApprovalFlowService.handleCancelLoanYes(to, session);
            case "confirm_cancel_loan_no" -> loanApprovalFlowService.handleCancelLoanNo(to, session);

            case "pay_loan_menu" -> loanPaymentFlowService.handlePayLoanMenu(to, session);
            case "confirm_pay_loan" -> loanPaymentFlowService.handleConfirmPayLoan(to, session);
            case "cancel_pay_loan" -> loanPaymentFlowService.handleCancelPayLoan(to, session);

            case "payslip_menu" -> payslipFlowService.handlePayslipMenu(to, session);
            case "confirm_payslip" -> payslipFlowService.handleConfirmPayslip(to, session);
            case "cancel_payslip" -> payslipFlowService.handleCancelPayslip(to, session);

            case "loan_statement_menu" -> loanDocumentFlowService.handleLoanStatementMenu(to, session);
            case "confirm_loan_statement" -> loanDocumentFlowService.handleConfirmLoanStatement(to, session);
            case "cancel_loan_statement" -> loanDocumentFlowService.handleCancelLoanStatement(to, session);

            case "loan_clearance_menu" -> loanDocumentFlowService.handleLoanClearanceMenu(to, session);
            case "confirm_loan_clearance" -> loanDocumentFlowService.handleConfirmLoanClearance(to, session);
            case "cancel_loan_clearance" -> loanDocumentFlowService.handleCancelLoanClearance(to, session);

            default ->
                    messageService.sendTextMessage(to, "You selected: " + buttonId + " (this flow is not ported yet, coming in a future update).");
        };
    }

    private Mono<Void> handleText(String to, String text, UserSession session) {
        log.info("text_received to={} step={}", to, session.getStep());

        String step = session.getStep();

        if (EDIT_FIELD_IDS.contains(step)) {
            return registrationFlowService.handleEditFieldText(to, text, session);
        }

        return switch (step) {
            case "login_enter_upn" -> authFlowService.handleLoginEnterUpn(to, text, session);
            case "login_enter_pin" -> authFlowService.handleLoginEnterPin(to, text, session);
            case "login_enter_verification_code" -> authFlowService.handleLoginEnterVerificationCode(to, text, session);

            case "first_name" -> registrationFlowService.handleFirstName(to, text, session);
            case "last_name" -> registrationFlowService.handleLastName(to, text, session);
            case "upn" -> registrationFlowService.handleUpnField(to, text, session);
            case "national_id" -> registrationFlowService.handleNationalId(to, text, session);
            case "mobile_number" -> registrationFlowService.handleMobileNumber(to, text, session);
            case "enter_otp" -> registrationFlowService.handleEnterOtp(to, text, session);
            case "enter_new_pin" -> registrationFlowService.handleEnterNewPin(to, text, session);
            case "confirm_new_pin" -> registrationFlowService.handleConfirmNewPin(to, text, session);

            case "forgot_pin_enter_otp" -> forgotPinFlowService.handleEnterOtp(to, text, session);
            case "forgot_pin_enter_new_pin" -> forgotPinFlowService.handleEnterNewPin(to, text, session);
            case "forgot_pin_confirm_new_pin" -> forgotPinFlowService.handleConfirmNewPin(to, text, session);

            case "opt_out_confirmation" -> optOutFlowService.handleOptOutConfirmation(to, text, session);
            case "opt_out_pin" -> optOutFlowService.handleOptOutPin(to, text, session);

            case "enter_loan_amount" -> loanApplicationFlowService.handleEnterLoanAmount(to, text, session);
            case "enter_loan_payroll_number" -> loanApplicationFlowService.handleEnterPayrollNumber(to, text, session);

            case "enter_approval_code" -> loanApprovalFlowService.handleEnterApprovalCode(to, text, session);
            case "approval_payroll_number" -> loanApprovalFlowService.handleApprovalPayrollNumber(to, text, session);

            case "enter_payslip_months" -> payslipFlowService.handleEnterPayslipMonths(to, text, session);

            default ->
                    messageService.sendTextMessage(to, "Got it. This part of the conversation is not wired up yet. Try again in a future update!");
        };
    }
}
