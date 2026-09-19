package com.mfstechnologies.mymobi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A registered account - equivalent of the object shape stored in
 * registeredUsers[phoneNumber] in the Node.js version. hashedPin is
 * exactly that: a BCrypt hash, never the raw PIN.
 *
 * Written with plain, manual getters/setters rather than Lombok - see
 * the note in UserSession.java for why.
 *
 * WORKSTREAM C (persistence): now a proper JPA entity, backed by
 * Postgres via RegisteredUserRepository. phoneNumber is new here - it
 * didn't exist as a field before, since the phone number was always the
 * external Map key rather than something stored ON the object. JPA
 * entities need their own identity, so it's promoted to the @Id here.
 * This is purely additive: nothing in the existing flow services calls
 * getPhoneNumber(), since they already have the phone number as their
 * own "to" parameter throughout - so this addition can't break anything
 * that already exists.
 */
@Entity
@Table(name = "registered_users")
public class RegisteredUser {

    @Id
    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "middle_name")
    private String middleName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "email_address")
    private String emailAddress;

    @Column(name = "upn")
    private String upn;

    @Column(name = "national_id")
    private String nationalId;

    @Column(name = "mobile_number")
    private String mobileNumber;

    @Column(name = "hashed_pin")
    private String hashedPin;

    @Column(name = "status")
    private String status = "active";

    @Column(name = "testing_bypass_account")
    private boolean testingBypassAccount = false;

    public RegisteredUser() {
    }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

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
