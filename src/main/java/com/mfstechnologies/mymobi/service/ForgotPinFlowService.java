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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * WORKSTREAM B (reactive -> synchronous): every method here used to
 * return Mono<Void>, chaining follow-up screens with .then(). All
 * converted to plain blocking void methods with sequential statements.
 * deliverOtpAfterDelay's error handling changed from .doOnError().
 * subscribe() to a plain try/catch, since sendTextMessage() now throws
 * directly instead of carrying errors on a reactive error channel.
 */
@Service
public class ForgotPinFlowService {

    private static final Logger log = LoggerFactory.getLogger(ForgotPinFlowService.class);
    private static final int MAX_OTP_ATTEMPTS = 3;

    private final ScreenMessageService screenService;
    private final WhatsAppMessageService messageService;
    private final RegisteredUserStore userStore;
    private final PasswordEncoder passwordEncoder;
    private final LoginLockoutService lockoutService;

    public ForgotPinFlowService(
            ScreenMessageService screenService,
            WhatsAppMessageService messageService,
            RegisteredUserStore userStore,
            PasswordEncoder passwordEncoder,
            LoginLockoutService lockoutService
    ) {
        this.screenService = screenService;
        this.messageService = messageService;
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

        deliverOtpAfterDelay(to, otp);
        messageService.sendTextMessage(to, "A new OTP has been sent to your registered mobile number.\n\nPlease enter the OTP:");
    }

    public void handleEnterOtp(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            messageService.sendTextMessage(to, "Invalid OTP. Please enter a 5-digit number.");
            return;
        }

        if (text.equals(session.getOtp())) {
            session.setStep("forgot_pin_enter_new_pin");
            messageService.sendTextMessage(to, "OTP verified. Please create a new 5-digit PIN:");
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
    }

    public void handleEnterNewPin(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            messageService.sendTextMessage(to, "Invalid PIN. Please enter exactly 5 digits.");
            return;
        }
        if (text.equals(session.getOtp())) {
            messageService.sendTextMessage(to, "Your new PIN cannot be the same as the OTP. Please choose a different 5-digit PIN.");
            return;
        }

        session.setNewPin(text);
        session.setStep("forgot_pin_confirm_new_pin");
        messageService.sendTextMessage(to, "Please re-enter your new 5-digit PIN to confirm.");
    }

    public void handleConfirmNewPin(String to, String text, UserSession session) {
        if (!text.equals(session.getNewPin())) {
            session.setStep("forgot_pin_enter_new_pin");
            messageService.sendTextMessage(to, "The PINs do not match. Please enter your new 5-digit PIN again:");
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

    private void deliverOtpAfterDelay(String to, String otp) {
        CompletableFuture.runAsync(
                () -> {
                    try {
                        messageService.sendTextMessage(to, "OTP " + otp);
                    } catch (Exception err) {
                        log.error("Failed to deliver simulated OTP to {}: {}", to, err.getMessage());
                    }
                },
                CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS)
        );
    }
}
