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
