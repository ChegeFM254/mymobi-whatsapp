package com.mfstechnologies.mymobi.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginLockoutServiceTest {

    @Test
    void noLockoutByDefault() {
        LoginLockoutService service = new LoginLockoutService(0);
        assertThat(service.getLockoutMinutesRemaining("254700000001")).isZero();
    }

    @Test
    void testingModeDefaultOfZeroSecondsAllowsImmediateRetryAfterLockout() {
        LoginLockoutService service = new LoginLockoutService(0);
        service.applyLockout("254700000002");

        assertThat(service.getLockoutMinutesRemaining("254700000002")).isZero();
    }

    @Test
    void uatConfiguredDurationOfTenMinutesActuallyBlocksLogin() {
        LoginLockoutService service = new LoginLockoutService(600);
        service.applyLockout("254700000003");

        long remaining = service.getLockoutMinutesRemaining("254700000003");

        assertThat(remaining).isBetween(9L, 10L);
    }

    @Test
    void lockoutIsPerPhoneNumber() {
        LoginLockoutService service = new LoginLockoutService(600);
        service.applyLockout("254700000004");

        assertThat(service.getLockoutMinutesRemaining("254700000004")).isGreaterThan(0);
        assertThat(service.getLockoutMinutesRemaining("254700000005")).isZero();
    }

    @Test
    void maxAttemptsMatchesConfirmedRuleOfThree() {
        assertThat(LoginLockoutService.MAX_LOGIN_ATTEMPTS).isEqualTo(3);
    }
}
