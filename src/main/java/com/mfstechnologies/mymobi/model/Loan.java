package com.mfstechnologies.mymobi.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
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
    private String status; // pending_approval, approved, paid, cancelled
    private int installmentsPaid = 0;
    private Instant submittedAt;
    private Instant approvedAt;
}