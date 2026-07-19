package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers the core authentication flow — this is genuinely new business
 * logic ported tonight (not just infrastructure), so it gets the most
 * thorough test coverage of this session's additions.
 *
 * Uses a REAL LoginLockoutService and a REAL LoginVerificationService
 * (backed by a real BCryptPasswordEncoder and an in-memory
 * RegisteredUserStore) rather than mocking them — cheap enough to run
 * for real, and gives more genuine confidence than mocking business
 * logic this important.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;

    private LoginLockoutService lockoutService;
    private LoginVerificationService loginVerificationService;
    private AuthenticationFlowService authFlowService;

    @BeforeEach
    void setUp() {
        lockoutService = new LoginLockoutService();
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        loginVerificationService = new LoginVerificationService(
                new com.mfstechnologies.mymobi.session.RegisteredUserStore(),
                passwordEncoder,
                true // testing bypass ON, matching the default
        );
        authFlowService = new AuthenticationFlowService(screenService, messageService, lockoutService, loginVerificationService);
    }

    // ==================== CIVIL SERVANTS MENU ====================

    @Test
    void authenticatedSessionGoesStraightToMainMenu() {
        UserSession session = new UserSession();
        session.setAuthenticated(true);
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        authFlowService.handleCivilServants(FROM, session).block();

        verify(screenService).sendMainMenu(FROM);
        verify(screenService, never()).sendCivilServantsMenu(anyString());
    }

    @Test
    void unauthenticatedSessionSeesTheCivilServantsMenu() {
        UserSession session = new UserSession(); // authenticated=false by default
        when(screenService.sendCivilServantsMenu(FROM)).thenReturn(Mono.empty());

        authFlowService.handleCivilServants(FROM, session).block();

        verify(screenService).sendCivilServantsMenu(FROM);
        verify(screenService, never()).sendMainMenu(anyString());
    }

    // ==================== LOGIN: UPN STEP ====================

    @Test
    void loginMenuPromptsForUpnWhenNotLockedOut() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        authFlowService.handleLoginMenu(FROM, session).block();

        assertThat(session.getStep()).isEqualTo("login_enter_upn");
        verify(messageService).sendTextMessage(FROM, "Enter UPN:");
    }

    @Test
    void loginMenuRefusesToProceedWhileLockedOut() {
        lockoutService.applyLockout(FROM);
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        authFlowService.handleLoginMenu(FROM, session).block();

        assertThat(session.getStep()).isNotEqualTo("login_enter_upn");
        verify(messageService).sendTextMessage(eq(FROM), contains("temporarily locked"));
    }

    @Test
    void invalidUpnFormatIsRejectedWithoutAdvancingTheStep() {
        UserSession session = new UserSession();
        session.setStep("login_enter_upn");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        authFlowService.handleLoginEnterUpn(FROM, "notanumber", session).block();

        assertThat(session.getStep()).isEqualTo("login_enter_upn"); // unchanged
        verify(messageService).sendTextMessage(eq(FROM), contains("UPN"));
    }

    @Test
    void validUpnAdvancesToPinStep() {
        UserSession session = new UserSession();
        session.setStep("login_enter_upn");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        authFlowService.handleLoginEnterUpn(FROM, "12345", session).block();

        assertThat(session.getStep()).isEqualTo("login_enter_pin");
        assertThat(session.getLoginUpn()).isEqualTo("12345");
        verify(messageService).sendTextMessage(FROM, "Enter PIN:");
    }

    // ==================== LOGIN: PIN STEP ====================

    @Test
    void correctPinAdvancesToVerificationCodeStep() {
        UserSession session = new UserSession();
        session.setStep("login_enter_pin");
        session.setLoginUpn("12345"); // testing bypass: any correctly-formatted UPN+PIN succeeds
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        authFlowService.handleLoginEnterPin(FROM, "54321", session).block();

        assertThat(session.getStep()).isEqualTo("login_enter_verification_code");
        assertThat(session.getVerificationCode()).isNotNull();
        verify(messageService).sendTextMessage(FROM, "Enter Verification Code:");
    }

    @Test
    void wrongPinAgainstARealRecordIncrementsAttemptsWithoutLockingOutImmediately() {
        // Seed a REAL record so this isn't the testing-bypass path.
        var realUser = new com.mfstechnologies.mymobi.model.RegisteredUser();
        realUser.setUpn("19999999");
        realUser.setHashedPin(new BCryptPasswordEncoder().encode("11111"));
        var userStore = new com.mfstechnologies.mymobi.session.RegisteredUserStore();
        userStore.save(FROM, realUser);
        var realLoginService = new LoginVerificationService(userStore, new BCryptPasswordEncoder(), true);
        var flowWithRealUser = new AuthenticationFlowService(screenService, messageService, lockoutService, realLoginService);

        UserSession session = new UserSession();
        session.setStep("login_enter_pin");
        session.setLoginUpn("19999999");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        flowWithRealUser.handleLoginEnterPin(FROM, "00000", session).block(); // wrong PIN

        assertThat(session.getLoginAttempts()).isEqualTo(1);
        assertThat(session.getStep()).isEqualTo("login_enter_pin"); // did not advance
        verify(messageService).sendTextMessage(eq(FROM), contains("2 attempt(s) remaining"));
    }

    @Test
    void thirdConsecutiveWrongAttemptTriggersLockout() {
        var realUser = new com.mfstechnologies.mymobi.model.RegisteredUser();
        realUser.setUpn("19999999");
        realUser.setHashedPin(new BCryptPasswordEncoder().encode("11111"));
        var userStore = new com.mfstechnologies.mymobi.session.RegisteredUserStore();
        userStore.save(FROM, realUser);
        var realLoginService = new LoginVerificationService(userStore, new BCryptPasswordEncoder(), true);
        var flowWithRealUser = new AuthenticationFlowService(screenService, messageService, lockoutService, realLoginService);

        UserSession session = new UserSession();
        session.setStep("login_enter_pin");
        session.setLoginUpn("19999999");
        session.setLoginAttempts(2); // already failed twice
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        flowWithRealUser.handleLoginEnterPin(FROM, "00000", session).block(); // third wrong attempt

        assertThat(lockoutService.getLockoutMinutesRemaining(FROM)).isGreaterThan(0);
        verify(messageService).sendTextMessage(eq(FROM), contains("locked for 10 minutes"));
    }

    // ==================== LOGIN: VERIFICATION CODE STEP ====================

    @Test
    void correctVerificationCodeCompletesLoginAndShowsMainMenu() {
        UserSession session = new UserSession();
        session.setStep("login_enter_verification_code");
        session.setVerificationCode("98765");
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        authFlowService.handleLoginEnterVerificationCode(FROM, "98765", session).block();

        assertThat(session.isAuthenticated()).isTrue();
        assertThat(session.getVerificationCode()).isNull(); // cleared after use
        verify(screenService).sendMainMenu(FROM);
    }

    @Test
    void wrongVerificationCodeDoesNotAuthenticateTheSession() {
        UserSession session = new UserSession();
        session.setStep("login_enter_verification_code");
        session.setVerificationCode("98765");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        authFlowService.handleLoginEnterVerificationCode(FROM, "00000", session).block();

        assertThat(session.isAuthenticated()).isFalse();
        verify(screenService, never()).sendMainMenu(anyString());
    }
}