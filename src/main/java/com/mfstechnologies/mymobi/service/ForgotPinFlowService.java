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

import java.util.Optional;

/**
 * WORKSTREAM B (reactive -> synchronous): every method here used to
 * return Mono<Void>, chaining follow-up screens with .then(). All
 * converted to plain blocking void methods with sequential statements.
 *
 * WORKSTREAM E (WhatsApp Flows webview for PIN/OTP/Approval Code): every
 * OTP/PIN entry point here now goes through the WhatsApp Flow webview
 * instead of a plain text prompt. Every retry path (wrong OTP, rejected
 * new PIN, mismatched confirmation) also re-sends the Flow afterward -
 * without that, the person's only way to retry would be falling back to
 * typing the value directly into the chat, defeating the whole point of
 * using the Flow in the first place.
 *
 * WORKSTREAM G (outbox pattern): the direct smsService.sendSms(...) call
 * for the Forgot PIN OTP is gone - handleForgotPin now calls
 * outboxService.enqueue(...) instead, a plain enqueue (same reasoning as
 * RegistrationFlowService's OTP): at this point nothing has been
 * persisted yet, since the existing user's PIN isn't updated until
 * handleConfirmNewPin runs later, well after this OTP is verified.
 */
@Service
public class ForgotPinFlowService {

    private static final Logger log = LoggerFactory.getLogger(ForgotPinFlowService.class);
    private static final int MAX_OTP_ATTEMPTS = 3;

    private final ScreenMessageService screenService;
    private final WhatsAppMessageService messageService;
    private final OutboxService outboxService;
    private final RegisteredUserStore userStore;
    private final PasswordEncoder passwordEncoder;
    private final LoginLockoutService lockoutService;

    public ForgotPinFlowService(
            ScreenMessageService screenService,
            WhatsAppMessageService messageService,
            OutboxService outboxService,
            RegisteredUserStore userStore,
            PasswordEncoder passwordEncoder,
            LoginLockoutService lockoutService
    ) {
        this.screenService = screenService;
        this.messageService = messageService;
        this.outboxService = outboxService;
        this.userStore = userStore;
        this.passwordEncoder = passwordEncoder;
        this.lockoutService = lockoutService;
    }
        public void handleForgotPin(String to, UserSession session) {
        long lockoutMinutes = lockoutService.getLockoutMinutesRemaining(to);
        if (lockoutMinutes > 0) {
            messageService.sendTextMessage(to,
                    "Too many incorrect attempts. Your account is temporarily locked. Please try again in " + lockoutMinutes + " minute(s).");
            return;
        }

        Optional<RegisteredUser> existing = userStore.findByPhoneNumber(to);
        if (existing.isEmpty()) {
            messageService.sendTextMessage(to, "No account found for this number. Please register first.");
            screenService.sendCivilServantsMenu(to);
            return;
        }

        String otp = CodeGenerator.generateFiveDigitCode();
        session.setOtp(otp);
        session.setOtpAttempts(0);
        session.setStep("forgot_pin_enter_otp");

        outboxService.enqueue(OutboxEntryType.OTP_SMS, new OutboxPayloads.SmsPayload(to, "OTP " + otp));
        screenService.sendCodeEntryFlow(to, "A new OTP has been sent to your registered mobile number.\n\nPlease enter the OTP:", "Enter OTP");
    }

    public void handleEnterOtp(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            messageService.sendTextMessage(to, "Invalid OTP. Please enter a 5-digit number.");
            return;
        }

        if (text.equals(session.getOtp())) {
            session.setStep("forgot_pin_enter_new_pin");
            screenService.sendCodeEntryFlow(to, "OTP verified. Please create a new 5-digit PIN:", "Enter PIN");
            return;
        }

        session.setOtpAttempts(session.getOtpAttempts() + 1);

        if (session.getOtpAttempts() >= MAX_OTP_ATTEMPTS) {
            lockoutService.applyLockout(to);
            resetFields(session);
            session.setStep("welcome");
            messageService.sendTextMessage(to, "Too many incorrect attempts. Your account has been temporarily locked for 10 minutes.");
            screenService.sendWelcome(to);
            return;
        }

        int attemptsLeft = MAX_OTP_ATTEMPTS - session.getOtpAttempts();
        messageService.sendTextMessage(to, "Incorrect OTP. You have " + attemptsLeft + " attempt(s) remaining.");
        screenService.sendCodeEntryFlow(to, "Please enter the OTP:", "Enter OTP");
    }

    public void handleEnterNewPin(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            messageService.sendTextMessage(to, "Invalid PIN. Please enter exactly 5 digits.");
            screenService.sendCodeEntryFlow(to, "Please create a new 5-digit PIN:", "Enter PIN");
            return;
        }
        if (text.equals(session.getOtp())) {
            messageService.sendTextMessage(to, "Your new PIN cannot be the same as the OTP. Please choose a different 5-digit PIN.");
            screenService.sendCodeEntryFlow(to, "Please create a new 5-digit PIN:", "Enter PIN");
            return;
        }

        session.setNewPin(text);
                session.setStep("forgot_pin_confirm_new_pin");
        screenService.sendCodeEntryFlow(to, "Please re-enter your new 5-digit PIN to confirm.", "Confirm PIN");
    }

    public void handleConfirmNewPin(String to, String text, UserSession session) {
        if (!text.equals(session.getNewPin())) {
            session.setStep("forgot_pin_enter_new_pin");
            screenService.sendCodeEntryFlow(to, "The PINs do not match. Please enter your new 5-digit PIN again:", "Enter PIN");
            return;
        }

        Optional<RegisteredUser> existing = userStore.findByPhoneNumber(to);
        if (existing.isEmpty()) {
            resetFields(session);
            session.setStep("welcome");
            messageService.sendTextMessage(to, "Something went wrong. Please start again.");
            screenService.sendWelcome(to);
            return;
        }

        RegisteredUser user = existing.get();
        user.setHashedPin(passwordEncoder.encode(text));
        // FIXED BUG (unrelated to WORKSTREAM G): findByPhoneNumber()
        // returns a detached entity outside any transaction - mutating
        // it alone never persisted the new PIN. Without this save(), the
        // "successful" reset message was sent but the person's PIN
        // reset was silently lost; their old PIN still worked.
        userStore.save(to, user);

        log.info("pin_reset to={}", to);

        resetFields(session);
        session.setAuthenticated(true);

        messageService.sendTextMessage(to,
                "Your PIN has been reset successfully.\n\n" +
                "Do not share this PIN with anyone."
        );
        screenService.sendMainMenu(to);
    }

    private void resetFields(UserSession session) {
        session.setOtp(null);
        session.setOtpAttempts(0);
        session.setNewPin(null);
    }
}
