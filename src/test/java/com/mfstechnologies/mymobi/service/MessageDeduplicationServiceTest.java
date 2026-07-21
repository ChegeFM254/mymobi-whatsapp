package com.mfstechnologies.mymobi.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageDeduplicationServiceTest {

    @Test
    void firstTimeSeeingAMessageIdIsNotADuplicate() {
        MessageDeduplicationService service = new MessageDeduplicationService();

        assertThat(service.isDuplicate("wamid.ABC123")).isFalse();
    }

    @Test
    void secondTimeSeeingTheSameMessageIdIsADuplicate() {
        MessageDeduplicationService service = new MessageDeduplicationService();

        service.isDuplicate("wamid.ABC123"); // first delivery
        boolean secondDelivery = service.isDuplicate("wamid.ABC123"); // Meta retry

        assertThat(secondDelivery).isTrue();
    }

    @Test
    void differentMessageIdsAreNeverConsideredDuplicatesOfEachOther() {
        MessageDeduplicationService service = new MessageDeduplicationService();

        assertThat(service.isDuplicate("wamid.AAA")).isFalse();
        assertThat(service.isDuplicate("wamid.BBB")).isFalse();
    }

    @Test
    void nullMessageIdIsNeverTreatedAsADuplicate() {
        // Matches the Node version's behavior: can't dedupe without an
        // id, so let it through rather than blocking it.
        MessageDeduplicationService service = new MessageDeduplicationService();

        assertThat(service.isDuplicate(null)).isFalse();
        assertThat(service.isDuplicate(null)).isFalse();
    }
}
