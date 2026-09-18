package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Opt Out flow: Yes/No confirmation, then PIN verification before
 * actually opting the account out. Direct equivalent of the
 * corresponding section of handleButton() / handleTextInput() in the
 * Node.js version. Matches the Node version's single-attempt PIN check
 * here (no retry loop) - getting the PIN wrong simply cancels the
 * opt-out rather than locking the account, since this is a lower-risk
 * action than logging in.
 *
 * WORKSTREAM B (reactive -> synchronous): every method here used to
 * return Mono<Void>, chaining follow-up screens with .then(). All
 * converted to plain blocking void methods with sequential statements.
 */
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

    public void handleOptOut(String to, UserSession session) {
        long lockoutMinutes = lockoutService.getLockoutMinutesRemaining(to);
        if (lockoutMinutes > 0) {
            messageService.sendTextMessage(to,
                    "Too many incorrect attempts. Your account is temporarily locked. Please try again in " + lockoutMinutes + " minute(s).");
            return;
        }

        if (userStore.findByPhoneNumber(to).isEmpty()) {
            messageService.sendTextMessage(to, "No account found for this number.");
            screenService.sendCivilServantsMenu(to);
            return;
        }

        session.setStep("opt_out_confirmation");
        messageService.sendTextMessage(to, "You are about to OPT OUT of Emergency Loan Services\n\nTo proceed, type YES or NO");
    }

    public void handleOptOutConfirmation(String to, String text, UserSession session) {
        String response = text == null ? "" : text.trim().toLowerCase();

        if (response.equals("yes") || response.equals("y")) {
            session.setStep("opt_out_pin");
            messageService.sendTextMessage(to, "To confirm opt out, please enter your 5-digit PIN:");
            return;
        }

        if (response.equals("no") || response.equals("n")) {
            session.setStep("welcome");
            messageService.sendTextMessage(to, "Opt out cancelled.");
            screenService.sendCivilServantsMenu(to);
            return;
        }

        messageService.sendTextMessage(to, "Please reply with Yes or No.");
    }

    public void handleOptOutPin(String to, String text, UserSession session) {
        Optional<RegisteredUser> existing = userStore.findByPhoneNumber(to);

        if (existing.isEmpty()) {
            messageService.sendTextMessage(to, "No account found for this number.");
            screenService.sendWelcome(to);
            return;
        }

        RegisteredUser user = existing.get();

        if (text == null || !passwordEncoder.matches(text, user.getHashedPin())) {
            session.setStep("welcome");
            messageService.sendTextMessage(to, "Incorrect PIN. Opt out cancelled.");
            screenService.sendCivilServantsMenu(to);
            return;
        }

        // Genuinely remove the account, not just mark it - once opted
        // out, this number must be indistinguishable from one that's
        // never registered at all (data protection requirement). The
        // person must register again from scratch to use the service.
        userStore.delete(to);
        log.info("user_opted_out to={}", to);

        // The session ends immediately here - deliberately no screen is
        // sent at all beyond the plain confirmation text. The person
        // must type a trigger word (Hi, Loan, etc.) to start a genuinely
        // fresh session, matching a brand new UserSession's own
        // defaults exactly (step=welcome, newSession=true).
        session.setStep("welcome");
        session.setNewSession(true);
        session.setAuthenticated(false);

        messageService.sendTextMessage(to, "You have been successfully opted out of the Emergency Loan service.");
    }
}
