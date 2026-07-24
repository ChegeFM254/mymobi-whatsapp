package com.mfstechnologies.mymobi.screen;

import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.service.WhatsAppMessageService;
import com.mfstechnologies.mymobi.session.LoanStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the Main Menu's dynamic loan-action row - the old, separate
 * "Emergency Loan" submenu was collapsed directly into this screen, so
 * the correct Apply/Approve/Pay (plus Cancel Loan alongside Approve) row
 * combination needs direct verification here.
 */
@ExtendWith(MockitoExtension.class)
class ScreenMessageServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private WhatsAppMessageService messageService;

    @Captor
    private ArgumentCaptor<Map<String, Object>> payloadCaptor;

    private LoanStore loanStore;
    private com.mfstechnologies.mymobi.session.RegisteredUserStore userStore;
    private ScreenMessageService screenService;

    @BeforeEach
    void setUp() {
        loanStore = new LoanStore();
        userStore = new com.mfstechnologies.mymobi.session.RegisteredUserStore();
        screenService = new ScreenMessageService(messageService, loanStore, userStore);
        when(messageService.sendMessage(eq(FROM), payloadCaptor.capture())).thenReturn(Mono.empty());
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedRowIds() {
        Map<String, Object> payload = payloadCaptor.getValue();
        Map<String, Object> interactive = (Map<String, Object>) payload.get("interactive");
        Map<String, Object> action = (Map<String, Object>) interactive.get("action");
        List<Map<String, Object>> sections = (List<Map<String, Object>>) action.get("sections");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) sections.get(0).get("rows");
        return rows.stream().map(row -> (String) row.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private String capturedBodyText() {
        Map<String, Object> payload = payloadCaptor.getValue();
        Map<String, Object> interactive = (Map<String, Object>) payload.get("interactive");
        Map<String, Object> body = (Map<String, Object>) interactive.get("body");
        return (String) body.get("text");
    }

    @SuppressWarnings("unchecked")
    private String capturedHeaderText() {
        Map<String, Object> payload = payloadCaptor.getValue();
        Map<String, Object> interactive = (Map<String, Object>) payload.get("interactive");
        Map<String, Object> header = (Map<String, Object>) interactive.get("header");
        return (String) header.get("text");
    }

    @SuppressWarnings("unchecked")
    private boolean capturedHasFooter() {
        Map<String, Object> payload = payloadCaptor.getValue();
        Map<String, Object> interactive = (Map<String, Object>) payload.get("interactive");
        return interactive.containsKey("footer");
    }
    @Test
    void showsApplyLoanWhenThereIsNoLoan() {
        screenService.sendMainMenu(FROM).block();

        assertThat(capturedRowIds()).startsWith("apply_loan");
    }

    @Test
    void showsApproveLoanAndCancelLoanTogetherWhenPendingApproval() {
        Loan loan = new Loan();
        loan.setStatus("pending_approval");
        loanStore.save(FROM, loan);

        screenService.sendMainMenu(FROM).block();

        List<String> rowIds = capturedRowIds();
        assertThat(rowIds).containsSubsequence("approve_loan_menu", "cancel_loan");
    }

    @Test
    void showsPayLoanWhenApproved() {
        Loan loan = new Loan();
        loan.setStatus("approved");
        loanStore.save(FROM, loan);

        screenService.sendMainMenu(FROM).block();

        assertThat(capturedRowIds()).startsWith("pay_loan_menu");
    }

    @Test
    void showsApplyLoanWhenPreviousLoanIsFullyPaid() {
        Loan loan = new Loan();
        loan.setStatus("paid");
        loanStore.save(FROM, loan);

        screenService.sendMainMenu(FROM).block();

        assertThat(capturedRowIds()).startsWith("apply_loan");
    }

    @Test
    void alwaysIncludesTheStandardNavigationRows() {
        screenService.sendMainMenu(FROM).block();

        List<String> rowIds = capturedRowIds();
        assertThat(rowIds).contains("payslip_menu", "loan_statement_menu", "loan_clearance_menu", "back", "home", "logout");
    }

    // ==================== sendHomeScreen ====================

    @Test
    void homeScreenShowsMainMenuWhenAuthenticated() {
        com.mfstechnologies.mymobi.model.UserSession session = new com.mfstechnologies.mymobi.model.UserSession();
        session.setAuthenticated(true);

        screenService.sendHomeScreen(FROM, session).block();

        // Main Menu specifically has these rows; Welcome does not.
        assertThat(capturedRowIds()).contains("payslip_menu", "loan_statement_menu");
    }

    @Test
    void homeScreenShowsWelcomeWhenNotAuthenticated() {
        com.mfstechnologies.mymobi.model.UserSession session = new com.mfstechnologies.mymobi.model.UserSession(); // authenticated=false by default

        screenService.sendHomeScreen(FROM, session).block();

        // Welcome specifically has these rows; Main Menu does not.
        assertThat(capturedRowIds()).contains("civil_servants", "buy_airtime");
    }

    @Test
    void homeScreenShowsWelcomeWhenSessionIsNull() {
        screenService.sendHomeScreen(FROM, null).block();

        assertThat(capturedRowIds()).contains("civil_servants", "buy_airtime");
    }

    // ==================== sendLoanBreakdown ====================

    @Test
    void loanBreakdownMatchesTheExactRequestedWording() {
        var breakdown = new com.mfstechnologies.mymobi.model.LoanBreakdown(60000, 6842, 53158, 24500, 450);

        screenService.sendLoanBreakdown(FROM, breakdown, 3).block();

        assertThat(capturedBodyText()).isEqualTo(
                "Loan Amount: KES 60,000\n" +
                "Upfront Fees: KES 6,842\n" +
                "You Receive: KES 53,158\n" +
                "Loan Period: 3 Months\n" +
                "Monthly Installment: KES 24,500\n" +
                "Platform Fee: KES 450\n" +
                "\n" +
                "Confirm and Proceed:"
        );
    }

    @Test
    void loanBreakdownUsesSingularMonthForATenureOfOne() {
        var breakdown = new com.mfstechnologies.mymobi.model.LoanBreakdown(20000, 2000, 18000, 20000, 150);

        screenService.sendLoanBreakdown(FROM, breakdown, 1).block();

        assertThat(capturedBodyText()).contains("Loan Period: 1 Month\n");
        assertThat(capturedBodyText()).doesNotContain("1 Months");
    }

    // ==================== sendApproveLoanDetails ====================

    @Test
    void approveLoanDetailsMatchesTheExactRequestedWording() {
        Loan loan = new Loan();
        loan.setLoanAmount(60000);
        loan.setTenureMonths(3);
        loan.setDueDate("2026-10-23");
        loan.setStatus("pending_approval");
        loan.setBreakdown(new com.mfstechnologies.mymobi.model.LoanBreakdown(60000, 6842, 53158, 24500, 450));

        screenService.sendApproveLoanDetails(FROM, loan).block();

        assertThat(capturedBodyText()).isEqualTo(
                "Loan Amount: KES 60,000\n" +
                "Upfront Fees: KES 6,842\n" +
                "You Receive: KES 53,158\n" +
                "Loan Period: 3 Months\n" +
                "Monthly Installment: KES 24,500\n" +
                "Platform Fee: KES 450\n" +
                "Due Date: 2026-10-23\n" +
                "Status: Pending Approval\n" +
                "\n" +
                "Enter your Approval Code to proceed."
        );
    }

    @Test
    void approveLoanDetailsStatusIsGenuinelyDynamicNotHardcoded() {
        Loan loan = new Loan();
        loan.setLoanAmount(20000);
        loan.setTenureMonths(1);
        loan.setDueDate("2026-09-01");
        loan.setStatus("cancelled"); // deliberately NOT pending_approval
        loan.setBreakdown(new com.mfstechnologies.mymobi.model.LoanBreakdown(20000, 2000, 18000, 20000, 150));

        screenService.sendApproveLoanDetails(FROM, loan).block();

        assertThat(capturedBodyText()).contains("Status: Cancelled");
        assertThat(capturedBodyText()).doesNotContain("Pending Approval");
    }

    // ==================== sendWelcome personalization ====================

    @Test
    void welcomeGreetsByNameWhenARegisteredUserExists() {
        var user = new com.mfstechnologies.mymobi.model.RegisteredUser();
        user.setFirstName("John");
        userStore.save(FROM, user);

        screenService.sendWelcome(FROM).block();

        assertThat(capturedHeaderText()).isEqualTo("Hello John, Welcome to MyMobi [Java]");
        // No footer on this screen at all, personalized or not - "MyMobi
        // Emergency Loan" was misleading, since MyMobi also offers Buy
        // Airtime; "Select a service" alone is a complete description.
        assertThat(capturedHasFooter()).isFalse();
    }

    @Test
    void welcomeUsesGenericGreetingWhenNoRegisteredUserExists() {
        screenService.sendWelcome(FROM).block();

        assertThat(capturedHeaderText()).isEqualTo("Welcome to MyMobi [Java]");
        assertThat(capturedHasFooter()).isFalse();
    }

    // ==================== sendConfirmation ====================

    @Test
    void confirmationShowsAllSevenKycFieldsInTheCorrectOrder() {
        com.mfstechnologies.mymobi.model.UserSession session = new com.mfstechnologies.mymobi.model.UserSession();
        session.setFirstName("Jane");
        session.setMiddleName("Wanjiru");
        session.setLastName("Doe");
        session.setEmailAddress("jane.doe@example.com");
        session.setUpn("12345");
        session.setNationalId("87654321");
        session.setMobileNumber("0722730336");

        screenService.sendConfirmation(FROM, session).block();

        String body = capturedBodyText();
        assertThat(body).contains("First Name: Jane");
        assertThat(body).contains("Middle Name: Wanjiru");
        assertThat(body).contains("Last Name: Doe");
        assertThat(body).contains("Email Address: jane.doe@example.com");
        assertThat(body).contains("UPN Number: 12345");
        assertThat(body).contains("National ID Number: 87654321");
        assertThat(body).contains("Mpesa Mobile Number: 0722730336");

        // Order matters too - the five ORIGINAL fields keep their exact
        // original relative order; Middle Name and Email Address are
        // just inserted at specific points, nothing else moved.
        int firstNameIndex = body.indexOf("First Name:");
        int middleNameIndex = body.indexOf("Middle Name:");
        int lastNameIndex = body.indexOf("Last Name:");
        int emailIndex = body.indexOf("Email Address:");
        int upnIndex = body.indexOf("UPN Number:");
        int nationalIdIndex = body.indexOf("National ID Number:");
        int mobileIndex = body.indexOf("Mpesa Mobile Number:");
        assertThat(firstNameIndex).isLessThan(middleNameIndex);
        assertThat(middleNameIndex).isLessThan(lastNameIndex);
        assertThat(lastNameIndex).isLessThan(emailIndex);
        assertThat(emailIndex).isLessThan(upnIndex);
        assertThat(upnIndex).isLessThan(nationalIdIndex);
        assertThat(nationalIdIndex).isLessThan(mobileIndex);
    }

    // ==================== sendEditOptions ====================

    @Test
    void editOptionsIncludesAllSevenFieldsPlusExit() {
        screenService.sendEditOptions(FROM).block();

        assertThat(capturedRowIds()).containsExactly(
                "edit_firstname", "edit_middlename", "edit_lastname",
                "edit_emailaddress", "edit_upn", "edit_nationalid", "edit_mobilenumber",
                "exit_edit"
        );
    }
}
