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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Forgot PIN flow: OTP verification against an existing account, then a
 * new PIN is set. Direct equivalent of the corresponding section of
 * handleButton() / handleTextInput() in the Node.js version.
 *
 * Uses DISTINCT step names from Registration's OTP/new-PIN steps
 * (forgot_pin_enter_otp, forgot_pin_enter_new_pin,
 * forgot_pin_confirm_new_pin) even though the underlying idea is
 * similar, so ConversationService's routing can never confuse "resetting
 * an existing PIN" with "setting a PIN during fresh registration" -
 * these update different things (an existing RegisteredUser's PIN vs
 * creating a brand new one).
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

    public Mono<Void> handleForgotPin(String to, UserSession session) {
        long lockoutMinutes = lockoutService.getLockoutMinutesRemaining(to);
        if (lockoutMinutes > 0) {
            return messageService.sendTextMessage(to,
                    "Too many incorrect attempts. Your account is temporarily locked. Please try again in " + lockoutMinutes + " minute(s).");
        }

        Optional<RegisteredUser> existing = userStore.findByPhoneNumber(to);
        if (existing.isEmpty()) {
            return messageService.sendTextMessage(to, "No account found for this number. Please register first.")
                    .then(screenService.sendCivilServantsMenu(to));
        }

        String otp = CodeGenerator.generateFiveDigitCode();
        session.setOtp(otp);
        session.setOtpAttempts(0);
        session.setStep("forgot_pin_enter_otp");

        deliverOtpAfterDelay(to, otp);
        return messageService.sendTextMessage(to, "A new OTP has been sent to your registered mobile number.\n\nPlease enter the OTP:");
    }

    public Mono<Void> handleEnterOtp(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            return messageService.sendTextMessage(to, "Invalid OTP. Please enter a 5-digit number.");
        }

        if (text.equals(session.getOtp())) {
            session.setStep("forgot_pin_enter_new_pin");
            return messageService.sendTextMessage(to, "OTP verified. Please create a new 5-digit PIN:");
        }

        session.setOtpAttempts(session.getOtpAttempts() + 1);

        if (session.getOtpAttempts() >= MAX_OTP_ATTEMPTS) {
            lockoutService.applyLockout(to);
            resetFields(session);
            session.setStep("welcome");
            return messageService.sendTextMessage(to, "Too many incorrect attempts. Your account has been temporarily locked for 10 minutes.");
        }

        int attemptsLeft = MAX_OTP_ATTEMPTS - session.getOtpAttempts();
        return messageService.sendTextMessage(to, "Incorrect OTP. You have " + attemptsLeft + " attempt(s) remaining.");
    }

    public Mono<Void> handleEnterNewPin(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            return messageService.sendTextMessage(to, "Invalid PIN. Please enter exactly 5 digits.");
        }
        if (text.equals(session.getOtp())) {
            return messageService.sendTextMessage(to, "Your new PIN cannot be the same as the OTP. Please choose a different 5-digit PIN.");
        }

        session.setNewPin(text);
        session.setStep("forgot_pin_confirm_new_pin");
        return messageService.sendTextMessage(to, "Please re-enter your new 5-digit PIN to confirm.");
    }

    public Mono<Void> handleConfirmNewPin(String to, String text, UserSession session) {
        if (!text.equals(session.getNewPin())) {
            session.setStep("forgot_pin_enter_new_pin");
            return messageService.sendTextMessage(to, "The PINs do not match. Please enter your new 5-digit PIN again:");
        }

        Optional<RegisteredUser> existing = userStore.findByPhoneNumber(to);
        if (existing.isEmpty()) {
            resetFields(session);
            session.setStep("welcome");
            return messageService.sendTextMessage(to, "Something went wrong. Please start again.")
                    .then(screenService.sendWelcome(to));
        }

        RegisteredUser user = existing.get();
        user.setHashedPin(passwordEncoder.encode(text));

        log.info("pin_reset to={}", to);

        resetFields(session);
        session.setAuthenticated(true);

        return messageService.sendTextMessage(to,
                        "Your PIN has been reset successfully.\n\n" +
                                "Do not share this PIN with anyone."
                )
                .then(screenService.sendMainMenu(to));
    }

    private void resetFields(UserSession session) {
        session.setOtp(null);
        session.setOtpAttempts(0);
        session.setNewPin(null);
    }

    private void deliverOtpAfterDelay(String to, String otp) {
        CompletableFuture.runAsync(
                () -> messageService.sendTextMessage(to, "OTP " + otp)
                        .doOnError(err -> log.error("Failed to deliver simulated OTP to {}: {}", to, err.getMessage()))
                        .subscribe(),
                CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS)
        );
    }
}