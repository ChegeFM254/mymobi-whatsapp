package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Service
public class OptOutFlowService {

    private static final Logger log = LoggerFactory.getLogger(OptOutFlowService.class);

    private final ScreenMessageService screenService;
    private final WhatsAppMessageService messageService;
    private final RegisteredUserStore userStore;
    private final PasswordEncoder passwordEncoder;
    private final LoginLockoutService lockoutService;

    public OptOutFlowService(
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

    public Mono<Void> handleOptOut(String to, UserSession session) {
        long lockoutMinutes = lockoutService.getLockoutMinutesRemaining(to);
        if (lockoutMinutes > 0) {
            return messageService.sendTextMessage(to,
                    "Too many incorrect attempts. Your account is temporarily locked. Please try again in " + lockoutMinutes + " minute(s).");
        }

        if (userStore.findByPhoneNumber(to).isEmpty()) {
            return messageService.sendTextMessage(to, "No account found for this number.")
                    .then(screenService.sendCivilServantsMenu(to));
        }

        session.setStep("opt_out_confirmation");
        return messageService.sendTextMessage(to, "You are about to OPT OUT of Emergency Loan Services\n\nTo proceed, type YES or NO");
    }

    public Mono<Void> handleOptOutConfirmation(String to, String text, UserSession session) {
        String response = text == null ? "" : text.trim().toLowerCase();

        if (response.equals("yes") || response.equals("y")) {
            session.setStep("opt_out_pin");
            return messageService.sendTextMessage(to, "To confirm opt out, please enter your 5-digit PIN:");
        }

        if (response.equals("no") || response.equals("n")) {
            session.setStep("welcome");
            return messageService.sendTextMessage(to, "Opt out cancelled.")
                    .then(screenService.sendCivilServantsMenu(to));
        }

        return messageService.sendTextMessage(to, "Please reply with Yes or No.");
    }

    public Mono<Void> handleOptOutPin(String to, String text, UserSession session) {
        Optional<RegisteredUser> existing = userStore.findByPhoneNumber(to);

        if (existing.isEmpty()) {
            return messageService.sendTextMessage(to, "No account found for this number.")
                    .then(screenService.sendWelcome(to));
        }

        RegisteredUser user = existing.get();

        if (text == null || !passwordEncoder.matches(text, user.getHashedPin())) {
            session.setStep("welcome");
            return messageService.sendTextMessage(to, "Incorrect PIN. Opt out cancelled.")
                    .then(screenService.sendCivilServantsMenu(to));
        }

        user.setStatus("opted_out");
        log.info("user_opted_out to={}", to);

        session.setStep("welcome");
        session.setAuthenticated(false);

        return messageService.sendTextMessage(to, "You have been successfully opted out of the Emergency Loan service.")
                .then(screenService.sendWelcome(to));
    }
}
