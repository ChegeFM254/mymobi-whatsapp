package com.mfstechnologies.mymobi.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WORKSTREAM G (outbox pattern): only the two deterministic extremes of
 * the failure rate are tested - 0.0 never fails and 1.0 always fails,
 * which holds for any random draw since ThreadLocalRandom.nextDouble()
 * returns a value in [0.0, 1.0). An in-between rate would need either a
 * seedable random source or a statistical assertion, and neither is
 * worth the flakiness for a test-support class.
 */
class MockBackendFailureSimulatorTest {

    @Test
    void aRateOfZeroNeverFails() {
        MockBackendFailureSimulator simulator = new MockBackendFailureSimulator(0.0);

        assertThatCode(() -> {
            for (int i = 0; i < 1000; i++) {
                simulator.maybeFail("anything");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void aRateOfOneAlwaysFails() {
        MockBackendFailureSimulator simulator = new MockBackendFailureSimulator(1.0);

        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() -> simulator.maybeFail("anything"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void theFailureMessageNamesTheOperation() {
        MockBackendFailureSimulator simulator = new MockBackendFailureSimulator(1.0);

        assertThatThrownBy(() -> simulator.maybeFail("loan approval"))
                .hasMessageContaining("loan approval");
    }

    @Test
    void aRateOutsideZeroToOneIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new MockBackendFailureSimulator(-0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0.0 and 1.0");
        assertThatThrownBy(() -> new MockBackendFailureSimulator(1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0.0 and 1.0");
        // A likely typo: "50" meaning 50% must fail loudly, not quietly
        // behave as "always fail".
        assertThatThrownBy(() -> new MockBackendFailureSimulator(50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBoundaryRatesZeroAndOneAreAccepted() {
        assertThat(new MockBackendFailureSimulator(0.0)).isNotNull();
        assertThat(new MockBackendFailureSimulator(1.0)).isNotNull();
    }
}
