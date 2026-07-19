package com.mfstechnologies.mymobi.screen;

import com.mfstechnologies.mymobi.service.WhatsAppMessageService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Builds and sends the various interactive screens shown to users —
 * direct equivalent of the many sendXxx() functions in the Node.js
 * version (sendWelcome, sendMainMenu, sendOptIn, etc). This class will
 * grow to hold each of those as the corresponding flow gets ported.
 *
 * STANDING CONVENTION carried over from the Node version: WhatsApp's
 * interactive "button" message type supports a MAXIMUM of 3 buttons.
 * Anything needing 4+ options (or any Back/Home/Logout combination) must
 * use the interactive "list" type instead. Keep this in mind for every
 * screen added here.
 */
@Service
public class ScreenMessageService {

    private final WhatsAppMessageService messageService;

    public ScreenMessageService(WhatsAppMessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * The Welcome/Home screen — direct equivalent of sendWelcome() in the
     * Node.js version, including the Log Out option added there later.
     */
    public Mono<Void> sendWelcome(String to) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", "Welcome to MyMobi"),
                        "body", Map.of("text", "Select a service"),
                        "footer", Map.of("text", "MyMobi Emergency Loan"),
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
        return messageService.sendMessage(to, payload);
    }

    /**
     * The pre-login "Civil Servants" screen — Log In / Register / Forgot
     * PIN / Opt Out. Direct equivalent of sendCivilServantsMenu() in the
     * Node.js version (the multi-channel-aware menu that replaced the
     * old "Enter PIN" auth screen).
     */
    public Mono<Void> sendCivilServantsMenu(String to) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", "Civil Servants"),
                        "body", Map.of("text", "Select a service:"),
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
        return messageService.sendMessage(to, payload);
    }

    /**
     * The post-login Main Menu. Direct equivalent of sendMainMenu() in
     * the Node.js version.
     *
     * NOTE ON SCOPE: the row ids here (emergency_loan, payslip_menu,
     * etc.) match the real Node version's structure, but their actual
     * flows aren't ported yet — tapping them currently falls through to
     * ConversationService's generic handleButton() stub. This screen
     * exists now so the structure is correct and ready for each flow to
     * be plugged in incrementally, same approach as everywhere else in
     * this rewrite.
     */
    public Mono<Void> sendMainMenu(String to) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", "Main Menu"),
                        "body", Map.of("text", "What would you like to do?"),
                        "footer", Map.of("text", "MyMobi"),
                        "action", Map.of(
                                "button", "Select Option",
                                "sections", List.of(Map.of(
                                        "title", "Options",
                                        "rows", List.of(
                                                Map.of("id", "emergency_loan", "title", "Emergency Loan", "description", "Apply for Emergency Loan"),
                                                Map.of("id", "payslip_menu", "title", "Payslip", "description", "Download your payslip"),
                                                Map.of("id", "loan_statement_menu", "title", "Loan Statement", "description", "View your loan details and balance"),
                                                Map.of("id", "loan_clearance_menu", "title", "Loan Clearance Letter", "description", "For a fully paid loan"),
                                                Map.of("id", "back", "title", "Back", "description", "Go back"),
                                                Map.of("id", "home", "title", "Home", "description", "Return to home"),
                                                Map.of("id", "logout", "title", "Log Out", "description", "Log out of the app")
                                        )
                                ))
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    /**
     * Registration: opt-in confirmation. Direct equivalent of sendOptIn()
     * in the Node.js version. Only 2 options — fits within the 3-button
     * cap, so "button" type is fine here (see standing convention above).
     */
    public Mono<Void> sendOptIn(String to) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", "You are not registered for this service.\nWould you like to OPT IN?"),
                        "action", Map.of(
                                "buttons", List.of(
                                        Map.of("type", "reply", "reply", Map.of("id", "optin_yes", "title", "Yes")),
                                        Map.of("type", "reply", "reply", Map.of("id", "optin_no", "title", "No"))
                                )
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    /**
     * Registration: terms & conditions acceptance. Direct equivalent of
     * sendTerms() in the Node.js version.
     */
    public Mono<Void> sendTerms(String to) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", "Please accept T&Cs and Data Privacy Policy of MyMobi Civil Servants Emergency Loan.\nView at: www.mymobi.co.ke"),
                        "action", Map.of(
                                "buttons", List.of(
                                        Map.of("type", "reply", "reply", Map.of("id", "accept_tc", "title", "✅ Accept")),
                                        Map.of("type", "reply", "reply", Map.of("id", "decline_tc", "title", "Decline"))
                                )
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    /**
     * Registration: shows the collected KYC details for confirmation
     * before proceeding to OTP. Direct equivalent of sendConfirmation()
     * in the Node.js version.
     */
    public Mono<Void> sendConfirmation(String to, com.mfstechnologies.mymobi.model.UserSession session) {
        String details = String.format("""
                Confirm Details:

                First Name: %s
                Last Name: %s
                UPN: %s
                National ID: %s
                Mobile Number (Mpesa): %s

                Is this correct?""",
                nullToEmpty(session.getFirstName()),
                nullToEmpty(session.getLastName()),
                nullToEmpty(session.getUpn()),
                nullToEmpty(session.getNationalId()),
                nullToEmpty(session.getMobileNumber())
        );

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", details),
                        "action", Map.of(
                                "buttons", List.of(
                                        Map.of("type", "reply", "reply", Map.of("id", "confirm_details", "title", "✅ Accept")),
                                        Map.of("type", "reply", "reply", Map.of("id", "edit_details", "title", "✏️ Edit"))
                                )
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    /**
     * Registration: pick which KYC field to edit. Direct equivalent of
     * sendEditOptions() in the Node.js version. 6 options, so this needs
     * "list" type per the standing convention.
     */
    public Mono<Void> sendEditOptions(String to) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", "Edit Details"),
                        "body", Map.of("text", "Which field would you like to edit?"),
                        "footer", Map.of("text", "MyMobi"),
                        "action", Map.of(
                                "button", "Select Field",
                                "sections", List.of(Map.of(
                                        "title", "Available Fields",
                                        "rows", List.of(
                                                Map.of("id", "edit_firstname", "title", "First Name", "description", "Update your first name"),
                                                Map.of("id", "edit_lastname", "title", "Last Name", "description", "Update your last name"),
                                                Map.of("id", "edit_upn", "title", "UPN", "description", "Update your UPN"),
                                                Map.of("id", "edit_nationalid", "title", "National ID", "description", "Update your National ID"),
                                                Map.of("id", "edit_mobilenumber", "title", "Mobile Number (Mpesa)", "description", "Update your M-Pesa number"),
                                                Map.of("id", "exit_edit", "title", "Exit", "description", "Return to Confirm Details")
                                        )
                                ))
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}