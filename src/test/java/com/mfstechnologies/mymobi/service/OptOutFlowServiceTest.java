package com.mfstechnologies.mymobi.service;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WORKSTREAM B (reactive -> synchronous): rewritten for the now-void
 * OptOutFlowService methods. No more .block() calls or Mono.empty()
 * stubs anywhere.
 *
 * WORKSTREAM C (persistence): RegisteredUserStore is now Postgres-backed
 * - userStore here is wired to a fake, in-memory-backed repository (see
 * FakeRepositories) so it keeps behaving like a real, working
 * collaborator, exactly as it did with the old ConcurrentHashMap -
 * including genuinely deleting a record on delete(), which the Opt Out
 * data-protection tests below depend on.
 */
@ExtendWith(MockitoExtension.class)
class OptOutFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;
    @Mock
    private RegisteredUserRepository registeredUserRepository;

    private RegisteredUserStore userStore;
    private LoginLockoutService lockoutService;
    private OptOutFlowService optOutFlowService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        FakeRepositories.wireAsInMemoryStore(registeredUserRepository, RegisteredUser::getPhoneNumber);
        userStore = new RegisteredUserStore(registeredUserRepository);
        lockoutService = new LoginLockoutService(0);
        optOutFlowService = new OptOutFlowService(screenService, messageService, userStore, passwordEncoder, lockoutService);
    }

    @Test
    void noAccountFoundReturnsAHelpfulMessage() {
        UserSession session = new UserSession();

        optOutFlowService.handleOptOut(FROM, session);

        verify(screenService).sendCivilServantsMenu(FROM);
    }

    @Test
    void existingAccountMovesToConfirmationStep() {
        userStore.save(FROM, new RegisteredUser());
        UserSession session = new UserSession();

        optOutFlowService.handleOptOut(FROM, session);

        assertThat(session.getStep()).isEqualTo("opt_out_confirmation");
    }

    @Test
    void confirmingYesMovesToPinStep() {
        UserSession session = new UserSession();

        optOutFlowService.handleOptOutConfirmation(FROM, "yes", session);

        assertThat(session.getStep()).isEqualTo("opt_out_pin");
    }

    @Test
    void confirmingNoCancelsAndReturnsToCivilServantsMenu() {
        UserSession session = new UserSession();

        optOutFlowService.handleOptOutConfirmation(FROM, "no", session);

        verify(screenService).sendCivilServantsMenu(FROM);
    }

    @Test
    void ambiguousConfirmationTextRepromptsWithoutAdvancing() {
        UserSession session = new UserSession();

        optOutFlowService.handleOptOutConfirmation(FROM, "maybe", session);

        assertThat(session.getStep()).isNotEqualTo("opt_out_pin");
        verify(screenService, never()).sendCivilServantsMenu(anyString());
    }

    @Test
    void correctPinCompletesOptOut() {
        RegisteredUser existing = new RegisteredUser();
        existing.setFirstName("Jane");
        existing.setHashedPin(passwordEncoder.encode("11111"));
        existing.setStatus("active");
        userStore.save(FROM, existing);

        UserSession session = new UserSession();
        session.setAuthenticated(true);

        optOutFlowService.handleOptOutPin(FROM, "11111", session);

        // Data protection: the record is genuinely gone, not just
        // marked - findByPhoneNumber must come back completely empty,
        // indistinguishable from a number that's never registered.
        assertThat(userStore.findByPhoneNumber(FROM)).isEmpty();

        assertThat(session.isAuthenticated()).isFalse();
        // The session ends immediately - no screen is sent automatically.
        // This matches a brand new UserSession's own defaults exactly,
        // so a later "Hi" correctly triggers a genuinely fresh Welcome.
        assertThat(session.getStep()).isEqualTo("welcome");
        assertThat(session.isNewSession()).isTrue();
        verifyNoInteractions(screenService);
    }

    @Test
    void optedOutNumberIsIndistinguishableFromNeverRegistered() {
        RegisteredUser existing = new RegisteredUser();
        existing.setHashedPin(passwordEncoder.encode("11111"));
        existing.setStatus("active");
        userStore.save(FROM, existing);

        UserSession session = new UserSession();

        optOutFlowService.handleOptOutPin(FROM, "11111", session);

        // A later Welcome screen for this number must show the generic
        // greeting, never the old name - proving the data is genuinely
        // gone, not just hidden behind a status flag.
        assertThat(userStore.exists(FROM)).isFalse();
    }

    @Test
    void wrongPinCancelsOptOutWithoutChangingAccountStatus() {
        RegisteredUser existing = new RegisteredUser();
        existing.setHashedPin(passwordEncoder.encode("11111"));
        existing.setStatus("active");
        userStore.save(FROM, existing);

        UserSession session = new UserSession();

        optOutFlowService.handleOptOutPin(FROM, "00000", session);

        assertThat(userStore.findByPhoneNumber(FROM).get().getStatus()).isEqualTo("active"); // unchanged
        verify(screenService).sendCivilServantsMenu(FROM);
    }
}
