package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.OutboxEntry;
import com.mfstechnologies.mymobi.model.OutboxEntryType;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.RegisteredUserRepository;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import com.mfstechnologies.mymobi.testsupport.FakeRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WORKSTREAM B (reactive -> synchronous): rewritten for the now-void
 * ForgotPinFlowService methods. No more .block() calls or Mono.empty()
 * stubs anywhere. Note: thirdWrongOtpAppliesLockout previously needed
 * lenient() around its sendWelcome stub due to a reactive-specific
 * timing quirk (documented as an unresolved mystery at the time) - now
 * that everything is synchronous, no stubbing of void methods is needed
 * at all, so that workaround is gone entirely rather than carried
 * forward.
 *
 * WORKSTREAM C (persistence): RegisteredUserStore is now Postgres-backed
 * - userStore here is wired to a fake, in-memory-backed repository (see
 * FakeRepositories) so it keeps behaving like a real, working
 * collaborator, exactly as it did with the old ConcurrentHashMap.
 *
 * WORKSTREAM E (WhatsApp Flows webview for PIN/OTP/Approval Code): every
 * OTP/PIN entry point now goes through screenService.sendCodeEntryFlow()
 * instead of a plain text prompt - including every retry path, which
 * must re-send the Flow so the person never has to fall back to typing
 * the value directly into the chat.
 *
 * WORKSTREAM G (outbox pattern): OTP delivery now goes through
 * outboxService.enqueue(...) (mocked here) instead of a direct
 * SmsService call - a plain enqueue, since nothing is persisted yet at
 * this point in the flow (see ForgotPinFlowService's class-level note).
 *
 * matchingConfirmationUpdatesTheExistingAccountsPin now also verifies
 * registeredUserRepository.save(...) is actually called - this test
 * previously passed even when handleConfirmNewPin was missing its
 * userStore.save() call entirely (a genuine bug, fixed alongside this
 * workstream), because FakeRepositories' backing HashMap stores the same
 * Java object reference rather than a true copy the way a real database
 * would - so checking only the in-memory field value after the call
 * could never have caught a missing persistence call. The explicit
 * verify(...).save(...) now makes that failure mode actually testable.
 */
@ExtendWith(MockitoExtension.class)
class ForgotPinFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
        @Mock
    private WhatsAppMessageService messageService;
    @Mock
    private OutboxService outboxService;
    @Mock
    private RegisteredUserRepository registeredUserRepository;

    private RegisteredUserStore userStore;
    private LoginLockoutService lockoutService;
    private ForgotPinFlowService forgotPinFlowService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        FakeRepositories.wireAsInMemoryStore(registeredUserRepository, RegisteredUser::getPhoneNumber);
        userStore = new RegisteredUserStore(registeredUserRepository);
        lockoutService = new LoginLockoutService(600);
        forgotPinFlowService = new ForgotPinFlowService(screenService, messageService, outboxService, userStore, passwordEncoder, lockoutService);

        lenient().when(outboxService.enqueue(any(), any())).thenReturn(new OutboxEntry());
    }

    @Test
    void noAccountFoundReturnsAHelpfulMessage() {
        UserSession session = new UserSession();

        forgotPinFlowService.handleForgotPin(FROM, session);

        verify(screenService).sendCivilServantsMenu(FROM);
        assertThat(session.getOtp()).isNull();
    }

    @Test
    void lockedOutAccountCannotStartTheFlow() {
        lockoutService.applyLockout(FROM);
        UserSession session = new UserSession();

        forgotPinFlowService.handleForgotPin(FROM, session);

        assertThat(session.getOtp()).isNull();
    }

    @Test
    void existingAccountGetsAnOtpSentViaTheOutboxAndMovesToOtpStep() {
        userStore.save(FROM, new RegisteredUser());
        UserSession session = new UserSession();

        forgotPinFlowService.handleForgotPin(FROM, session);

        assertThat(session.getStep()).isEqualTo("forgot_pin_enter_otp");
        assertThat(session.getOtp()).matches("^\\d{5}$");
        // WORKSTREAM G: OTP delivery now goes through the outbox.
        verify(outboxService).enqueue(
                eq(OutboxEntryType.OTP_SMS),
                argThat(payload -> payload instanceof OutboxPayloads.SmsPayload p
                        && p.phoneNumber().equals(FROM)
                        && p.message().contains(session.getOtp()))
        );
        // WORKSTREAM E: OTP entry now goes through the Flow webview.
        verify(screenService).sendCodeEntryFlow(eq(FROM), anyString(), anyString());
    }

    @Test
    void correctOtpAdvancesToNewPinStep() {
        UserSession session = new UserSession();
                session.setOtp("12345");

        forgotPinFlowService.handleEnterOtp(FROM, "12345", session);

        assertThat(session.getStep()).isEqualTo("forgot_pin_enter_new_pin");
        verify(screenService).sendCodeEntryFlow(eq(FROM), anyString(), anyString());
    }

    @Test
    void wrongOtpBelowMaxAttemptsResendsTheFlowForARetry() {
        UserSession session = new UserSession();
        session.setOtp("12345");

        forgotPinFlowService.handleEnterOtp(FROM, "00000", session);

        assertThat(session.getOtpAttempts()).isEqualTo(1);
        verify(messageService).sendTextMessage(eq(FROM), contains("2 attempt(s) remaining"));
        // WORKSTREAM E: the Flow must be re-sent so the retry also
        // happens securely, not by falling back to typing in chat.
        verify(screenService).sendCodeEntryFlow(eq(FROM), anyString(), anyString());
    }

    @Test
    void thirdWrongOtpAppliesLockout() {
        UserSession session = new UserSession();
        session.setOtp("12345");
        session.setOtpAttempts(2);

        forgotPinFlowService.handleEnterOtp(FROM, "00000", session);

        assertThat(lockoutService.getLockoutMinutesRemaining(FROM)).isGreaterThan(0);
        assertThat(session.getStep()).isEqualTo("welcome");
        verify(screenService).sendWelcome(FROM);
    }

    @Test
    void newPinCannotMatchTheOtp() {
        UserSession session = new UserSession();
        session.setOtp("12345");

        forgotPinFlowService.handleEnterNewPin(FROM, "12345", session);

        assertThat(session.getNewPin()).isNull();
        // WORKSTREAM E: the Flow must be re-sent so the retry also
        // happens securely, not by falling back to typing in chat.
        verify(screenService).sendCodeEntryFlow(eq(FROM), anyString(), anyString());
    }

    @Test
    void matchingConfirmationUpdatesTheExistingAccountsPin() {
        RegisteredUser existing = new RegisteredUser();
        existing.setHashedPin(passwordEncoder.encode("11111"));
        userStore.save(FROM, existing);
        // Clears the repository's invocation history, which already
        // includes one save() from the setup line above - without this,
        // verify(...).save(...) below would pass even if
        // handleConfirmNewPin never saved anything itself, since "at
        // least once" would already be satisfied by that earlier call.
        clearInvocations(registeredUserRepository);

        UserSession session = new UserSession();
        session.setNewPin("99999");

        forgotPinFlowService.handleConfirmNewPin(FROM, "99999", session);
                RegisteredUser updated = userStore.findByPhoneNumber(FROM).get();
        assertThat(passwordEncoder.matches("11111", updated.getHashedPin())).isFalse();
        assertThat(passwordEncoder.matches("99999", updated.getHashedPin())).isTrue();
        assertThat(session.isAuthenticated()).isTrue();
        verify(screenService).sendMainMenu(FROM);
        // WORKSTREAM G (bug fix, unrelated to the outbox itself): confirms
        // the new PIN is genuinely persisted, not just mutated in memory -
        // see class-level note on why the assertions above alone
        // wouldn't have caught this bug.
        verify(registeredUserRepository).save(updated);
    }

    @Test
    void mismatchedConfirmationSendsBackToEnterNewPin() {
        UserSession session = new UserSession();
        session.setNewPin("99999");

        forgotPinFlowService.handleConfirmNewPin(FROM, "11111", session);

        assertThat(session.getStep()).isEqualTo("forgot_pin_enter_new_pin");
        // WORKSTREAM E: the Flow must be re-sent so the retry also
        // happens securely, not by falling back to typing in chat.
        verify(screenService).sendCodeEntryFlow(eq(FROM), anyString(), anyString());
    }
}
