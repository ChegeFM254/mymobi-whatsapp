package com.mfstechnologies.mymobi.screen;

import com.mfstechnologies.mymobi.service.WhatsAppMessageService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Builds and sends the various interactive screens shown to users -
 * direct equivalent of the many sendXxx() functions in the Node.js
 * version (sendWelcome, sendMainMenu, sendOptIn, etc). This class will
 * grow to hold each of those as the corresponding flow gets ported -
 * starting here with just Welcome, the very first screen in the whole
 * conversation flow.
 *
 * STANDING CONVENTION carried over from the Node version: WhatsApp's
 * interactive "button" message type supports a MAXIMUM of 3 buttons.
 * Anything needing 4+ options (or any Back/Home/Logout combination) must
 * use the interactive "list" type instead. Keep this in mind for every
 * screen added here.
 *
 * WORKSTREAM B (reactive -> synchronous): every method here used to
 * return Mono<Void>, simply forwarding whatever WhatsAppMessageService
 * returned. Now that WhatsAppMessageService's send methods are plain
 * blocking void calls, these are too - no Mono wrapping needed anywhere
 * in this class.
 */
@Service
public class ScreenMessageService {

    private final WhatsAppMessageService messageService;
    private final com.mfstechnologies.mymobi.session.LoanStore loanStore;
    private final com.mfstechnologies.mymobi.session.RegisteredUserStore userStore;

    public ScreenMessageService(
            WhatsAppMessageService messageService,
            com.mfstechnologies.mymobi.session.LoanStore loanStore,
            com.mfstechnologies.mymobi.session.RegisteredUserStore userStore
    ) {
        this.messageService = messageService;
        this.loanStore = loanStore;
        this.userStore = userStore;
    }

    /**
     * "Go to my home base" - the Main Menu if still authenticated, or
     * the Welcome screen if not. Direct equivalent of sendHomeScreen()
     * in the Node.js version. Use this (not a bare sendWelcome) for the
     * Home button and for any completion/defensive-fallback path where
     * the person might still be mid-session - e.g. after successfully
     * approving or paying off a loan, or a "something changed
     * unexpectedly" guard within an already-authenticated flow.
     */
    public void sendHomeScreen(String to, com.mfstechnologies.mymobi.model.UserSession session) {
        if (session != null && session.isAuthenticated()) {
            sendMainMenu(to);
            return;
        }
        sendWelcome(to);
    }

    /**
     * The Welcome/Home screen - direct equivalent of sendWelcome() in the
     * Node.js version, including the Log Out option added there later.
     */

        public void sendWelcome(String to) {
        String greeting = userStore.findByPhoneNumber(to)
                .map(user -> "Hello " + user.getFirstName() + ", Welcome to MyMobi [Java]")
                .orElse("Welcome to MyMobi [Java]");

        // No footer at all on this screen, for either the personalized
        // or generic case: "MyMobi Emergency Loan" was misleading, since
        // MyMobi also offers Buy Airtime - "Select a service" alone is a
        // complete, honest description of what's on offer.
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", greeting),
                        "body", Map.of("text", "Select a service"),
                        "action", Map.of(
                                "button", "Choose Option",
                                "sections", List.of(Map.of(
                                        "title", "Services",
                                        "rows", List.of(
                                                Map.of("id", "civil_servants", "title", "Civil Servants", "description", "Emergency Loan"),
                                                Map.of("id", "buy_airtime", "title", "Buy Airtime", "description", "Quick top up"),
                                                Map.of("id", "logout", "title", "Log Out", "description", "Log out of the app")
                                        )
                                ))
                        )
                )
        );
        messageService.sendMessage(to, payload);
    }

    /**
     * The pre-login "Civil Servants" screen - Log In / Register / Forgot
     * PIN / Opt Out. Direct equivalent of sendCivilServantsMenu() in the
     * Node.js version (the multi-channel-aware menu that replaced the
     * old "Enter PIN" auth screen).
     */
    public void sendCivilServantsMenu(String to) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", "Civil Servants"),
                        "body", Map.of("text", "Log in or register to access MyMobi services."),
                        "footer", Map.of("text", "MyMobi"),
                        "action", Map.of(
                                "button", "Select Option",
                                "sections", List.of(Map.of(
                                        "title", "Options",
                                        "rows", List.of(
                                                Map.of("id", "login_menu", "title", "Log In", "description", "Already registered? Log in here"),
                                                Map.of("id", "register_menu", "title", "Register", "description", "New to MyMobi? Register here"),
                                                Map.of("id", "forgot_pin", "title", "Forgot PIN", "description", "Reset your PIN"),
                                                Map.of("id", "opt_out", "title", "Opt Out", "description", "Opt out of this service")
                                        )
                                ))
                        )
                )
        );
        messageService.sendMessage(to, payload);
    }

    /**
     * The post-login Main Menu. Direct equivalent of sendMainMenu() in
     * the Node.js version - the old separate "Emergency Loan" submenu
     * has been collapsed directly into this screen's top row(s), so the
     * user's actual next loan action (Apply/Approve/Pay) is visible
     * immediately, without an extra tap. Cancel Loan shows directly
     * alongside Approve Loan here too, not just one screen deeper.
     */
    
