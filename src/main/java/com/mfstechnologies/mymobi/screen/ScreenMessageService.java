package com.mfstechnologies.mymobi.screen;

import com.mfstechnologies.mymobi.service.WhatsAppMessageService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class ScreenMessageService {

    private final WhatsAppMessageService messageService;

    public ScreenMessageService(WhatsAppMessageService messageService) {
        this.messageService = messageService;
    }

    public Mono<Void> sendWelcome(String to) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", "Welcome to MyMobi [Java]"),
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

    public Mono<Void> sendEmergencyLoanMenu(String to, String loanStatus) {
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();

        if ("pending_approval".equals(loanStatus)) {
            rows.add(Map.of("id", "approve_loan_menu", "title", "Approve Loan", "description", "Approve your pending loan"));
            rows.add(Map.of("id", "cancel_loan", "title", "Cancel Loan", "description", "Cancel this loan application"));
        } else if ("approved".equals(loanStatus)) {
            rows.add(Map.of("id", "pay_loan_menu", "title", "Pay Loan", "description", "Make an early repayment"));
        } else {
            rows.add(Map.of("id", "apply_loan", "title", "Apply Loan", "description", "Apply for an emergency loan"));
        }

        rows.add(Map.of("id", "back", "title", "Back", "description", "Go back"));
        rows.add(Map.of("id", "home", "title", "Home", "description", "Return to home"));
        rows.add(Map.of("id", "logout", "title", "Log Out", "description", "Log out of the app"));

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "interactive",
                "interactive", Map.of(
                        "type", "list",
                        "header", Map.of("type", "text", "text", "Emergency Loan"),
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

    public Mono<Void> sendLoanBreakdown(String to, com.mfstechnologies.mymobi.model.LoanBreakdown breakdown) {
        String details = String.format(
                "Loan %,d\nUpfront Fees %,d\nDisbursement %,d\nMonthly Installment %,d\nPlatform Fee %,d",
                breakdown.loanAmount(),
                breakdown.upfrontFee(),
                breakdown.disbursement(),
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

    public Mono<Void> sendApproveLoanDetails(String to, com.mfstechnologies.mymobi.model.Loan loan) {
        String details = String.format(
                "Loan Amount: KES %,d\nTenure: %d month(s)\nDue Date: %s\nStatus: %s\n\nEnter your Approval Code to proceed.",
                loan.getLoanAmount(),
                loan.getTenureMonths(),
                loan.getDueDate(),
                loan.getStatus()
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

    public Mono<Void> sendPayLoanConfirm(String to, int installments, int totalAmount, int remainingBalanceAfter) {
        String body = String.format(
                "You are about to pay %d installment(s) totaling KES %,d.\n\nRemaining balance after this payment: %d installment(s).\n\nProceed?",
                installments, totalAmount, remainingBalanceAfter
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

    public Mono<Void> sendLoanStatementConfirm(String to, double cost) {
        String body = String.format("Loan Statement: KES %.2f\n\nProceed?", cost);

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

    public Mono<Void> sendLoanClearanceConfirm(String to, double cost) {
        String body = String.format("Loan Clearance Letter: KES %.2f\n\nProceed?", cost);

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
}
