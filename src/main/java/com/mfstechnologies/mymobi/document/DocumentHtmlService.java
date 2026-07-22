package com.mfstechnologies.mymobi.document;

import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.validation.HtmlEscaper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

@Service
public class DocumentHtmlService {

    private static final String LENDER_NAME = "MFS Technologies Limited";
    private static final DateTimeFormatter MONTH_YEAR_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private static final int BASIC_SALARY = 45000;
    private static final int ALLOWANCES = 8000;
    private static final int GROSS_PAY = BASIC_SALARY + ALLOWANCES;
    private static final int PAYE = 6200;
    private static final int NSSF = 1080;
    private static final int SHIF = 1350;
    private static final int OTHER_DEDUCTIONS = 500;
    private static final int TOTAL_DEDUCTIONS = PAYE + NSSF + SHIF + OTHER_DEDUCTIONS;
    private static final int NET_PAY = GROSS_PAY - TOTAL_DEDUCTIONS;

    private static final Map<String, String> LOAN_STATUS_LABELS = Map.of(
            "pending_approval", "Pending Approval",
            "approved", "Current (Active)",
            "paid", "Paid",
            "cancelled", "Cancelled"
    );

    public String generatePayslipHtml(RegisteredUser user, int months) {
        StringBuilder monthSections = new StringBuilder();
        YearMonth now = YearMonth.now();
        for (int i = 0; i < months; i++) {
            String label = now.minusMonths(i).format(MONTH_YEAR_FORMAT);
            monthSections.append("""
                    <h3>%s</h3>
                    <table>
                    <tr><th>Earnings</th><th>Amount (KES)</th></tr>
                    <tr><td>Basic Salary</td><td>%,d</td></tr>
                    <tr><td>Allowances</td><td>%,d</td></tr>
                    <tr><td><strong>Gross Pay</strong></td><td><strong>%,d</strong></td></tr>
                    <tr><th>Deductions</th><th>Amount (KES)</th></tr>
                    <tr><td>PAYE</td><td>%,d</td></tr>
                    <tr><td>NSSF</td><td>%,d</td></tr>
                    <tr><td>SHIF</td><td>%,d</td></tr>
                    <tr><td>Other Deductions</td><td>%,d</td></tr>
                    <tr class="total-row"><td>Net Pay</td><td>KES %,d</td></tr>
                    </table>
                    """.formatted(
                    HtmlEscaper.escape(label), BASIC_SALARY, ALLOWANCES, GROSS_PAY,
                    PAYE, NSSF, SHIF, OTHER_DEDUCTIONS, NET_PAY
            ));
        }

        String periodLabel = months > 1 ? months + " months" : months + " month";
        String body = """
                <p><strong>Name:</strong> %s %s<br>
                <strong>UPN:</strong> %s<br>
                <strong>Period:</strong> Last %s</p>
                <div class="notice">\u26A0\uFE0F Placeholder figures for testing - not real payroll data.</div>
                %s
                """.formatted(
                HtmlEscaper.escape(user.getFirstName()), HtmlEscaper.escape(user.getLastName()),
                HtmlEscaper.escape(user.getUpn()), periodLabel, monthSections
        );

        return documentPageWrapper("Payslip", body);
    }
    public String generateLoanStatementHtml(RegisteredUser user, Loan loan) {
        int remainingBalance = calculateRemainingBalance(loan);
        String rawStatus = loan.getStatus();
        String statusLabel = rawStatus == null ? "" : LOAN_STATUS_LABELS.getOrDefault(rawStatus, rawStatus);
        String statementDate = LocalDate.now().toString();

        String body = """
                <table>
                <tr><td>Statement Date</td><td>%s</td></tr>
                <tr><td>Lender</td><td>%s</td></tr>
                <tr><td>First Name</td><td>%s</td></tr>
                <tr><td>Last Name</td><td>%s</td></tr>
                <tr><td>UPN</td><td>%s</td></tr>
                <tr><td>Loan Principal</td><td>KES %,d</td></tr>
                <tr><td>Installments Paid</td><td>%d of %d</td></tr>
                <tr class="total-row"><td>Loan Balance</td><td>KES %,d</td></tr>
                <tr><td>Loan Due Date</td><td>%s</td></tr>
                <tr><td>Loan Status</td><td>%s</td></tr>
                </table>
                """.formatted(
                HtmlEscaper.escape(statementDate),
                HtmlEscaper.escape(LENDER_NAME),
                HtmlEscaper.escape(user.getFirstName()),
                HtmlEscaper.escape(user.getLastName()),
                HtmlEscaper.escape(user.getUpn()),
                loan.getLoanAmount(),
                loan.getInstallmentsPaid(),
                loan.getTenureMonths(),
                remainingBalance,
                HtmlEscaper.escape(loan.getDueDate()),
                HtmlEscaper.escape(statusLabel)
        );

        return documentPageWrapper("Loan Statement", body);
    }

    public String generateLoanClearanceHtml(RegisteredUser user, Loan loan) {
        int totalObligation = calculateTotalObligation(loan);
        int remainingBalance = calculateRemainingBalance(loan);
        String letterDate = LocalDate.now().toString();

        String body = """
                <p>This is to certify that the below-named individual has fully repaid their loan facility with %s.</p>
                <table>
                <tr><td>Letter Date</td><td>%s</td></tr>
                <tr><td>Lender</td><td>%s</td></tr>
                <tr><td>First Name</td><td>%s</td></tr>
                <tr><td>Last Name</td><td>%s</td></tr>
                <tr><td>UPN</td><td>%s</td></tr>
                <tr><td>Loan Repayment Amount</td><td>KES %,d</td></tr>
                <tr class="total-row"><td>Loan Balance</td><td>KES %,d</td></tr>
                <tr><td>Loan Due Date</td><td>%s</td></tr>
                <tr><td>Loan Status</td><td>Paid</td></tr>
                </table>
                <p>No further amounts are owed on this loan facility as of the date of this letter.</p>
                """.formatted(
                HtmlEscaper.escape(LENDER_NAME),
                HtmlEscaper.escape(letterDate),
                HtmlEscaper.escape(LENDER_NAME),
                HtmlEscaper.escape(user.getFirstName()),
                HtmlEscaper.escape(user.getLastName()),
                HtmlEscaper.escape(user.getUpn()),
                totalObligation,
                remainingBalance,
                HtmlEscaper.escape(loan.getDueDate())
        );

        return documentPageWrapper("Loan Clearance Letter", body);
    }

    /**
     * Shared page wrapper - direct equivalent of documentPageWrapper()
     * in the Node.js version, including the exact same styling, the
     * print/download notice, and the "Download / Print" button (browser
     * print-to-PDF stands in for a real generated PDF for now).
     */
private String documentPageWrapper(String title, String bodyHtml) {
        String escapedTitle = HtmlEscaper.escape(title);
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s - MyMobi</title>
                <style>
                body { font-family: Arial, Helvetica, sans-serif; max-width: 700px; margin: 40px auto; padding: 0 20px; color: #222; line-height: 1.5; }
                .header { border-bottom: 3px solid #0a7d3e; padding-bottom: 16px; margin-bottom: 24px; }
                .header h1 { color: #0a7d3e; margin: 0 0 4px 0; }
                table { width: 100%%; border-collapse: collapse; margin: 16px 0; }
                td, th { padding: 8px 12px; text-align: left; border-bottom: 1px solid #ddd; }
                th { background: #f5f5f5; }
                .total-row td { font-weight: bold; border-top: 2px solid #333; }
                .footer { margin-top: 32px; font-size: 12px; color: #888; }
                .notice { background: #fff8e1; border: 1px solid #ffe082; padding: 12px; border-radius: 6px; margin: 20px 0; font-size: 14px; }
                button.download-btn { background: #0a7d3e; color: white; border: none; padding: 10px 20px; border-radius: 6px; font-size: 15px; cursor: pointer; }
                @media print { .no-print { display: none; } }
                </style>
                </head>
                <body>
                <div class="header"><h1>MyMobi</h1><p>%s</p></div>
                %s
                <div class="notice no-print">
                \uD83D\uDCC4 This is a placeholder document for testing (a downloadable PDF version is planned). Use your browser's Print option and choose "Save as PDF" to keep a copy.
                </div>
                <div class="no-print"><button class="download-btn" onclick="window.print()">Download / Print</button></div>
                <div class="footer">Generated by MyMobi &middot; %s</div>
                </body>
                </html>
                """.formatted(escapedTitle, escapedTitle, bodyHtml, Instant.now().toString());
    }

    private int calculateTotalObligation(Loan loan) {
        if (loan.getBreakdown() == null) {
            return 0;
        }
        return loan.getBreakdown().monthlyInstallment() * loan.getTenureMonths();
    }

    private int calculateRemainingBalance(Loan loan) {
        return calculateTotalObligation(loan) - (loan.getBreakdown() != null ? loan.getBreakdown().monthlyInstallment() * loan.getInstallmentsPaid() : 0);
    }
}
