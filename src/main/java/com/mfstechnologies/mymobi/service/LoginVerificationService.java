package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Verifies UPN + PIN for Log In — direct equivalent of
 * verifyLoginCredentials() from the Node.js version, including the
 * testing-mode bypass toggle.
 *
 * CURRENT (testing-mode) BEHAVIOR, controlled by
 * app.allow-login-without-stored-data (env var
 * ALLOW_LOGIN_WITHOUT_STORED_DATA, defaults to true):
 *  - If a local record already exists for this WhatsApp number, the
 *    entered UPN + PIN must genuinely match it.
 *  - If NO local record exists and the bypass is enabled, ANY
 *    correctly-formatted UPN + PIN is accepted, and a temporary
 *    synthetic account is created on the fly.
 *
 * FOR UAT/PRODUCTION: set ALLOW_LOGIN_WITHOUT_STORED_DATA=false in
 * Render's environment — no code change needed, exactly like the Node
 * version's equivalent flag.
 */
@Service
public class LoginVerificationService {

    private static final Logger log = LoggerFactory.getLogger(LoginVerificationService.class);

    private final RegisteredUserStore userStore;
    private final PasswordEncoder passwordEncoder;
    private final boolean allowLoginWithoutStoredData;

    public LoginVerificationService(
            RegisteredUserStore userStore,
            PasswordEncoder passwordEncoder,
            @Value("${app.allow-login-without-stored-data:true}") boolean allowLoginWithoutStoredData
    ) {
        this.userStore = userStore;
        this.passwordEncoder = passwordEncoder;
        this.allowLoginWithoutStoredData = allowLoginWithoutStoredData;
    }

    public record LoginResult(boolean success, RegisteredUser user) {
        static LoginResult failure() {
            return new LoginResult(false, null);
        }
    }

    public LoginResult verify(String phoneNumber, String upn, String pin) {
        Optional<RegisteredUser> existing = userStore.findByPhoneNumber(phoneNumber);

        if (existing.isPresent()) {
            RegisteredUser user = existing.get();
            boolean pinMatches = passwordEncoder.matches(pin, user.getHashedPin());
            if (upn.equals(user.getUpn()) && pinMatches) {
                return new LoginResult(true, user);
            }
            return LoginResult.failure();
        }

        if (!allowLoginWithoutStoredData) {
            // Defense in depth: the calling flow should already block
            // this earlier (see AuthenticationFlowService), but this
            // method stays correct on its own regardless of what calls it.
            return LoginResult.failure();
        }

        log.warn("login_testing_bypass_used phoneNumber={} upn={}", phoneNumber, upn);
        RegisteredUser synthetic = new RegisteredUser();
        synthetic.setFirstName("Test");
        synthetic.setLastName("User");
        synthetic.setUpn(upn);
        synthetic.setNationalId("00000000"); // placeholder — not real KYC data
        synthetic.setMobileNumber(phoneNumber);
        synthetic.setHashedPin(passwordEncoder.encode(pin));
        synthetic.setStatus("active");
        synthetic.setTestingBypassAccount(true);
        userStore.save(phoneNumber, synthetic);

        return new LoginResult(true, synthetic);
    }

    public boolean isAllowLoginWithoutStoredData() {
        return allowLoginWithoutStoredData;
    }
}