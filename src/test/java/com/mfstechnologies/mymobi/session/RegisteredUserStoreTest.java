package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegisteredUserStoreTest {

    private RegisteredUser sampleUser() {
        RegisteredUser user = new RegisteredUser();
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setUpn("12345");
        return user;
    }

    @Test
    void freshlySavedUserIsFindable() {
        RegisteredUserStore store = new RegisteredUserStore();
        store.save("254700000001", sampleUser());

        assertThat(store.findByPhoneNumber("254700000001")).isPresent();
        assertThat(store.findByPhoneNumber("254700000001").get().getUpn()).isEqualTo("12345");
    }

    @Test
    void unknownPhoneNumberIsNotFound() {
        RegisteredUserStore store = new RegisteredUserStore();

        assertThat(store.findByPhoneNumber("254700000099")).isEmpty();
    }

    @Test
    void existsReflectsWhetherAUserHasBeenSaved() {
        RegisteredUserStore store = new RegisteredUserStore();

        assertThat(store.exists("254700000001")).isFalse();

        store.save("254700000001", sampleUser());

        assertThat(store.exists("254700000001")).isTrue();
    }

    @Test
    void savingAgainForTheSamePhoneNumberReplacesThePreviousRecord() {
        RegisteredUserStore store = new RegisteredUserStore();
        store.save("254700000001", sampleUser());

        RegisteredUser updated = sampleUser();
        updated.setUpn("99999");
        store.save("254700000001", updated);

        assertThat(store.findByPhoneNumber("254700000001").get().getUpn()).isEqualTo("99999");
    }

    @Test
    void usersArePerPhoneNumber() {
        RegisteredUserStore store = new RegisteredUserStore();
        store.save("254700000001", sampleUser());

        assertThat(store.findByPhoneNumber("254700000002")).isEmpty();
        assertThat(store.exists("254700000002")).isFalse();
    }
}
