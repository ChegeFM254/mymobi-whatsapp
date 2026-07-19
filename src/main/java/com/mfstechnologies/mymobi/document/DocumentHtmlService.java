package com.mfstechnologies.mymobi.document;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.validation.HtmlEscaper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class DocumentHtmlService {

    private static final String LENDER_NAME = "MFS Technologies Limited";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMMM yyyy");

    public String generatePayslipHtml(RegisteredUser user, int months) {
        String issueDate = LocalDate.now().format(DATE_FORMAT);

        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <title>Payslip</title>
                <style>
                body { font-family: Arial, sans-serif; max-width: 600px; margin: 40px auto; padding: 20px; color: #222; }
                h1 { font-size: 20px; border-bottom: 2px solid #333; padding-bottom: 10px; }
                table { width: 100%%; border-collapse: collapse; margin-top: 20px; }
                td { padding: 8px 4px; border-bottom: 1px solid #eee; }
                td:first-child { font-weight: bold; width: 40%%; }
                .footer { margin-top: 30px; font-size: 12px; color: #777; }
                </style>
                </head>
                <body>
                <h1>Payslip</h1>
                <table>
                <tr><td>Issue Date</td><td>%s</td></tr>
                <tr><td>Lender</td><td>%s</td></tr>
                <tr><td>First Name</td><td>%s</td></tr>
                <tr><td>Last Name</td><td>%s</td></tr>
                <tr><td>UPN</td><td>%s</td></tr>
                <tr><td>Period Covered</td><td>%d month(s)</td></tr>
                </table>
                <div class="footer">This document was generated electronically and does not require a signature.</div>
                </body>
                </html>
                """.formatted(
                HtmlEscaper.escape(issueDate),
                HtmlEscaper.escape(LENDER_NAME),
                HtmlEscaper.escape(user.getFirstName()),
                HtmlEscaper.escape(user.getLastName()),
                HtmlEscaper.escape(user.getUpn()),
                months
        );
    }

    public String generateLoanStatementHtml(RegisteredUser user, com.mfstechnologies.mymobi.model.Loan loan) {
        String statementDate = LocalDate.now().format(DATE_FORMAT);
        int balance = calculateBalance(loan);

        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <title>Loan Statement</title>
                <style>
                body { font-family: Arial, sans-serif; max-width: 600px; margin: 40px auto; padding: 20px; color: #222; }
                h1 { font-size: 20px; border-bottom: 2px solid #333; padding-bottom: 10px; }
                table { width: 100%%; border-collapse: collapse; margin-top: 20px; }
                td { padding: 8px 4px; border-bottom: 1px solid #eee; }
                td:first-child { font-weight: bold; width: 40%%; }
                .footer { margin-top: 30px; font-size: 12px; color: #777; }
                </style>
                </head>
                <body>
                <h1>Loan Statement</h1>
                <table>
                <tr><td>Statement Date</td><td>%s</td></tr>
                <tr><td>Lender</td><td>%s</td></tr>
                <tr><td>First Name</td><td>%s</td></tr>
                <tr><td>Last Name</td><td>%s</td></tr>
                <tr><td>UPN</td><td>%s</td></tr>
                <tr><td>Loan Principal</td><td>KES %,d</td></tr>
                <tr><td>Installments Paid</td><td>%d of %d</td></tr>
                <tr><td>Loan Balance</td><td>KES %,d</td></tr>
                <tr><td>Loan Due Date</td><td>%s</td></tr>
                <tr><td>Loan Status</td><td>%s</td></tr>
                </table>
                <div class="footer">This document was generated electronically and does not require a signature.</div>
                </body>
                </html>
                """.formatted(
                HtmlEscaper.escape(statementDate),
                HtmlEscaper.escape(LENDER_NAME),
                HtmlEscaper.escape(user.getFirstName()),
                HtmlEscaper.escape(user.getLastName()),
                HtmlEscaper.escape(user.getUpn()),
                loan.getLoanAmount(),
                loan.getInstallmentsPaid(),
                loan.getTenureMonths(),
                balance,
                HtmlEscaper.escape(loan.getDueDate()),
                HtmlEscaper.escape(loan.getStatus())
        );
    }

    public String generateLoanClearanceHtml(RegisteredUser user, com.mfstechnologies.mymobi.model.Loan loan) {
        String letterDate = LocalDate.now().format(DATE_FORMAT);

        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <title>Loan Clearance Letter</title>
                <style>
                body { font-family: Arial, sans-serif; max-width: 600px; margin: 40px auto; padding: 20px; color: #222; }
                h1 { font-size: 20px; border-bottom: 2px solid #333; padding-bottom: 10px; }
                table { width: 100%%; border-collapse: collapse; margin-top: 20px; }
                td { padding: 8px 4px; border-bottom: 1px solid #eee; }
                td:first-child { font-weight: bold; width: 40%%; }
                .footer { margin-top: 30px; font-size: 12px; color: #777; }
                </style>
                </head>
                <body>
                <h1>Loan Clearance Letter</h1>
                <table>
                <tr><td>Letter Date</td><td>%s</td></tr>
                <tr><td>Lender</td><td>%s</td></tr>
                <tr><td>First Name</td><td>%s</td></tr>
                <tr><td>Last Name</td><td>%s</td></tr>
                <tr><td>UPN</td><td>%s</td></tr>
                <tr><td>Loan Repayment Amount</td><td>KES %,d</td></tr>
                <tr><td>Loan Balance</td><td>KES 0</td></tr>
                <tr><td>Loan Due Date</td><td>%s</td></tr>
                <tr><td>Loan Status</td><td>%s</td></tr>
                </table>
                <div class="footer">This letter confirms the above loan has been fully repaid. Generated electronically and does not require a signature.</div>
                </body>
                </html>
                """.formatted(
                HtmlEscaper.escape(letterDate),
                HtmlEscaper.escape(LENDER_NAME),
                HtmlEscaper.escape(user.getFirstName()),
                HtmlEscaper.escape(user.getLastName()),
                HtmlEscaper.escape(user.getUpn()),
                loan.getLoanAmount(),
                HtmlEscaper.escape(loan.getDueDate()),
                HtmlEscaper.escape(loan.getStatus())
        );
    }

    private int calculateBalance(com.mfstechnologies.mymobi.model.Loan loan) {
        if (loan.getBreakdown() == null) {
            return 0;
        }
        int remainingInstallments = loan.getTenureMonths() - loan.getInstallmentsPaid();
        return Math.max(0, remainingInstallments * loan.getBreakdown().monthlyInstallment());
    }
}