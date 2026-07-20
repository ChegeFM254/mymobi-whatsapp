package com.mfstechnologies.mymobi.model;

import java.time.Instant;

public class UserSession {

    private String step = "welcome";
    private boolean newSession = true;
    private Instant lastProcessedAt;
    private boolean authenticated = false;

    private int loginAttempts = 0;
    private String loginUpn;
    private String verificationCode;

    private String firstName;
    private String lastName;
    private String upn;
    private String nationalId;
    private String mobileNumber;

    private String otp;
    private int otpAttempts = 0;
    private String newPin;

    private Integer loanTenureMonths;
    private Integer loanLimit;
    private Integer loanAmount;
    private int payrollNumberAttempts = 0;

    private String currentMenu;

    private Integer pendingPaymentInstallments;

    private String pendingDocumentType;
    private Integer pendingDocumentMonths;

    public UserSession() {
    }

    public String getStep() { return step; }
    public void setStep(String step) { this.step = step; }

    public boolean isNewSession() { return newSession; }
    public void setNewSession(boolean newSession) { this.newSession = newSession; }

    public Instant getLastProcessedAt() { return lastProcessedAt; }
    public void setLastProcessedAt(Instant lastProcessedAt) { this.lastProcessedAt = lastProcessedAt; }

    public boolean isAuthenticated() { return authenticated; }
    public void setAuthenticated(boolean authenticated) { this.authenticated = authenticated; }

    public int getLoginAttempts() { return loginAttempts; }
    public void setLoginAttempts(int loginAttempts) { this.loginAttempts = loginAttempts; }

    public String getLoginUpn() { return loginUpn; }
    public void setLoginUpn(String loginUpn) { this.loginUpn = loginUpn; }

    public String getVerificationCode() { return verificationCode; }
    public void setVerificationCode(String verificationCode) { this.verificationCode = verificationCode; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getUpn() { return upn; }
    public void setUpn(String upn) { this.upn = upn; }

    public String getNationalId() { return nationalId; }
    public void setNationalId(String nationalId) { this.nationalId = nationalId; }

    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }

    public String getOtp() { return otp; }
    public void setOtp(String otp) { this.otp = otp; }

    public int getOtpAttempts() { return otpAttempts; }
    public void setOtpAttempts(int otpAttempts) { this.otpAttempts = otpAttempts; }

    public String getNewPin() { return newPin; }
    public void setNewPin(String newPin) { this.newPin = newPin; }

    public Integer getLoanTenureMonths() { return loanTenureMonths; }
    public void setLoanTenureMonths(Integer loanTenureMonths) { this.loanTenureMonths = loanTenureMonths; }

    public Integer getLoanLimit() { return loanLimit; }
    public void setLoanLimit(Integer loanLimit) { this.loanLimit = loanLimit; }

    public Integer getLoanAmount() { return loanAmount; }
    public void setLoanAmount(Integer loanAmount) { this.loanAmount = loanAmount; }

    public int getPayrollNumberAttempts() { return payrollNumberAttempts; }
    public void setPayrollNumberAttempts(int payrollNumberAttempts) { this.payrollNumberAttempts = payrollNumberAttempts; }

    public String getCurrentMenu() { return currentMenu; }
    public void setCurrentMenu(String currentMenu) { this.currentMenu = currentMenu; }

    public Integer getPendingPaymentInstallments() { return pendingPaymentInstallments; }
    public void setPendingPaymentInstallments(Integer pendingPaymentInstallments) { this.pendingPaymentInstallments = pendingPaymentInstallments; }

    public String getPendingDocumentType() { return pendingDocumentType; }
    public void setPendingDocumentType(String pendingDocumentType) { this.pendingDocumentType = pendingDocumentType; }

    public Integer getPendingDocumentMonths() { return pendingDocumentMonths; }
    public void setPendingDocumentMonths(Integer pendingDocumentMonths) { this.pendingDocumentMonths = pendingDocumentMonths; }
}
