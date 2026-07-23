package com.mfstechnologies.mymobi.screen;

import com.mfstechnologies.mymobi.service.WhatsAppMessageService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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
    public Mono<Void> sendHomeScreen(String to, com.mfstechnologies.mymobi.model.UserSession session) {
        if (session != null && session.isAuthenticated()) {
            return sendMainMenu(to);
        }
        return sendWelcome(to);
    }

    /**
     * The Welcome/Home screen - direct equivalent of sendWelcome() in the
     * Node.js version, including the Log Out option added there later.
     */
    public Mono<Void> sendWelcome(String to) {
        String greeting = userStore.findByPhoneNumber(to)
                .map(user -> "Hello " + user.getFirstName() + ", welcome to MyMobi [Java]")
                .orElse("Welcome to MyMobi [Java]");

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", greeting),
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
     * The pre-login "Civil Servants" screen - Log In / Register / Forgot
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
        return messageService.sendMessage(to, payload);
    }

    /**
     * The post-login Main Menu. Direct equivalent of sendMainMenu() in
     * the Node.js version - the old separate "Emergency Loan" submenu
     * has been collapsed directly into this screen's top row(s), so the
     * user's actual next loan action (Apply/Approve/Pay) is visible
     * immediately, without an extra tap. Cancel Loan shows directly
     * alongside Approve Loan here too, not just one screen deeper.
     */
    public Mono<Void> sendMainMenu(String to) {
        com.mfstechnologies.mymobi.model.Loan loan = loanStore.findByPhoneNumber(to).orElse(null);
        java.util.List<Map<String, Object>> loanActionRows;

        if (loan != null && "pending_approval".equals(loan.getStatus())) {
            loanActionRows = List.of(
                    Map.of("id", "approve_loan_menu", "title", "Approve Loan", "description", "Enter your approval code"),
                    Map.of("id", "cancel_loan", "title", "Cancel Loan", "description", "Cancel this loan application")
            );
        } else if (loan != null && "approved".equals(loan.getStatus())) {
            loanActionRows = List.of(
                    Map.of("id", "pay_loan_menu", "title", "Pay Loan", "description", "Make an early repayment")
            );
        } else {
            loanActionRows = List.of(
                    Map.of("id", "apply_loan", "title", "Apply Loan", "description", "Apply for an emergency loan")
            );
        }

        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>(loanActionRows);
        rows.add(Map.of("id", "payslip_menu", "title", "Payslip", "description", "Download your payslip"));
        rows.add(Map.of("id", "loan_statement_menu", "title", "Loan Statement", "description", "View your loan details and balance"));
        rows.add(Map.of("id", "loan_clearance_menu", "title", "Loan Clearance Letter", "description", "For a fully paid loan"));
        rows.add(Map.of("id", "back", "title", "Back", "description", "Go back"));
        rows.add(Map.of("id", "home", "title", "Home", "description", "Return to home"));
        rows.add(Map.of("id", "logout", "title", "Log Out", "description", "Log out of the app"));

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
                                "sections", List.of(Map.of("title", "Options", "rows", rows))
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    /**
     * Registration: opt-in confirmation. Direct equivalent of sendOptIn()
     * in the Node.js version. Only 2 options - fits within the 3-button
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
                                        Map.of("type", "reply", "reply", Map.of("id", "accept_tc", "title", "Accept")),
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
                                        Map.of("type", "reply", "reply", Map.of("id", "confirm_details", "title", "Accept")),
                                        Map.of("type", "reply", "reply", Map.of("id", "edit_details", "title", "Edit"))
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

    /**
     * Apply Loan: pick a repayment tenure. Direct equivalent of
     * sendLoanTenureOptions() in the Node.js version.
     */
    public Mono<Void> sendLoanTenureOptions(String to) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", "Apply Loan"),
                        "body", Map.of("text", "Select your repayment period:"),
                        "footer", Map.of("text", "MyMobi Emergency Loan"),
                        "action", Map.of(
                                "button", "Select Period",
                                "sections", List.of(Map.of(
                                        "title", "Repayment Period",
                                        "rows", List.of(
                                                Map.of("id", "tenure_1", "title", "1 Month", "description", "Loan limit KES 20,000"),
                                                Map.of("id", "tenure_2", "title", "2 Months", "description", "Loan limit KES 40,000"),
                                                Map.of("id", "tenure_3", "title", "3 Months", "description", "Loan limit KES 60,000"),
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
     * Apply Loan: reached via Back from the breakdown screen. Direct
     * equivalent of sendLoanAmountMenu() in the Node.js version.
     */
    public Mono<Void> sendLoanAmountMenu(String to, int loanLimit, int tenureMonths) {
        String monthLabel = tenureMonths > 1 ? "months" : "month";
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", "Apply Loan"),
                        "body", Map.of("text", "Loan limit: KES " + loanLimit + " over " + tenureMonths + " " + monthLabel + "."),
                        "footer", Map.of("text", "MyMobi Emergency Loan"),
                        "action", Map.of(
                                "button", "Select Option",
                                "sections", List.of(Map.of(
                                        "title", "Options",
                                        "rows", List.of(
                                                Map.of("id", "start_loan_amount_entry", "title", "Enter Loan Amount", "description", "Type the amount you wish to borrow"),
                                                Map.of("id", "back", "title", "Back", "description", "Select a different repayment period"),
                                                Map.of("id", "home", "title", "Home", "description", "Return to home")
                                        )
                                ))
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    /**
     * Apply Loan: fee breakdown and Accept/Decline. Direct equivalent of
     * sendLoanBreakdown() in the Node.js version.
     */
public Mono<Void> sendLoanBreakdown(String to, com.mfstechnologies.mymobi.model.LoanBreakdown breakdown, int tenureMonths) {
        String periodLabel = tenureMonths > 1 ? "Months" : "Month";
        String details = String.format(
                "Loan Amount: KES %,d\nUpfront Fees: KES %,d\nYou Receive: KES %,d\nLoan Period: %d %s\nMonthly Installment: KES %,d\nPlatform Fee: KES %,d\n\nConfirm and Proceed:",
                breakdown.loanAmount(),
                breakdown.upfrontFee(),
                breakdown.disbursement(),
                tenureMonths,
                periodLabel,
                breakdown.monthlyInstallment(),
                breakdown.platformFee()
        );

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", "Loan Breakdown"),
                        "body", Map.of("text", details),
                        "footer", Map.of("text", "MyMobi Emergency Loan"),
                        "action", Map.of(
                                "button", "Select Option",
                                "sections", List.of(Map.of(
                                        "title", "Options",
                                        "rows", List.of(
                                                Map.of("id", "accept_loan", "title", "Accept", "description", "Confirm and proceed"),
                                                Map.of("id", "decline_loan", "title", "Decline", "description", "Cancel this loan application"),
                                                Map.of("id", "back", "title", "Back", "description", "Return to Enter Loan Amount menu"),
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
     * Approve Loan: shows the pending loan's details and prompts for the
     * approval code. Direct equivalent of the details screen in the
     * Node.js version's Approve Loan flow.
     */
    public Mono<Void> sendApproveLoanDetails(String to, com.mfstechnologies.mymobi.model.Loan loan) {
        String periodLabel = loan.getTenureMonths() > 1 ? "Months" : "Month";
        com.mfstechnologies.mymobi.model.LoanBreakdown breakdown = loan.getBreakdown();
        String details = String.format(
               "Loan Amount: KES %,d\nUpfront Fees: KES %,d\nYou Receive: KES %,d\nLoan Period: %d %s\nMonthly Installment: KES %,d\nPlatform Fee: KES %,d\nDue Date: %s\nStatus: %s\n\nEnter your Approval Code to proceed.",
                loan.getLoanAmount(),
                breakdown.upfrontFee(),
                breakdown.disbursement(),
                loan.getTenureMonths(),
                periodLabel,
                breakdown.monthlyInstallment(),
                breakdown.platformFee(),
                loan.getDueDate(),
                humanizeStatus(loan.getStatus())
        );

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", "Approve Loan"),
                        "body", Map.of("text", details),
                        "footer", Map.of("text", "MyMobi Emergency Loan"),
                        "action", Map.of(
                                "button", "Select Option",
                                "sections", List.of(Map.of(
                                        "title", "Options",
                                        "rows", List.of(
                                                Map.of("id", "enter_approval_code_menu", "title", "Enter Approval Code", "description", "Type the code you received"),
                                                Map.of("id", "cancel_loan", "title", "Cancel Loan", "description", "Cancel this loan application"),
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
     * Cancel Loan: a single Yes/No confirmation. Direct equivalent of
     * the Cancel Loan confirmation screen in the Node.js version.
     */
public Mono<Void> sendCancelLoanConfirm(String to) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", "Are you sure you want to cancel this loan application?"),
                        "action", Map.of(
                                "buttons", List.of(
                                        Map.of("type", "reply", "reply", Map.of("id", "confirm_cancel_loan_yes", "title", "Yes")),
                                        Map.of("type", "reply", "reply", Map.of("id", "confirm_cancel_loan_no", "title", "No"))
                                )
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    /**
     * Pay Loan: dynamic installment options based on how many remain.
     * Direct equivalent of the dynamic pay-loan menu in the Node.js
     * version.
     */
    public Mono<Void> sendPayLoanOptions(String to, int remainingInstallments, int monthlyInstallmentAmount) {
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int n = 1; n <= remainingInstallments; n++) {
            int total = monthlyInstallmentAmount * n;
            String label = n == 1 ? "Pay 1 installment" : "Pay " + n + " installments";
            rows.add(Map.of(
                    "id", "pay_installments_" + n,
                    "title", label,
                    "description", "KES " + String.format("%,d", total)
            ));
        }
        rows.add(Map.of("id", "back", "title", "Back", "description", "Go back"));
        rows.add(Map.of("id", "home", "title", "Home", "description", "Return to home"));

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", "Pay Loan"),
                        "body", Map.of("text", "How many installments would you like to pay?"),
                        "footer", Map.of("text", "MyMobi Emergency Loan"),
                        "action", Map.of(
                                "button", "Select Option",
                                "sections", List.of(Map.of("title", "Options", "rows", rows))
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    /**
     * Pay Loan: confirmation before triggering payment. Direct
     * equivalent of the pay-loan confirmation screen in the Node.js
     * version.
     */
public Mono<Void> sendPayLoanConfirm(String to, int installments, int totalAmount, int remainingBalanceAfter, int remainingInstallmentsAfter) {
        String body = String.format(
                "You are about to pay %d installment(s) totalling KES %,d.\n\nRemaining balance after this payment: KES %,d (%d installments).\nProceed?",
                installments, totalAmount, remainingBalanceAfter, remainingInstallmentsAfter
        );

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", body),
                        "action", Map.of(
                                "buttons", List.of(
                                        Map.of("type", "reply", "reply", Map.of("id", "confirm_pay_loan", "title", "Proceed")),
                                        Map.of("type", "reply", "reply", Map.of("id", "cancel_pay_loan", "title", "Cancel"))
                                )
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    /**
     * Payslip: cost confirmation before generating the document. Direct
     * equivalent of the payslip confirmation screen in the Node.js
     * version.
     */
    public Mono<Void> sendPayslipConfirm(String to, int months, double cost) {
        String body = String.format("Payslip for %d month(s): KES %.2f\n\nProceed?", months, cost);

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", body),
                        "action", Map.of(
                                "buttons", List.of(
                                        Map.of("type", "reply", "reply", Map.of("id", "confirm_payslip", "title", "Proceed")),
                                        Map.of("type", "reply", "reply", Map.of("id", "cancel_payslip", "title", "Cancel"))
                                )
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    /**
     * Loan Statement: cost confirmation before generating the document.
     */
    public Mono<Void> sendLoanStatementConfirm(String to, double cost) {
        String body = String.format("Loan Statement: You will be charged KES %.2f for this request.\n\nProceed:", cost);

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", body),
                        "action", Map.of(
                                "buttons", List.of(
                                        Map.of("type", "reply", "reply", Map.of("id", "confirm_loan_statement", "title", "Proceed")),
                                        Map.of("type", "reply", "reply", Map.of("id", "cancel_loan_statement", "title", "Cancel"))
                                )
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    /**
     * Loan Clearance Letter: cost confirmation before generating the
     * document. Only reachable once the flow has already confirmed the
     * loan status is "paid".
     */
public Mono<Void> sendLoanClearanceConfirm(String to, double cost) {
        String body = String.format("Loan Clearance Letter: You will be charged KES %.2f for this request.\n\nProceed:", cost);

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "button",
                        "body", Map.of("text", body),
                        "action", Map.of(
                                "buttons", List.of(
                                        Map.of("type", "reply", "reply", Map.of("id", "confirm_loan_clearance", "title", "Proceed")),
                                        Map.of("type", "reply", "reply", Map.of("id", "cancel_loan_clearance", "title", "Cancel"))
                                )
                        )
                )
        );
        return messageService.sendMessage(to, payload);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String humanizeStatus(String rawStatus) {
        if (rawStatus == null) {
            return "";
        }
        return switch (rawStatus) {
            case "pending_approval" -> "Pending Approval";
            case "approved" -> "Current (Active)";
            case "paid" -> "Paid";
            case "cancelled" -> "Cancelled";
            default -> rawStatus;
        };
    }
}
