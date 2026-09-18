package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.validation.CodeGenerator;
import com.mfstechnologies.mymobi.validation.FieldValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * WORKSTREAM B (reactive -> synchronous): every method here used to
 * return Mono<Void>, chaining with .then(). All converted to plain
 * blocking void methods with sequential statements. The two background
 * CompletableFuture blocks (deliverCodeAfterDelay, handleLogout) used to
 * catch send errors via .doOnError().subscribe() - since
 * sendTextMessage() now throws directly instead of carrying errors on a
 * reactive error channel, those became plain try/catch blocks instead.
 */
@Service
public class AuthenticationFlowService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFlowService.class);
    private static final long LOGOUT_DELAY_SECONDS = 3;

    private final ScreenMessageService screenService;
    private final WhatsAppMessageService messageService;
    private final LoginLockoutService lockoutService;
    private final LoginVerificationService loginVerificationService;
    private final InactivityTimeoutService inactivityTimeoutService;

    public AuthenticationFlowService(
            ScreenMessageService screenService,
            WhatsAppMessageService messageService,
            LoginLockoutService lockoutService,
            LoginVerificationService loginVerificationService,
            InactivityTimeoutService inactivityTimeoutService
    ) {
        this.screenService = screenService;
        this.messageService = messageService;
        this.lockoutService = lockoutService;
        this.loginVerificationService = loginVerificationService;
        this.inactivityTimeoutService = inactivityTimeoutService;
    }

    public void handleCivilServants(String to, UserSession session) {
        if (session.isAuthenticated()) {
            screenService.sendMainMenu(to);
            return;
        }
        screenService.sendCivilServantsMenu(to);
    }

    public void handleLoginMenu(String to, UserSession session) {
        long lockoutMinutes = lockoutService.getLockoutMinutesRemaining(to);
        if (lockoutMinutes > 0) {
            messageService.sendTextMessage(to,
                    "Too many incorrect attempts. Your account is temporarily locked. Please try again in " + lockoutMinutes + " minute(s).");
            return;
        }

        session.setStep("login_enter_upn");
        session.setLoginAttempts(0);
        messageService.sendTextMessage(to, "Enter UPN:");
    }

    public void handleLoginEnterUpn(String to, String text, UserSession session) {
        if (!FieldValidators.isValidUpn(text)) {
            messageService.sendTextMessage(to, "UPN should be up to 11 digits and start with 1 or 2. Please try again.");
            return;
        }

        session.setLoginUpn(text);
        session.setStep("login_enter_pin");
        messageService.sendTextMessage(to, "Enter PIN:");
    }

    public void handleLoginEnterPin(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            messageService.sendTextMessage(to, "Invalid PIN. Please enter exactly 5 digits.");
            return;
                    }

        LoginVerificationService.LoginResult result = loginVerificationService.verify(to, session.getLoginUpn(), text);

        if (!result.success()) {
            recordFailedAttemptAndRespond(to, session, "Incorrect UPN or PIN.");
            return;
        }

        String code = CodeGenerator.generateFiveDigitCode();
        session.setVerificationCode(code);
        session.setStep("login_enter_verification_code");

        deliverCodeAfterDelay(to, code);
        messageService.sendTextMessage(to, "Enter Verification Code:");
    }

    public void handleLoginEnterVerificationCode(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            messageService.sendTextMessage(to, "Invalid code. Please enter a 5-digit verification code.");
            return;
        }

        if (!text.equals(session.getVerificationCode())) {
            recordFailedAttemptAndRespond(to, session, "Incorrect code.");
            return;
        }

        // UPN + PIN + Verification Code all correct.
        session.setLoginAttempts(0);
        session.setVerificationCode(null);
        session.setLoginUpn(null);
        session.setAuthenticated(true);
        screenService.sendWelcome(to);
    }

    private void recordFailedAttemptAndRespond(String to, UserSession session, String reasonPrefix) {
        session.setLoginAttempts(session.getLoginAttempts() + 1);

        if (session.getLoginAttempts() >= LoginLockoutService.MAX_LOGIN_ATTEMPTS) {
            lockoutService.applyLockout(to);
            session.setStep("welcome");
            session.setLoginUpn(null);
            session.setVerificationCode(null);
            messageService.sendTextMessage(to,
                    "Too many incorrect attempts. Your account has been temporarily locked for 10 minutes.");
            screenService.sendHomeScreen(to, session);
            return;
        }

        int attemptsLeft = LoginLockoutService.MAX_LOGIN_ATTEMPTS - session.getLoginAttempts();
        messageService.sendTextMessage(to, reasonPrefix + " You have " + attemptsLeft + " attempt(s) remaining.");
    }

    private void deliverCodeAfterDelay(String to, String code) {
        CompletableFuture.runAsync(
                () -> {
                    try {
                        messageService.sendTextMessage(to, "Verification Code " + code);
                    } catch (Exception err) {
                        log.error("Failed to deliver simulated verification code to {}: {}", to, err.getMessage());
                    }
                },
                CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS)
        );
    }

    public void handleLogout(String to, UserSession session) {
        inactivityTimeoutService.cancelTimeout(to);

        CompletableFuture.runAsync(
                () -> {
                    session.setAuthenticated(false);
                    session.setStep("welcome");
                    session.setNewSession(true);
                    try {
                        messageService.sendTextMessage(to, "You have successfully logged out. Please type 'Hi' to start a new session.");
                    } catch (Exception err) {
                        log.error("Failed to send logout confirmation to {}: {}", to, err.getMessage());
                    }
                },
                CompletableFuture.delayedExecutor(LOGOUT_DELAY_SECONDS, TimeUnit.SECONDS)
        );
    }
}
