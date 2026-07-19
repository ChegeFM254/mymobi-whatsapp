package com.mfstechnologies.mymobi.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginLockoutServiceTest {

    @Test
    void noLockoutByDefault() {
        LoginLockoutService service = new LoginLockoutService();
        assertThat(service.getLockoutMinutesRemaining("254700000001")).isZero();
    }

    @Test
    void appliedLockoutReportsApproximatelyTenMinutesRemaining() {
        LoginLockoutService service = new LoginLockoutService();
        service.applyLockout("254700000002");

        long remaining = service.getLockoutMinutesRemaining("254700000002");

        assertThat(remaining).isBetween(9L, 10L);
    }

    @Test
    void lockoutIsPerPhoneNumber() {
        LoginLockoutService service = new LoginLockoutService();
        service.applyLockout("254700000003");

        assertThat(service.getLockoutMinutesRemaining("254700000003")).isGreaterThan(0);
        assertThat(service.getLockoutMinutesRemaining("254700000004")).isZero();
    }

    @Test
    void maxAttemptsMatchesConfirmedRuleOfThree() {
        assertThat(LoginLockoutService.MAX_LOGIN_ATTEMPTS).isEqualTo(3);
    }
}