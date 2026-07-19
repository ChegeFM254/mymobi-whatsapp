package com.mfstechnologies.mymobi.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Per-user conversation state — direct equivalent of the dynamically-typed
 * session object in the Node.js version (userSessions[from]).
 *
 * NOTE on scope: the Node version's session object grew organically to
 * hold 25+ possible fields across every flow built over the course of
 * that project (KYC, PIN/OTP, loan applications, document purchases,
 * login attempts, etc). Rather than speculatively porting every field
 * before the flows that use them exist here, this class holds the core
 * fields needed for what's been built so far (webhook receiving, Welcome,
 * Login, Registration) plus the most immediately-next fields. It will
 * grow incrementally alongside each flow that gets ported, the same way
 * the Node session object did — this is intentional, not an oversight.
 *
 * Also worth deciding explicitly in a future session: whether this stays
 * a single wide class (direct port, simplest) or gets split into a
 * proper per-flow state machine (cleaner Java design, more work). Not
 * decided yet — flagging so it isn't decided by default via inertia.
 */
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
}