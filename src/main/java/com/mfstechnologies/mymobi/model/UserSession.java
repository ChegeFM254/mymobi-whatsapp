package com.mfstechnologies.mymobi.model;

import java.time.Instant;

/**
 * Per-user conversation state - direct equivalent of the dynamically-typed
 * session object in the Node.js version (userSessions[from]).
 *
 * NOTE: written with plain, manual getters/setters rather than Lombok's
 * @Data annotation - Lombok's annotation processor was found to silently
 * fail to generate any methods in one specific Docker build environment,
 * so it was removed entirely to eliminate that as a variable, even
 * though it worked correctly everywhere else this was tested.
 */
public class UserSession {

    private String step = "welcome";
    private boolean newSession = true;
    private Instant lastProcessedAt;
    private boolean authenticated = false;

    private int loginAttempts = 0;
    private String loginUpn;
    private String verificationCode;

    private String firstName;
    private String middleName;
    private String lastName;
    private String emailAddress;
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

    /**
     * WORKSTREAM C (persistence): resets every field back to a fresh
     * UserSession's defaults, in place on this same object. Needed once
     * SessionStore moved to Redis - the old in-memory version could
     * achieve "make this look like a brand new session" simply by
     * removing the Map entry (sessionStore.delete(...)), since any FUTURE
     * getOrCreate() call would then legitimately construct a fresh
     * object. With Redis, ConversationService saves back whatever state
     * THIS SAME in-flight object ends up in, in its own finally block,
     * so achieving "look brand new" requires actually resetting this
     * object's fields, not just removing a soon-to-be-overwritten Redis
     * entry.
     */
    public void reset() {
        this.step = "welcome";
        this.newSession = true;
        this.lastProcessedAt = null;
        this.authenticated = false;
        this.loginAttempts = 0;
        this.loginUpn = null;
        this.verificationCode = null;
        this.firstName = null;
        this.middleName = null;
        this.lastName = null;
        this.emailAddress = null;
        this.upn = null;
        this.nationalId = null;
        this.mobileNumber = null;
        this.otp = null;
        this.otpAttempts = 0;
        this.newPin = null;
        this.loanTenureMonths = null;
                this.loanLimit = null;
        this.loanAmount = null;
        this.payrollNumberAttempts = 0;
        this.currentMenu = null;
        this.pendingPaymentInstallments = null;
        this.pendingDocumentType = null;
        this.pendingDocumentMonths = null;
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

    public String getMiddleName() { return middleName; }
    public void setMiddleName(String middleName) { this.middleName = middleName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmailAddress() { return emailAddress; }
    public void setEmailAddress(String emailAddress) { this.emailAddress = emailAddress; }

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
