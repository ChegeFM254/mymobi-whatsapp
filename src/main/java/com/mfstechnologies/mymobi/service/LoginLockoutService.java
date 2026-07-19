package com.mfstechnologies.mymobi.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary 10-minute login lockout after too many failed attempts —
 * direct equivalent of loginLockouts / applyLoginLockout() /
 * getLoginLockoutMinutesRemaining() from the Node.js version.
 *
 * Keyed by phone number rather than the user record, so a lockout still
 * applies even if the entered UPN never matched any local account —
 * otherwise repeatedly guessing a wrong UPN would never trigger it.
 */
@Service
public class LoginLockoutService {

    public static final int MAX_LOGIN_ATTEMPTS = 3;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(10);

    private final Map<String, Instant> lockouts = new ConcurrentHashMap<>();

    /** @return minutes remaining on the lockout, or 0 if not currently locked out */
    public long getLockoutMinutesRemaining(String phoneNumber) {
        Instant unlockAt = lockouts.get(phoneNumber);
        if (unlockAt == null) {
            return 0;
        }

        long remainingSeconds = Duration.between(Instant.now(), unlockAt).getSeconds();
        if (remainingSeconds <= 0) {
            lockouts.remove(phoneNumber);
            return 0;
        }

        return ceilDivide(remainingSeconds, 60); // round UP to the next minute, matching Math.ceil() in the Node version
    }

    public void applyLockout(String phoneNumber) {
        lockouts.put(phoneNumber, Instant.now().plus(LOCKOUT_DURATION));
    }

    private long ceilDivide(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }
}