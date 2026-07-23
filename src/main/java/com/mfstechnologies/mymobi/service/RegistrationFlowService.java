package com.mfstechnologies.mymobi.service;

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
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The full Registration/KYC flow — OptIn, Terms, 5 KYC fields (with
 * Confirm/Edit), OTP verification, and new PIN setup. Direct equivalent
 * of the corresponding sections of handleButton() / handleTextInput()
 * in the Node.js version.
 *
 * This is the largest single flow in the whole application — deliberately
 * given its own dedicated session, following the same incremental,
 * one-flow-at-a-time approach used throughout this rewrite.
 */
@Service
public class RegistrationFlowService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationFlowService.class);
    private static final int MAX_OTP_ATTEMPTS = 3;

    private static final Map<String, String> EDIT_FIELD_LABELS = Map.of(
            "edit_firstname", "First Name",
            "edit_lastname", "Last Name",
            "edit_upn", "UPN",
            "edit_nationalid", "National ID",
            "edit_mobilenumber", "Mobile Number (Mpesa)"
    );

    private final ScreenMessageService screenService;
    private final WhatsAppMessageService messageService;
    private final RegisteredUserStore userStore;
    private final PasswordEncoder passwordEncoder;

    public RegistrationFlowService(
            ScreenMessageService screenService,
            WhatsAppMessageService messageService,
            RegisteredUserStore userStore,
            PasswordEncoder passwordEncoder
    ) {
        this.screenService = screenService;
        this.messageService = messageService;
        this.userStore = userStore;
        this.passwordEncoder = passwordEncoder;
    }

    // ==================== ENTRY + OPT-IN ====================

    public Mono<Void> handleRegisterMenu(String to, UserSession session) {
        boolean alreadyRegistered = userStore.findByPhoneNumber(to)
                .map(user -> "active".equals(user.getStatus()))
                .orElse(false);

        if (alreadyRegistered) {
            return messageService.sendTextMessage(to, "You already have an account registered on this number. Please use Log In instead.")
                    .then(screenService.sendCivilServantsMenu(to));
        }

        session.setStep("optin");
        return screenService.sendOptIn(to);
    }

    public Mono<Void> handleOptInYes(String to, UserSession session) {
        session.setStep("tc");
        return screenService.sendTerms(to);
    }

    public Mono<Void> handleOptInNo(String to, UserSession session) {
        return screenService.sendWelcome(to);
    }

    public Mono<Void> handleAcceptTerms(String to, UserSession session) {
        session.setStep("first_name");
        return messageService.sendTextMessage(to, "Enter your First Name");
    }

    public Mono<Void> handleDeclineTerms(String to, UserSession session) {
        return screenService.sendWelcome(to);
    }

    // ==================== KYC FIELD COLLECTION ====================

    public Mono<Void> handleFirstName(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            return messageService.sendTextMessage(to, "Please enter your First Name.");
        }
        session.setFirstName(text);
        session.setStep("last_name");
        return messageService.sendTextMessage(to, "Enter your Last Name");
    }

    public Mono<Void> handleLastName(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            return messageService.sendTextMessage(to, "Please enter your Last Name.");
        }
        session.setLastName(text);
        session.setStep("upn");
        return messageService.sendTextMessage(to, "Enter UPN");
    }

    public Mono<Void> handleUpnField(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            return messageService.sendTextMessage(to, "Please enter your UPN.");
        }
        if (!FieldValidators.isValidUpn(text)) {
            return messageService.sendTextMessage(to, "UPN should be up to 11 digits and start with 1 or 2. Please try again.");
        }
        session.setUpn(text);
        session.setStep("national_id");
        return messageService.sendTextMessage(to, "Enter National ID Number");
    }

    public Mono<Void> handleNationalId(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            return messageService.sendTextMessage(to, "Please enter your National ID Number.");
        }
        if (!FieldValidators.isValidNationalId(text)) {
            return messageService.sendTextMessage(to, "National ID should be exactly 8 digits and cannot start with 0. Please try again.");
        }
        session.setNationalId(text);
        session.setStep("mobile_number");
        return messageService.sendTextMessage(to, "Enter Mobile Number (Mpesa)");
    }

    public Mono<Void> handleMobileNumber(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            return messageService.sendTextMessage(to, "Enter your M-Pesa Mobile Number");
        }
        if (!FieldValidators.isValidMobileNumber(text)) {
            return messageService.sendTextMessage(to,
                    "Mobile Number should be 10 digits starting with 0 (e.g. 0722730336) or 12 digits starting with 254 (e.g. 254722730336). Please try again.");
        }
        session.setMobileNumber(text);
        return screenService.sendConfirmation(to, session);
    }

    // ==================== CONFIRM / EDIT ====================

    public Mono<Void> handleConfirmDetails(String to, UserSession session) {
        String otp = CodeGenerator.generateFiveDigitCode();
        session.setOtp(otp);
        session.setOtpAttempts(0);
        session.setStep("enter_otp");

        deliverOtpAfterDelay(to, otp);
        return messageService.sendTextMessage(to, "An OTP has been sent to your M-Pesa number.\n\nPlease enter the OTP:");
    }

    public Mono<Void> handleEditDetails(String to, UserSession session) {
        return screenService.sendEditOptions(to);
    }

    public Mono<Void> handleExitEdit(String to, UserSession session) {
        return screenService.sendConfirmation(to, session);
    }

    public Mono<Void> handleEditFieldSelect(String to, String fieldId, UserSession session) {
        session.setStep(fieldId);
        String label = EDIT_FIELD_LABELS.getOrDefault(fieldId, "field");
        return messageService.sendTextMessage(to, "Enter new " + label + ":");
    }

    public Mono<Void> handleEditFieldText(String to, String text, UserSession session) {
        if (text == null || text.isBlank()) {
            return messageService.sendTextMessage(to, "Please enter a valid value.");
        }

        String step = session.getStep();

        if ("edit_upn".equals(step) && !FieldValidators.isValidUpn(text)) {
            return messageService.sendTextMessage(to, "UPN should be up to 11 digits and start with 1 or 2. Please try again.");
        }
        if ("edit_nationalid".equals(step) && !FieldValidators.isValidNationalId(text)) {
            return messageService.sendTextMessage(to, "National ID should be exactly 8 digits and cannot start with 0. Please try again.");
        }
        if ("edit_mobilenumber".equals(step) && !FieldValidators.isValidMobileNumber(text)) {
            return messageService.sendTextMessage(to,
                    "Mobile Number should be 10 digits starting with 0 (e.g. 0722730336) or 12 digits starting with 254 (e.g. 254722730336). Please try again.");
        }

        switch (step) {
            case "edit_firstname" -> session.setFirstName(text);
            case "edit_lastname" -> session.setLastName(text);
            case "edit_upn" -> session.setUpn(text);
            case "edit_nationalid" -> session.setNationalId(text);
            case "edit_mobilenumber" -> session.setMobileNumber(text);
            default -> log.warn("Unexpected edit step {} for {}", step, to);
        }

        return screenService.sendConfirmation(to, session);
    }

    // ==================== OTP ====================

    public Mono<Void> handleEnterOtp(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            return messageService.sendTextMessage(to, "Invalid OTP. Please enter a 5-digit number.");
        }

        if (text.equals(session.getOtp())) {
            session.setStep("enter_new_pin");
            return messageService.sendTextMessage(to, "Create a new 5-digit PIN for your account.\n\nDo not share this PIN with anyone.");
        }

        session.setOtpAttempts(session.getOtpAttempts() + 1);

        if (session.getOtpAttempts() >= MAX_OTP_ATTEMPTS) {
            resetRegistrationFields(session);
            session.setStep("welcome");
            return messageService.sendTextMessage(to, "Too many incorrect attempts. Your registration has been cancelled. Please try again.")
                    .then(screenService.sendWelcome(to));
        }

        int attemptsLeft = MAX_OTP_ATTEMPTS - session.getOtpAttempts();
        return messageService.sendTextMessage(to, "Incorrect OTP. You have " + attemptsLeft + " attempt(s) remaining.");
    }

    // ==================== NEW PIN SETUP ====================

    public Mono<Void> handleEnterNewPin(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            return messageService.sendTextMessage(to, "Invalid PIN. Please enter exactly 5 digits.");
        }
        if (text.equals(session.getOtp())) {
            return messageService.sendTextMessage(to, "Your new PIN cannot be the same as the OTP. Please choose a different 5-digit PIN.");
        }

        session.setNewPin(text);
        session.setStep("confirm_new_pin");
        return messageService.sendTextMessage(to, "Please re-enter your new 5-digit PIN to confirm.");
    }

    public Mono<Void> handleConfirmNewPin(String to, String text, UserSession session) {
        if (!text.equals(session.getNewPin())) {
            session.setStep("enter_new_pin");
            return messageService.sendTextMessage(to, "The PINs do not match. Please enter your new 5-digit PIN again:");
        }

        return completeRegistration(to, session);
    }

    private Mono<Void> completeRegistration(String to, UserSession session) {
        RegisteredUser user = new RegisteredUser();
        user.setFirstName(session.getFirstName());
        user.setLastName(session.getLastName());
        user.setUpn(session.getUpn());
        user.setNationalId(session.getNationalId());
        user.setMobileNumber(session.getMobileNumber());
        user.setHashedPin(passwordEncoder.encode(session.getNewPin()));
        user.setStatus("active");
        userStore.save(to, user);

        log.info("user_registered to={} firstName={} lastName={}", to, session.getFirstName(), session.getLastName());

        resetRegistrationFields(session);
        session.setAuthenticated(true);

        return messageService.sendTextMessage(to,
                        "🎉 Registration Complete!\n\n" +
                                "Your account has been successfully set up.\n\n" +
                                "🔒 Security Notice:\n" +
                                "• Your PIN is now active\n" +
                                "• Do not share this PIN with anyone\n" +
                                "• For your protection, we strongly recommend deleting this chat or the messages containing your PIN"
                )
                .then(screenService.sendMainMenu(to));
    }

    private void resetRegistrationFields(UserSession session) {
        session.setOtp(null);
        session.setOtpAttempts(0);
        session.setNewPin(null);
    }

    /**
     * Simulates SMS delivery of the OTP, arriving as a separate WhatsApp
     * message a few seconds later — same testing pattern used for the
     * Verification Code in AuthenticationFlowService. TODO: remove once
     * a real SMS/backend delivers this for real.
     */
    private void deliverOtpAfterDelay(String to, String otp) {
        CompletableFuture.runAsync(
                () -> messageService.sendTextMessage(to, "OTP " + otp)
                        .doOnError(err -> log.error("Failed to deliver simulated OTP to {}: {}", to, err.getMessage()))
                        .subscribe(),
                CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS)
        );
    }
}
