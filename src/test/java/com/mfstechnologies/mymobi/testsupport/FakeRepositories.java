package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WORKSTREAM C (persistence): rewritten for the now Postgres-backed
 * RegisteredUserStore. Rather than exercising a real ConcurrentHashMap,
 * this mocks RegisteredUserRepository and verifies the store correctly
 * delegates to it - the same pattern used throughout this codebase for
 * every other service's dependencies. Also covers a genuinely new piece
 * of behavior: save() must set phoneNumber onto the entity itself before
 * persisting, since callers never had to do that with the old in-memory
 * version (phoneNumber was only ever the external Map key before).
 */
@ExtendWith(MockitoExtension.class)
class RegisteredUserStoreTest {

    private static final String FROM = "254700000001";

    @Mock
    private RegisteredUserRepository repository;

    private RegisteredUserStore store;

    @BeforeEach
    void setUp() {
        store = new RegisteredUserStore(repository);
    }

    private RegisteredUser sampleUser() {
        RegisteredUser user = new RegisteredUser();
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setUpn("12345");
        return user;
    }

    @Test
    void findByPhoneNumberDelegatesToFindById() {
        RegisteredUser user = sampleUser();
        when(repository.findById(FROM)).thenReturn(Optional.of(user));

        assertThat(store.findByPhoneNumber(FROM)).contains(user);
    }

    @Test
    void findByPhoneNumberReturnsEmptyWhenRepositoryHasNothing() {
        when(repository.findById(FROM)).thenReturn(Optional.empty());

        assertThat(store.findByPhoneNumber(FROM)).isEmpty();
    }

    @Test
    void saveSetsThePhoneNumberOntoTheEntityBeforePersisting() {
        RegisteredUser user = sampleUser();
        assertThat(user.getPhoneNumber()).isNull(); // not set yet

        store.save(FROM, user);

        assertThat(user.getPhoneNumber()).isEqualTo(FROM); // now set, by the store itself
        verify(repository).save(user);
    }

    @Test
    void existsDelegatesToExistsById() {
        when(repository.existsById(FROM)).thenReturn(true);

        assertThat(store.exists(FROM)).isTrue();
        verify(repository).existsById(FROM);
    }

    @Test
    void deleteDelegatesToDeleteById() {
        store.delete(FROM);

        verify(repository).deleteById(FROM);
    }
}
