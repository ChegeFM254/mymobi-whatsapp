package com.mfstechnologies.mymobi.document;

import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.LoanBreakdown;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentHtmlServiceTest {

    private final DocumentHtmlService service = new DocumentHtmlService();

    private RegisteredUser maliciousUser() {
        RegisteredUser user = new RegisteredUser();
        user.setFirstName("<script>alert(1)</script>");
        user.setLastName("Doe");
        user.setUpn("12345");
        return user;
    }

    private RegisteredUser sampleUser() {
        RegisteredUser user = new RegisteredUser();
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setUpn("12345");
        return user;
    }

    private Loan sampleLoan() {
        Loan loan = new Loan();
        loan.setLoanAmount(15000);
        loan.setTenureMonths(3);
        loan.setInstallmentsPaid(1);
        loan.setDueDate("2026-10-19");
        loan.setStatus("approved");
        loan.setBreakdown(new LoanBreakdown(15000, 2943, 32057, 14442, 450));
        return loan;
    }

    private Loan fullyPaidLoan() {
        Loan loan = sampleLoan();
        loan.setInstallmentsPaid(3); // fully paid: 3 of 3
        loan.setStatus("paid");
        return loan;
    }

    // ==================== PAYSLIP ====================

    @Test
    void payslipEscapesAMaliciousFirstName() {
        String html = service.generatePayslipHtml(maliciousUser(), 3);

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void payslipContainsAllExpectedFields() {
        String html = service.generatePayslipHtml(sampleUser(), 6);

        assertThat(html).contains("Jane");
        assertThat(html).contains("Doe");
        assertThat(html).contains("12345");
        assertThat(html).contains("Last 6 months");
        assertThat(html).contains("MyMobi");
    }

    @Test
    void payslipContainsARealisticPayslipBreakdownPerMonth() {
        String html = service.generatePayslipHtml(sampleUser(), 2);

        assertThat(html).contains("Basic Salary");
        assertThat(html).contains("45,000");
        assertThat(html).contains("Allowances");
        assertThat(html).contains("8,000");
        assertThat(html).contains("Gross Pay");
        assertThat(html).contains("53,000");
        assertThat(html).contains("PAYE");
        assertThat(html).contains("NSSF");
        assertThat(html).contains("SHIF");
        assertThat(html).contains("Net Pay");
        assertThat(html).contains("43,870");
    }

    @Test
    void payslipGeneratesOneBreakdownSectionPerRequestedMonth() {
        String html = service.generatePayslipHtml(sampleUser(), 3);

        int occurrences = html.split("<h3>", -1).length - 1;
        assertThat(occurrences).isEqualTo(3);
    }

    // ==================== LOAN STATEMENT ====================
@Test
    void loanStatementEscapesAMaliciousFirstName() {
        String html = service.generateLoanStatementHtml(maliciousUser(), sampleLoan());

        assertThat(html).doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void loanStatementShowsCorrectBalanceCalculation() {
        String html = service.generateLoanStatementHtml(sampleUser(), sampleLoan());

        assertThat(html).contains("28,884");
        assertThat(html).contains("1 of 3");
    }

    @Test
    void loanStatementShowsAHumanReadableStatusLabel() {
        Loan pending = sampleLoan();
        pending.setStatus("pending_approval");

        String html = service.generateLoanStatementHtml(sampleUser(), pending);

        assertThat(html).contains("Pending Approval");
        assertThat(html).doesNotContain("pending_approval<");
    }

    @Test
    void loanStatementShowsApprovedAsCurrentActive() {
        String html = service.generateLoanStatementHtml(sampleUser(), sampleLoan());

        assertThat(html).contains("Current (Active)");
    }

    // ==================== LOAN CLEARANCE LETTER ====================

    @Test
    void loanClearanceEscapesAMaliciousFirstName() {
        String html = service.generateLoanClearanceHtml(maliciousUser(), fullyPaidLoan());

        assertThat(html).doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void loanClearanceShowsZeroBalanceForAGenuinelyFullyPaidLoan() {
        String html = service.generateLoanClearanceHtml(sampleUser(), fullyPaidLoan());

        assertThat(html).contains("KES 0");
    }

    @Test
    void loanClearanceShowsTheTotalObligationAsRepaymentAmount() {
        String html = service.generateLoanClearanceHtml(sampleUser(), fullyPaidLoan());

        assertThat(html).contains("Loan Repayment Amount");
        assertThat(html).contains("43,326");
    }

    @Test
    void loanClearanceAlwaysShowsPaidStatusRegardlessOfLoanStatusField() {
        String html = service.generateLoanClearanceHtml(sampleUser(), fullyPaidLoan());

        assertThat(html).contains("Loan Status</td><td>Paid</td>");
    }
}
