package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.OutboxEntryType;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import com.mfstechnologies.mymobi.validation.CodeGenerator;
import com.mfstechnologies.mymobi.validation.FieldValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The full Registration/KYC flow - OptIn, Terms, 6 typed KYC fields plus
 * Mobile Number auto-populated from WhatsApp (with Confirm/Edit), OTP
 * verification, and new PIN setup. Direct equivalent of the
 * corresponding sections of handleButton() / handleTextInput() in the
 * Node.js version, extended with Middle Name and Email Address - two
 * fields Node never had.
 *
 * KYC collection order: First Name, Middle Name, Last Name, Email
 * Address, UPN Number, National ID Number, then straight to
 * Confirmation - Mobile Number is no longer a typed step (see
 * WORKSTREAM D below).
 *
 * This is the largest single flow in the whole application - deliberately
 * given its own dedicated session, following the same incremental,
 * one-flow-at-a-time approach used throughout this rewrite.
 *
 * WORKSTREAM B (reactive -> synchronous): every method here used to
 * return Mono<Void>, chaining follow-up screens with .then(). All
 * converted to plain blocking void methods with sequential statements.
 *
 * WORKSTREAM D (login/registration simplification): Mobile Number is no
 * longer typed during registration - handleNationalId now auto-populates
 * it directly from the WhatsApp sender's own number (the "to" parameter
 * every method already receives) and goes straight to the confirmation
 * screen. handleMobileNumber (the old typed-entry handler) is removed
 * entirely, since nothing reaches that step anymore. Mobile Number is
 * ALSO removed from the editable fields entirely (EDIT_FIELD_LABELS,
 * the edit_mobilenumber switch case, and its validation) - it can now
 * only be changed by an admin directly, not by the person themselves via
 * Edit Details.
 *
 * WORKSTREAM E (WhatsApp Flows webview for PIN/OTP/Approval Code): both
 * OTP entry and new-PIN entry/confirmation now go through the WhatsApp
 * Flow webview instead of a plain text prompt. Every retry path (wrong
 * OTP, rejected new PIN, mismatched confirmation) also re-sends the Flow
 * afterward - without that, the person's only way to retry would be
 * falling back to typing the value directly into the chat, defeating the
 * whole point of using the Flow in the first place.
 *
 * WORKSTREAM G (outbox pattern): the direct smsService.sendSms(...) call
 * for the registration OTP is gone - handleConfirmDetails now calls
 * outboxService.enqueue(...) instead, a plain enqueue rather than
 * enqueueWithBusinessWrite (unlike LoanApplicationFlowService's Approval
 * Code case). There's genuinely no business write to pair atomically
 * with here - at this point in the flow nothing has been persisted yet;
 * the RegisteredUser isn't created until completeRegistration() runs,
 * well after the person has verified this OTP and set their PIN.
 */
@Service
public class RegistrationFlowService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationFlowService.class);
    private static final int MAX_OTP_ATTEMPTS = 3;

    private static final Map<String, String> EDIT_FIELD_LABELS = Map.of(
            "edit_firstname", "First Name",
            "edit_middlename", "Middle Name",
            "edit_lastname", "Last Name",
            "edit_emailaddress", "Email Address",
            "edit_upn", "UPN Number",
            "edit_nationalid", "National ID Number"
    );

    private final ScreenMessageService screenService;
    private final WhatsAppMessageService messageService;
    private final OutboxService outboxService;
    private final RegisteredUserStore userStore;
    private final PasswordEncoder passwordEncoder;

    public RegistrationFlowService(
            ScreenMessageService screenService,
            WhatsAppMessageService messageService,
            OutboxService outboxService,
            RegisteredUserStore userStore,
            PasswordEncoder passwordEncoder
    ) {
        this.screenService = screenService;
        this.messageService = messageService;
        this.outboxService = outboxService;
        this.userStore = userStore;
        this.passwordEncoder = passwordEncoder;
    }

    // ==================== ENTRY + OPT-IN ====================

    public void handleRegisterMenu(String to, UserSession session) {
        boolean alreadyRegistered = userStore.findByPhoneNumber(to)
                .map(user -> "active".equals(user.getStatus()))
                .orElse(false);

        if (alreadyRegistered) {
            messageService.sendTextMessage(to, "You already have an account registered on this number. Please use Log In instead.");
            screenService.sendCivilServantsMenu(to);
            return;
        }

        session.setStep("optin");
        screenService.sendOptIn(to);
    }

    public void handleOptInYes(String to, UserSession session) {
        session.setStep("tc");
        screenService.sendTerms(to);
    }

    public void handleOptInNo(String to, UserSession session) {
        screenService.sendWelcome(to);
    }

    public void handleAcceptTerms(String to, UserSession session) {
        session.setStep("first_name");
        messageService.sendTextMessage(to, "Enter First Name");
    }
    
    public void handleDeclineTerms(String to, UserSession session) {
        screenService.sendWelcome(to);
    }

    // ==================== KYC FIELD COLLECTION ====================
    // Order: First Name -> Middle Name -> Last Name -> Email Address ->
    // UPN Number -> National ID Number -> Confirmation (Mobile Number is
    // auto-populated from WhatsApp, not a typed step - see WORKSTREAM D).

    public void handleFirstName(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            messageService.sendTextMessage(to, "Please enter your First Name.");
            return;
        }
        session.setFirstName(text);
        session.setStep("middle_name");
        messageService.sendTextMessage(to, "Enter Middle Name");
    }

    public void handleMiddleName(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            messageService.sendTextMessage(to, "Please enter your Middle Name.");
            return;
        }
        session.setMiddleName(text);
        session.setStep("last_name");
        messageService.sendTextMessage(to, "Enter Last Name");
    }

    public void handleLastName(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            messageService.sendTextMessage(to, "Please enter your Last Name.");
            return;
        }
        session.setLastName(text);
        session.setStep("email_address");
        messageService.sendTextMessage(to, "Enter Email Address");
    }

    public void handleEmailAddress(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            messageService.sendTextMessage(to, "Please enter your Email Address.");
            return;
        }
        if (!FieldValidators.isValidEmail(text)) {
            messageService.sendTextMessage(to, "Please enter a valid Email Address (e.g. name@example.com).");
            return;
        }
        session.setEmailAddress(text);
        session.setStep("upn");
        messageService.sendTextMessage(to, "Enter UPN Number");
    }

    public void handleUpnField(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            messageService.sendTextMessage(to, "Please enter your UPN.");
            return;
        }
        if (!FieldValidators.isValidUpn(text)) {
            messageService.sendTextMessage(to, "UPN should be up to 11 digits and start with 1 or 2. Please try again.");
            return;
        }
        session.setUpn(text);
        session.setStep("national_id");
                messageService.sendTextMessage(to, "Enter National ID Number");
    }

    public void handleNationalId(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            messageService.sendTextMessage(to, "Please enter your National ID Number.");
            return;
        }
        if (!FieldValidators.isValidNationalId(text)) {
            messageService.sendTextMessage(to, "National ID should be exactly 8 digits and cannot start with 0. Please try again.");
            return;
        }
        session.setNationalId(text);
        // WORKSTREAM D: Mobile Number comes directly from WhatsApp itself
        // (the sender's own number) rather than being typed - straight to
        // Confirmation from here.
        session.setMobileNumber(to);
        screenService.sendConfirmation(to, session);
    }

    // ==================== CONFIRM / EDIT ====================

    public void handleConfirmDetails(String to, UserSession session) {
        String otp = CodeGenerator.generateFiveDigitCode();
        session.setOtp(otp);
        session.setOtpAttempts(0);
        session.setStep("enter_otp");

        outboxService.enqueue(OutboxEntryType.OTP_SMS, new OutboxPayloads.SmsPayload(to, "OTP " + otp));
        screenService.sendCodeEntryFlow(to, "An OTP has been sent to your M-Pesa number.\n\nPlease enter the OTP:", "Enter OTP");
    }

    public void handleEditDetails(String to, UserSession session) {
        screenService.sendEditOptions(to);
    }

    public void handleExitEdit(String to, UserSession session) {
        screenService.sendConfirmation(to, session);
    }

    public void handleEditFieldSelect(String to, String fieldId, UserSession session) {
        session.setStep(fieldId);
        String label = EDIT_FIELD_LABELS.getOrDefault(fieldId, "field");
        messageService.sendTextMessage(to, "Enter new " + label + ":");
    }

    public void handleEditFieldText(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            messageService.sendTextMessage(to, "Please enter a valid value.");
            return;
        }

        String step = session.getStep();

        if ("edit_upn".equals(step) && !FieldValidators.isValidUpn(text)) {
            messageService.sendTextMessage(to, "UPN should be up to 11 digits and start with 1 or 2. Please try again.");
            return;
        }
        if ("edit_nationalid".equals(step) && !FieldValidators.isValidNationalId(text)) {
            messageService.sendTextMessage(to, "National ID should be exactly 8 digits and cannot start with 0. Please try again.");
            return;
        }
        if ("edit_emailaddress".equals(step) && !FieldValidators.isValidEmail(text)) {
            messageService.sendTextMessage(to, "Please enter a valid Email Address (e.g. name@example.com).");
            return;
                    }

        switch (step) {
            case "edit_firstname" -> session.setFirstName(text);
            case "edit_middlename" -> session.setMiddleName(text);
            case "edit_lastname" -> session.setLastName(text);
            case "edit_emailaddress" -> session.setEmailAddress(text);
            case "edit_upn" -> session.setUpn(text);
            case "edit_nationalid" -> session.setNationalId(text);
            default -> log.warn("Unexpected edit step {} for {}", step, to);
        }

        screenService.sendConfirmation(to, session);
    }

    // ==================== OTP ====================

    public void handleEnterOtp(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            messageService.sendTextMessage(to, "Invalid OTP. Please enter a 5-digit number.");
            return;
        }

        if (text.equals(session.getOtp())) {
            session.setStep("enter_new_pin");
            screenService.sendCodeEntryFlow(to, "Create a new 5-digit PIN for your account.\n\nDo not share this PIN with anyone.", "Enter PIN");
            return;
        }

        session.setOtpAttempts(session.getOtpAttempts() + 1);

        if (session.getOtpAttempts() >= MAX_OTP_ATTEMPTS) {
            resetRegistrationFields(session);
            session.setStep("welcome");
            messageService.sendTextMessage(to, "Too many incorrect attempts. Your registration has been cancelled. Please try again.");
            screenService.sendWelcome(to);
            return;
        }

        int attemptsLeft = MAX_OTP_ATTEMPTS - session.getOtpAttempts();
        messageService.sendTextMessage(to, "Incorrect OTP. You have " + attemptsLeft + " attempt(s) remaining.");
        screenService.sendCodeEntryFlow(to, "Please enter the OTP:", "Enter OTP");
    }

    // ==================== NEW PIN SETUP ====================

    public void handleEnterNewPin(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            messageService.sendTextMessage(to, "Invalid PIN. Please enter exactly 5 digits.");
            screenService.sendCodeEntryFlow(to, "Create a new 5-digit PIN for your account.\n\nDo not share this PIN with anyone.", "Enter PIN");
            return;
        }
        if (text.equals(session.getOtp())) {
            messageService.sendTextMessage(to, "Your new PIN cannot be the same as the OTP. Please choose a different 5-digit PIN.");
            screenService.sendCodeEntryFlow(to, "Create a new 5-digit PIN for your account.\n\nDo not share this PIN with anyone.", "Enter PIN");
            return;
        }

        session.setNewPin(text);
        session.setStep("confirm_new_pin");
        screenService.sendCodeEntryFlow(to, "Please re-enter your new 5-digit PIN to confirm.", "Confirm PIN");
    }

    public void handleConfirmNewPin(String to, String text, UserSession session) {
        if (!text.equals(session.getNewPin())) {
                        session.setStep("enter_new_pin");
            screenService.sendCodeEntryFlow(to, "The PINs do not match. Please enter your new 5-digit PIN again:", "Enter PIN");
            return;
        }

        completeRegistration(to, session);
    }

    private void completeRegistration(String to, UserSession session) {
        RegisteredUser user = new RegisteredUser();
        user.setFirstName(session.getFirstName());
        user.setMiddleName(session.getMiddleName());
        user.setLastName(session.getLastName());
        user.setEmailAddress(session.getEmailAddress());
        user.setUpn(session.getUpn());
        user.setNationalId(session.getNationalId());
        user.setMobileNumber(session.getMobileNumber());
        user.setHashedPin(passwordEncoder.encode(session.getNewPin()));
        user.setStatus("active");
        userStore.save(to, user);

        log.info("user_registered to={} firstName={} lastName={}", to, session.getFirstName(), session.getLastName());

        resetRegistrationFields(session);
        session.setAuthenticated(true);

        messageService.sendTextMessage(to,
                "\uD83C\uDF89 Registration Complete!\n\n" +
                "Your account has been successfully set up.\n\n" +
                "\uD83D\uDD12 Security Notice:\n" +
                "\u2022 Your PIN is now active\n" +
                "\u2022 Do not share this PIN with anyone\n" +
                "\u2022 For your protection, we strongly recommend deleting this chat or the messages containing your PIN"
        );
        screenService.sendWelcome(to);
    }

    private void resetRegistrationFields(UserSession session) {
        session.setOtp(null);
        session.setOtpAttempts(0);
        session.setNewPin(null);
    }
}
