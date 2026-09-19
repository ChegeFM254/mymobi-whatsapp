package com.mfstechnologies.mymobi.model;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * WORKSTREAM C (persistence): now a proper JPA entity, backed by
 * Postgres via LoanRepository. phoneNumber is new here - it didn't
 * exist as a field before, since the phone number was always the
 * external Map key rather than something stored ON the object (same
 * situation as RegisteredUser). This is purely additive: nothing in the
 * existing flow services calls getPhoneNumber(), since they already
 * have the phone number as their own "to" parameter throughout.
 *
 * breakdown is @Embedded - LoanBreakdown is marked @Embeddable and
 * stores its 5 fields directly as columns on this same loans table,
 * rather than needing its own separate table. @AttributeOverrides
 * renames every one of those 5 columns with a breakdown_ prefix -
 * without this, LoanBreakdown.loanAmount would map to the exact same
 * physical column name (loan_amount) that Loan's own top-level
 * loanAmount field already uses, which Hibernate correctly rejects as a
 * DuplicateMappingException rather than silently colliding the two.
 */
@Entity
@Table(name = "loans")
@AttributeOverrides({
        @AttributeOverride(name = "loanAmount", column = @Column(name = "breakdown_loan_amount")),
        @AttributeOverride(name = "upfrontFee", column = @Column(name = "breakdown_upfront_fee")),
        @AttributeOverride(name = "disbursement", column = @Column(name = "breakdown_disbursement")),
        @AttributeOverride(name = "monthlyInstallment", column = @Column(name = "breakdown_monthly_installment")),
        @AttributeOverride(name = "platformFee", column = @Column(name = "breakdown_platform_fee"))
})
public class Loan {

    @Id
    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "loan_amount")
    private int loanAmount;

    @Column(name = "tenure_months")
    private int tenureMonths;

    @Embedded
    private LoanBreakdown breakdown;

    @Column(name = "payroll_number")
    private String payrollNumber;

    @Column(name = "ref_no")
    private String refNo;

    @Column(name = "approval_code")
    private String approvalCode;

    @Column(name = "approval_code_attempts")
    private int approvalCodeAttempts = 0;

    @Column(name = "approval_payroll_attempts")
    private int approvalPayrollAttempts = 0;

    @Column(name = "due_date")
    private String dueDate;

    @Column(name = "status")
    private String status;

    @Column(name = "installments_paid")
    private int installmentsPaid = 0;

    @Column(name = "payment_in_progress")
    private boolean paymentInProgress = false;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    public Loan() {
    }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

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
