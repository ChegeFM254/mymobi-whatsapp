package com.mfstechnologies.mymobi.model;

/**
 * A registered account - equivalent of the object shape stored in
 * registeredUsers[phoneNumber] in the Node.js version. hashedPin is
 * exactly that: a BCrypt hash, never the raw PIN.
 *
 * Written with plain, manual getters/setters rather than Lombok - see
 * the note in UserSession.java for why.
 */
public class RegisteredUser {
    private String firstName;
    private String middleName;
    private String lastName;
    private String emailAddress;
    private String upn;
    private String nationalId;
    private String mobileNumber;
    private String hashedPin;
    private String status = "active";
    private boolean testingBypassAccount = false;

    public RegisteredUser() {
    }

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

    public String getHashedPin() { return hashedPin; }
    public void setHashedPin(String hashedPin) { this.hashedPin = hashedPin; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isTestingBypassAccount() { return testingBypassAccount; }
    public void setTestingBypassAccount(boolean testingBypassAccount) { this.testingBypassAccount = testingBypassAccount; }
}
