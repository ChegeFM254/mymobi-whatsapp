package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WORKSTREAM C (persistence): rewritten for the now Redis-backed
 * SessionStore. Uses a real ObjectMapper (genuinely exercising JSON
 * serialization, not just mocking it away) alongside a mocked
 * StringRedisTemplate wired to a real backing HashMap, so these tests
 * prove the actual round-trip works, not just that methods get called.
 *
 * getOrCreateReturnsTheSameInstanceOnRepeatedCalls (the old in-memory
 * version's test) is deliberately GONE, not just renamed - it encoded a
 * guarantee that's no longer true, and would legitimately fail against
 * this implementation: getOrCreate() now deserializes a fresh object
 * from Redis on every call, rather than returning the same shared Java
 * reference. This is a genuine, intentional behavioral change, and
 * getOrCreateRoundTripsThroughRedis below tests the NEW, correct
 * contract instead: two objects with the same field values, not
 * necessarily the same reference.
 */
@ExtendWith(MockitoExtension.class)
class SessionStoreTest {

    private static final String FROM = "254700000001";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> backing = new HashMap<>();

    private SessionStore store;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        lenient().when(valueOperations.get(anyString())).thenAnswer(invocation ->
                backing.get((String) invocation.getArgument(0)));

        lenient().doAnswer(invocation -> {
            backing.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString());

        lenient().when(redisTemplate.delete(anyString())).thenAnswer(invocation ->
                backing.remove((String) invocation.getArgument(0)) != null);

        lenient().when(redisTemplate.hasKey(anyString())).thenAnswer(invocation ->
                backing.containsKey((String) invocation.getArgument(0)));
                store = new SessionStore(redisTemplate, objectMapper);
    }

    @Test
    void getOrCreateReturnsAFreshSessionWhenNothingIsStored() {
        UserSession session = store.getOrCreate(FROM);

        assertThat(session.getStep()).isEqualTo("welcome");
        assertThat(session.isNewSession()).isTrue();
        assertThat(session.isAuthenticated()).isFalse();
    }

    @Test
    void getOrCreateRoundTripsThroughRedis() {
        UserSession original = store.getOrCreate(FROM);
        original.setStep("enter_otp");
        original.setFirstName("Jane");
        original.setLoanAmount(15000);

        store.save(FROM, original);
        UserSession reloaded = store.getOrCreate(FROM);

        assertThat(reloaded).isNotSameAs(original); // genuinely a different object now, by design
        assertThat(reloaded.getStep()).isEqualTo("enter_otp");
        assertThat(reloaded.getFirstName()).isEqualTo("Jane");
        assertThat(reloaded.getLoanAmount()).isEqualTo(15000);
    }

    @Test
    void differentPhoneNumbersGetIndependentSessions() {
        UserSession sessionA = store.getOrCreate("254700000001");
        sessionA.setStep("enter_otp");
        store.save("254700000001", sessionA);

        UserSession sessionB = store.getOrCreate("254700000002");

        assertThat(sessionB.getStep()).isEqualTo("welcome"); // unaffected by sessionA
    }

    @Test
    void deleteRemovesTheSession() {
        UserSession session = store.getOrCreate(FROM);
        store.save(FROM, session);
        assertThat(store.exists(FROM)).isTrue();

        store.delete(FROM);

        assertThat(store.exists(FROM)).isFalse();
    }

    @Test
    void newSessionDefaultsMatchTheNodeVersion() {
        UserSession session = store.getOrCreate(FROM);

        assertThat(session.getStep()).isEqualTo("welcome");
        assertThat(session.isNewSession()).isTrue();
        assertThat(session.isAuthenticated()).isFalse();
    }

    @Test
    void corruptedStoredJsonFallsBackToAFreshSessionRatherThanCrashing() {
        backing.put("session:" + FROM, "{not valid json");

        UserSession session = store.getOrCreate(FROM);

        assertThat(session.getStep()).isEqualTo("welcome");
        assertThat(session.isNewSession()).isTrue();
    }
}
