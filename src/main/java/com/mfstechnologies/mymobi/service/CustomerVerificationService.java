package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.RegisteredUser;

/**
 * WORKSTREAM F (mock service abstraction layer): a clean seam for
 * verifying a person's UPN + PIN against the authoritative customer
 * record at login - everywhere in the app that needs to verify login
 * credentials calls this interface, rather than depending directly on
 * LoginVerificationService's concrete type.
 *
 * Unlike MpesaService/SmsService/LoanCalculationService, this interface
 * is NOT backed by a pure placeholder mock - LoginVerificationService
 * already has a genuine production-ready mode (set
 * ALLOW_LOGIN_WITHOUT_STORED_DATA=false) alongside its testing-bypass
 * mode, so it keeps its own name as the implementation here rather than
 * being renamed with a "Mock" prefix.
 */
public interface CustomerVerificationService {

    record LoginResult(boolean success, RegisteredUser user) {
        static LoginResult failure() {
            return new LoginResult(false, null);
        }
    }

    /**
     * Verifies the given UPN + PIN for the person at this phone number.
     */
    LoginResult verify(String phoneNumber, String upn, String pin);
}
