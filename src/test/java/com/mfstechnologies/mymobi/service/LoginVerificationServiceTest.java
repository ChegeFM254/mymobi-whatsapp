package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.session.RegisteredUserRepository;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import com.mfstechnologies.mymobi.testsupport.FakeRepositories;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WORKSTREAM C (persistence): RegisteredUserStore is now Postgres-backed
 * - freshUserStore() below creates a fresh mock repository per call,
 * wired to a fake, in-memory-backed implementation (see
 * FakeRepositories), so each test still gets its own working,
 * independent collaborator, exactly as `new RegisteredUserStore()` used
 * to provide directly. This file uses plain Mockito.mock(...) rather
 * than @Mock/@ExtendWith(MockitoExtension.class), since it doesn't use
 * Mockito anywhere else and a one-line static mock() call is simpler
 * than adding the extension just for this.
 */
class LoginVerificationServiceTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private RegisteredUserStore freshUserStore() {
        RegisteredUserRepository repository = mock(RegisteredUserRepository.class);
        FakeRepositories.wireAsInMemoryStore(repository, RegisteredUser::getPhoneNumber);
        return new RegisteredUserStore(repository);
    }

    @Test
    void noExistingRecordWithBypassEnabledSucceedsAndCreatesASyntheticAccount() {
        RegisteredUserStore userStore = freshUserStore();
        LoginVerificationService service = new LoginVerificationService(userStore, passwordEncoder, true);

        LoginVerificationService.LoginResult result = service.verify("254700000010", "12345", "54321");

        assertThat(result.success()).isTrue();
        assertThat(result.user().getUpn()).isEqualTo("12345");
        assertThat(result.user().isTestingBypassAccount()).isTrue();
        assertThat(userStore.findByPhoneNumber("254700000010")).isPresent(); // persisted for downstream use
    }

    @Test
    void noExistingRecordWithBypassDisabledFails() {
        RegisteredUserStore userStore = freshUserStore();
        LoginVerificationService service = new LoginVerificationService(userStore, passwordEncoder, false);

        LoginVerificationService.LoginResult result = service.verify("254700000011", "12345", "54321");

        assertThat(result.success()).isFalse();
        assertThat(userStore.findByPhoneNumber("254700000011")).isEmpty();
    }

    @Test
    void existingRecordWithCorrectUpnAndPinSucceedsUsingRealData() {
        RegisteredUserStore userStore = freshUserStore();
        RegisteredUser realUser = new RegisteredUser();
        realUser.setFirstName("Real");
        realUser.setLastName("User");
        realUser.setUpn("19999999");
        realUser.setHashedPin(passwordEncoder.encode("11111"));
        userStore.save("254700000012", realUser);

        LoginVerificationService service = new LoginVerificationService(userStore, passwordEncoder, true);
        LoginVerificationService.LoginResult result = service.verify("254700000012", "19999999", "11111");

        assertThat(result.success()).isTrue();
        assertThat(result.user().getFirstName()).isEqualTo("Real");
        assertThat(result.user().isTestingBypassAccount()).isFalse();
    }

    @Test
    void existingRecordWithWrongPinFails() {
        RegisteredUserStore userStore = freshUserStore();
        RegisteredUser realUser = new RegisteredUser();
        realUser.setUpn("19999999");
        realUser.setHashedPin(passwordEncoder.encode("11111"));
        userStore.save("254700000013", realUser);

        LoginVerificationService service = new LoginVerificationService(userStore, passwordEncoder, true);
        LoginVerificationService.LoginResult result = service.verify("254700000013", "19999999", "99999");

        assertThat(result.success()).isFalse();
    }

    @Test
    void existingRecordWithWrongUpnFails() {
        RegisteredUserStore userStore = freshUserStore();
        RegisteredUser realUser = new RegisteredUser();
        realUser.setUpn("19999999");
        realUser.setHashedPin(passwordEncoder.encode("11111"));
        userStore.save("254700000014", realUser);

        LoginVerificationService service = new LoginVerificationService(userStore, passwordEncoder, true);
        LoginVerificationService.LoginResult result = service.verify("254700000014", "10000000", "11111");

        assertThat(result.success()).isFalse();
    }

    @Test
    void bypassNeverOverridesAnExistingMismatchEvenWhenEnabled() {
        // Once a real record exists, the bypass must NOT kick in just
        // because the entered credentials were wrong - it only applies
        // when there's no record at all.
        RegisteredUserStore userStore = freshUserStore();
        RegisteredUser realUser = new RegisteredUser();
        realUser.setUpn("19999999");
        realUser.setHashedPin(passwordEncoder.encode("11111"));
        userStore.save("254700000015", realUser);

        LoginVerificationService service = new LoginVerificationService(userStore, passwordEncoder, true);
        LoginVerificationService.LoginResult result = service.verify("254700000015", "19999999", "00000");

        assertThat(result.success()).isFalse();
        assertThat(result.user()).isNull();
    }
}
