package com.mfstechnologies.mymobi.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
public class UserSession {

    private String step = "welcome";
    private boolean newSession = true;
    private Instant lastProcessedAt;
    private boolean authenticated = false;

    // Login flow (UPN -> PIN -> Verification Code)
    private int loginAttempts = 0;
    private String loginUpn;
    private String verificationCode;

    // Populated once KYC/registration flows are ported (see README).
    private String firstName;
    private String lastName;
    private String upn;
    private String nationalId;
    private String mobileNumber;

    // Registration flow (OTP + new PIN, entered before it's hashed and saved)
    private String otp;
    private int otpAttempts = 0;
    private String newPin;

    // Apply Loan flow - cleared once the loan is submitted or declined
    private Integer loanTenureMonths;
    private Integer loanLimit;
    private Integer loanAmount;
    private int payrollNumberAttempts = 0;

    // Tracks which navigable loan screen is currently shown, so "Back"
    // can return to the right place. Only used by screens that actually
    // show a Back option - see LoanApplicationFlowService.handleBack().
    private String currentMenu;

    // Pay Loan flow - how many installments the person selected to pay,
    // between tapping the option and confirming.
    private Integer pendingPaymentInstallments;

    // Document purchase flow (Payslip, Loan Statement, Loan Clearance)
    private String pendingDocumentType;
    private Integer pendingDocumentMonths;
}
