package com.mfstechnologies.mymobi.model;

import java.time.Instant;

public class Loan {
    private int loanAmount;
    private int tenureMonths;
    private LoanBreakdown breakdown;
    private String payrollNumber;
    private String refNo;
    private String approvalCode;
    private int approvalCodeAttempts = 0;
    private int approvalPayrollAttempts = 0;
    private String dueDate;
    private String status;
    private int installmentsPaid = 0;
    private boolean paymentInProgress = false;
    private Instant submittedAt;
    private Instant approvedAt;

    public Loan() {
    }

    public int getLoanAmount() { return loanAmount; }
    public void setLoanAmount(int loanAmount) { this.loanAmount = loanAmount; }

    public int getTenureMonths() { return tenureMonths; }
    public void setTenureMonths(int tenureMonths) { this.tenureMonths = tenureMonths; }

    public LoanBreakdown getBreakdown() { return breakdown; }
    public void setBreakdown(LoanBreakdown breakdown) { this.breakdown = breakdown; }

    public String getPayrollNumber() { return payrollNumber; }
    public void setPayrollNumber(String payrollNumber) { this.payrollNumber = payrollNumber; }

    public String getRefNo() { return refNo; }
    public void setRefNo(String refNo) { this.refNo = refNo; }

    public String getApprovalCode() { return approvalCode; }
    public void setApprovalCode(String approvalCode) { this.approvalCode = approvalCode; }

    public int getApprovalCodeAttempts() { return approvalCodeAttempts; }
    public void setApprovalCodeAttempts(int approvalCodeAttempts) { this.approvalCodeAttempts = approvalCodeAttempts; }

    public int getApprovalPayrollAttempts() { return approvalPayrollAttempts; }
    public void setApprovalPayrollAttempts(int approvalPayrollAttempts) { this.approvalPayrollAttempts = approvalPayrollAttempts; }

    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getInstallmentsPaid() { return installmentsPaid; }
    public void setInstallmentsPaid(int installmentsPaid) { this.installmentsPaid = installmentsPaid; }

    public boolean isPaymentInProgress() { return paymentInProgress; }
    public void setPaymentInProgress(boolean paymentInProgress) { this.paymentInProgress = paymentInProgress; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
}
