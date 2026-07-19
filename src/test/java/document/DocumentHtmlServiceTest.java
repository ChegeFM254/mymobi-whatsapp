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

    @Test
    void payslipEscapesAMaliciousFirstName() {
        String html = service.generatePayslipHtml(maliciousUser(), 3);

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void payslipContainsAllExpectedFields() {
        RegisteredUser user = new RegisteredUser();
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setUpn("12345");

        String html = service.generatePayslipHtml(user, 6);

        assertThat(html).contains("Jane");
        assertThat(html).contains("Doe");
        assertThat(html).contains("12345");
        assertThat(html).contains("6 month(s)");
        assertThat(html).contains("MFS Technologies Limited");
    }

    @Test
    void loanStatementEscapesAMaliciousFirstName() {
        String html = service.generateLoanStatementHtml(maliciousUser(), sampleLoan());

        assertThat(html).doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void loanStatementShowsCorrectBalanceCalculation() {
        RegisteredUser user = new RegisteredUser();
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setUpn("12345");

        String html = service.generateLoanStatementHtml(user, sampleLoan());

        assertThat(html).contains("28,884");
        assertThat(html).contains("1 of 3");
    }

    @Test
    void loanClearanceEscapesAMaliciousFirstName() {
        String html = service.generateLoanClearanceHtml(maliciousUser(), sampleLoan());

        assertThat(html).doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void loanClearanceAlwaysShowsZeroBalance() {
        RegisteredUser user = new RegisteredUser();
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setUpn("12345");

        String html = service.generateLoanClearanceHtml(user, sampleLoan());

        assertThat(html).contains("KES 0");
    }
}