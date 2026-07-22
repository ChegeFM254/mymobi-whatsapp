package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ForgotPinFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;

    private RegisteredUserStore userStore;
    private LoginLockoutService lockoutService;
    private ForgotPinFlowService forgotPinFlowService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userStore = new RegisteredUserStore();
        lockoutService = new LoginLockoutService(600);
        forgotPinFlowService = new ForgotPinFlowService(screenService, messageService, userStore, passwordEncoder, lockoutService);
    }

    @Test
    void noAccountFoundReturnsAHelpfulMessage() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendCivilServantsMenu(FROM)).thenReturn(Mono.empty());

        forgotPinFlowService.handleForgotPin(FROM, session).block();

        verify(screenService).sendCivilServantsMenu(FROM);
        assertThat(session.getOtp()).isNull();
    }

    @Test
    void lockedOutAccountCannotStartTheFlow() {
        lockoutService.applyLockout(FROM);
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        forgotPinFlowService.handleForgotPin(FROM, session).block();

        assertThat(session.getOtp()).isNull();
    }

    @Test
    void existingAccountGetsAnOtpAndMovesToOtpStep() {
        userStore.save(FROM, new RegisteredUser());
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        forgotPinFlowService.handleForgotPin(FROM, session).block();

        assertThat(session.getStep()).isEqualTo("forgot_pin_enter_otp");
        assertThat(session.getOtp()).matches("^\\d{5}$");
    }

    @Test
    void correctOtpAdvancesToNewPinStep() {
        UserSession session = new UserSession();
        session.setOtp("12345");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        forgotPinFlowService.handleEnterOtp(FROM, "12345", session).block();

        assertThat(session.getStep()).isEqualTo("forgot_pin_enter_new_pin");
    }
    @Test
    void thirdWrongOtpAppliesLockout() {
        UserSession session = new UserSession();
        session.setOtp("12345");
        session.setOtpAttempts(2);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        lenient().when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        forgotPinFlowService.handleEnterOtp(FROM, "00000", session).block();

        assertThat(lockoutService.getLockoutMinutesRemaining(FROM)).isGreaterThan(0);
        assertThat(session.getStep()).isEqualTo("welcome");
    }

    @Test
    void newPinCannotMatchTheOtp() {
        UserSession session = new UserSession();
        session.setOtp("12345");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        forgotPinFlowService.handleEnterNewPin(FROM, "12345", session).block();

        assertThat(session.getNewPin()).isNull();
    }

    @Test
    void matchingConfirmationUpdatesTheExistingAccountsPin() {
        RegisteredUser existing = new RegisteredUser();
        existing.setHashedPin(passwordEncoder.encode("11111"));
        userStore.save(FROM, existing);

        UserSession session = new UserSession();
        session.setNewPin("99999");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        forgotPinFlowService.handleConfirmNewPin(FROM, "99999", session).block();

        RegisteredUser updated = userStore.findByPhoneNumber(FROM).get();
        assertThat(passwordEncoder.matches("11111", updated.getHashedPin())).isFalse();
        assertThat(passwordEncoder.matches("99999", updated.getHashedPin())).isTrue();
        assertThat(session.isAuthenticated()).isTrue();
        verify(screenService).sendMainMenu(FROM);
    }

    @Test
    void mismatchedConfirmationSendsBackToEnterNewPin() {
        UserSession session = new UserSession();
        session.setNewPin("99999");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        forgotPinFlowService.handleConfirmNewPin(FROM, "11111", session).block();

        assertThat(session.getStep()).isEqualTo("forgot_pin_enter_new_pin");
    }
}
