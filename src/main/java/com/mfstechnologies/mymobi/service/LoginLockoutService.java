package com.mfstechnologies.mymobi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginLockoutService {

    public static final int MAX_LOGIN_ATTEMPTS = 3;

    private final Duration lockoutDuration;
    private final Map<String, Instant> lockouts = new ConcurrentHashMap<>();

    public LoginLockoutService(@Value("${app.login-lockout-duration-seconds:0}") long lockoutDurationSeconds) {
        this.lockoutDuration = Duration.ofSeconds(lockoutDurationSeconds);
    }

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

        return ceilDivide(remainingSeconds, 60);
    }

    public void applyLockout(String phoneNumber) {
        lockouts.put(phoneNumber, Instant.now().plus(lockoutDuration));
    }

    private long ceilDivide(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }
}
