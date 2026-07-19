package com.mfstechnologies.mymobi.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A registered account — equivalent of the object shape stored in
 * registeredUsers[phoneNumber] in the Node.js version. hashedPin is
 * exactly that: a BCrypt hash, never the raw PIN — see SecurityConfig
 * and LoginVerificationService.
 */
@Data
@NoArgsConstructor
public class RegisteredUser {
    private String firstName;
    private String lastName;
    private String upn;
    private String nationalId;
    private String mobileNumber;
    private String hashedPin;
    private String status = "active";

    // Flags an account created by the testing-mode login bypass (see
    // LoginVerificationService) rather than a real Register flow — never
    // real KYC data. Kept visible in logs/debugging, never shown to users.
    private boolean testingBypassAccount = false;
}