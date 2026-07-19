package com.mfstechnologies.mymobi.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStoreTest {

    @Test
    void getOrCreateReturnsTheSameInstanceOnRepeatedCalls() {
        SessionStore store = new SessionStore();

        var first = store.getOrCreate("254700000001");
        var second = store.getOrCreate("254700000001");

        assertThat(second).isSameAs(first);
    }

    @Test
    void differentPhoneNumbersGetDifferentSessions() {
        SessionStore store = new SessionStore();

        var sessionA = store.getOrCreate("254700000001");
        var sessionB = store.getOrCreate("254700000002");

        assertThat(sessionA).isNotSameAs(sessionB);
    }

    @Test
    void deleteRemovesTheSession() {
        SessionStore store = new SessionStore();
        store.getOrCreate("254700000001");

        assertThat(store.exists("254700000001")).isTrue();

        store.delete("254700000001");

        assertThat(store.exists("254700000001")).isFalse();
    }

    @Test
    void newSessionDefaultsMatchTheNodeVersion() {
        SessionStore store = new SessionStore();
        var session = store.getOrCreate("254700000001");

        assertThat(session.getStep()).isEqualTo("welcome");
        assertThat(session.isNewSession()).isTrue();
        assertThat(session.isAuthenticated()).isFalse();
    }
}