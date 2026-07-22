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
class OptOutFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;

    private RegisteredUserStore userStore;
    private LoginLockoutService lockoutService;
    private OptOutFlowService optOutFlowService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userStore = new RegisteredUserStore();
        lockoutService = new LoginLockoutService(0);
        optOutFlowService = new OptOutFlowService(screenService, messageService, userStore, passwordEncoder, lockoutService);
    }

    @Test
    void noAccountFoundReturnsAHelpfulMessage() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendCivilServantsMenu(FROM)).thenReturn(Mono.empty());

        optOutFlowService.handleOptOut(FROM, session).block();

        verify(screenService).sendCivilServantsMenu(FROM);
    }

    @Test
    void existingAccountMovesToConfirmationStep() {
        userStore.save(FROM, new RegisteredUser());
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        optOutFlowService.handleOptOut(FROM, session).block();

        assertThat(session.getStep()).isEqualTo("opt_out_confirmation");
    }

    @Test
    void confirmingYesMovesToPinStep() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        optOutFlowService.handleOptOutConfirmation(FROM, "yes", session).block();

        assertThat(session.getStep()).isEqualTo("opt_out_pin");
    }

    @Test
    void confirmingNoCancelsAndReturnsToCivilServantsMenu() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendCivilServantsMenu(FROM)).thenReturn(Mono.empty());

        optOutFlowService.handleOptOutConfirmation(FROM, "no", session).block();

        verify(screenService).sendCivilServantsMenu(FROM);
    }

    @Test
    void ambiguousConfirmationTextRepromptsWithoutAdvancing() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        optOutFlowService.handleOptOutConfirmation(FROM, "maybe", session).block();

        assertThat(session.getStep()).isNotEqualTo("opt_out_pin");
        verify(screenService, never()).sendCivilServantsMenu(anyString());
    }

    @Test
    void correctPinCompletesOptOut() {
        RegisteredUser existing = new RegisteredUser();
        existing.setHashedPin(passwordEncoder.encode("11111"));
        existing.setStatus("active");
        userStore.save(FROM, existing);

        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        optOutFlowService.handleOptOutPin(FROM, "11111", session).block();

        assertThat(userStore.findByPhoneNumber(FROM).get().getStatus()).isEqualTo("opted_out");
        assertThat(session.isAuthenticated()).isFalse();
        verify(screenService).sendWelcome(FROM);
    }

    @Test
    void wrongPinCancelsOptOutWithoutChangingAccountStatus() {
        RegisteredUser existing = new RegisteredUser();
        existing.setHashedPin(passwordEncoder.encode("11111"));
        existing.setStatus("active");
        userStore.save(FROM, existing);

        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendCivilServantsMenu(FROM)).thenReturn(Mono.empty());

        optOutFlowService.handleOptOutPin(FROM, "00000", session).block();

        assertThat(userStore.findByPhoneNumber(FROM).get().getStatus()).isEqualTo("active");
        verify(screenService).sendCivilServantsMenu(FROM);
    }
}
