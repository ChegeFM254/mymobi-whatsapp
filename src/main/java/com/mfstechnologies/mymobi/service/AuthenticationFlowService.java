package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.validation.CodeGenerator;
import com.mfstechnologies.mymobi.validation.FieldValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    public Mono<Void> handleCivilServants(String to, UserSession session) {
        if (session.isAuthenticated()) {
            return screenService.sendMainMenu(to);
        }
        return screenService.sendCivilServantsMenu(to);
    }

    public Mono<Void> handleLoginMenu(String to, UserSession session) {
        long lockoutMinutes = lockoutService.getLockoutMinutesRemaining(to);
        if (lockoutMinutes > 0) {
            return messageService.sendTextMessage(to,
                    "Too many incorrect attempts. Your account is temporarily locked. Please try again in " + lockoutMinutes + " minute(s).");
        }

        session.setStep("login_enter_upn");
        session.setLoginAttempts(0);
        return messageService.sendTextMessage(to, "Enter UPN:");
    }

    public Mono<Void> handleLoginEnterUpn(String to, String text, UserSession session) {
        if (!FieldValidators.isValidUpn(text)) {
            return messageService.sendTextMessage(to, "UPN should be up to 11 digits and start with 1 or 2. Please try again.");
        }

        session.setLoginUpn(text);
        session.setStep("login_enter_pin");
        return messageService.sendTextMessage(to, "Enter PIN:");
    }
    public Mono<Void> handleLoginEnterPin(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            return messageService.sendTextMessage(to, "Invalid PIN. Please enter exactly 5 digits.");
        }

        LoginVerificationService.LoginResult result = loginVerificationService.verify(to, session.getLoginUpn(), text);

        if (!result.success()) {
            return recordFailedAttemptAndRespond(to, session, "Incorrect UPN or PIN.");
        }

        String code = CodeGenerator.generateFiveDigitCode();
        session.setVerificationCode(code);
        session.setStep("login_enter_verification_code");

        deliverCodeAfterDelay(to, code);
        return messageService.sendTextMessage(to, "Enter Verification Code:");
    }

    public Mono<Void> handleLoginEnterVerificationCode(String to, String text, UserSession session) {
        if (!FieldValidators.isValidFiveDigitCode(text)) {
            return messageService.sendTextMessage(to, "Invalid code. Please enter a 5-digit verification code.");
        }

        if (!text.equals(session.getVerificationCode())) {
            return recordFailedAttemptAndRespond(to, session, "Incorrect code.");
        }

        // UPN + PIN + Verification Code all correct.
        session.setLoginAttempts(0);
        session.setVerificationCode(null);
        session.setLoginUpn(null);
        session.setAuthenticated(true);
        return screenService.sendWelcome(to);
    }

    private Mono<Void> recordFailedAttemptAndRespond(String to, UserSession session, String reasonPrefix) {
        session.setLoginAttempts(session.getLoginAttempts() + 1);

        if (session.getLoginAttempts() >= LoginLockoutService.MAX_LOGIN_ATTEMPTS) {
            lockoutService.applyLockout(to);
            session.setStep("welcome");
            session.setLoginUpn(null);
            session.setVerificationCode(null);
            return messageService.sendTextMessage(to,
                    "Too many incorrect attempts. Your account has been temporarily locked for 10 minutes.")
                    .then(screenService.sendHomeScreen(to, session));
        }

        int attemptsLeft = LoginLockoutService.MAX_LOGIN_ATTEMPTS - session.getLoginAttempts();
        return messageService.sendTextMessage(to, reasonPrefix + " You have " + attemptsLeft + " attempt(s) remaining.");
    }
    private void deliverCodeAfterDelay(String to, String code) {
        CompletableFuture
                .runAsync(
                        () -> messageService.sendTextMessage(to, "Verification Code " + code)
                                .doOnError(err -> log.error("Failed to deliver simulated verification code to {}: {}", to, err.getMessage()))
                                .subscribe(),
                        CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS)
                );
    }

    public Mono<Void> handleLogout(String to, UserSession session) {
        inactivityTimeoutService.cancelTimeout(to);

        CompletableFuture.runAsync(
                () -> {
                    session.setAuthenticated(false);
                    session.setStep("welcome");
                    session.setNewSession(true);
                    messageService.sendTextMessage(to, "You have successfully logged out. Please type 'Hi' to start a new session.")
                            .doOnError(err -> log.error("Failed to send logout confirmation to {}: {}", to, err.getMessage()))
                            .subscribe();
                },
                CompletableFuture.delayedExecutor(LOGOUT_DELAY_SECONDS, TimeUnit.SECONDS)
        );
        return Mono.empty();
    }
}
