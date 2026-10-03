package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.OutboxEntry;
import com.mfstechnologies.mymobi.model.OutboxEntryType;
import com.mfstechnologies.mymobi.model.OutboxStatus;
import com.mfstechnologies.mymobi.session.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WORKSTREAM G (outbox pattern): OutboxRepository is mocked directly
 * here (not wired via FakeRepositories' generic in-memory helper) since
 * OutboxEntry's id is auto-generated rather than a natural key -
 * FakeRepositories' save() stub keys entries by whatever the entity's id
 * already is, which would be null for every entry here and collide
 * entries together. Instead, repository.save(any()) is stubbed to
 * simulate what a real database would do: assign a UUID if one isn't
 * already set, then return the same entity - enough to test
 * OutboxService's own logic without needing a real database.
 */
@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        lenient().when(repository.save(any())).thenAnswer(invocation -> {
            OutboxEntry entry = invocation.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(UUID.randomUUID());
            }
            return entry;
        });
        outboxService = new OutboxService(repository, objectMapper);
    }

    @Test
    void enqueueSerializesThePayloadAndAppliesTheTypesRetryPolicy() {
        var payload = new OutboxPayloads.SmsPayload("254700000001", "OTP 12345");

        OutboxEntry entry = outboxService.enqueue(OutboxEntryType.OTP_SMS, payload);

        assertThat(entry.getId()).isNotNull();
        assertThat(entry.getType()).isEqualTo(OutboxEntryType.OTP_SMS);
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getAttempts()).isZero();
        assertThat(entry.getMaxAttempts()).isEqualTo(OutboxRetryPolicy.forType(OutboxEntryType.OTP_SMS).maxAttempts());
        assertThat(entry.getNextAttemptAt()).isNotNull();
              assertThat(entry.getCreatedAt()).isNotNull();
        assertThat(entry.getPayload()).contains("254700000001").contains("OTP 12345");
    }

    @Test
    void enqueueUsesTheCorrectMaxAttemptsPerType() {
        var smsPayload = new OutboxPayloads.SmsPayload("254700000001", "OTP 12345");
        var stkPayload = new OutboxPayloads.StkPushPayload("254700000001", 15000.0, "loan_payment");

        OutboxEntry otpEntry = outboxService.enqueue(OutboxEntryType.OTP_SMS, smsPayload);
        OutboxEntry stkEntry = outboxService.enqueue(OutboxEntryType.MPESA_STK_PUSH, stkPayload);

        // Different types, different configured maxAttempts - confirms
        // the policy lookup is genuinely per-type, not a shared default.
        assertThat(otpEntry.getMaxAttempts()).isNotEqualTo(stkEntry.getMaxAttempts());
    }

    @Test
    void enqueueWithBusinessWriteRunsTheBusinessWriteAndEnqueuesTheEntry() {
        AtomicBoolean businessWriteRan = new AtomicBoolean(false);
        var payload = new OutboxPayloads.ApprovalCodeSmsPayload("254700000001", "Approval Code 654321", "REF123");

        OutboxEntry entry = outboxService.enqueueWithBusinessWrite(
                OutboxEntryType.APPROVAL_CODE_SMS,
                payload,
                () -> businessWriteRan.set(true)
        );

        assertThat(businessWriteRan.get()).isTrue();
        assertThat(entry.getType()).isEqualTo(OutboxEntryType.APPROVAL_CODE_SMS);
        assertThat(entry.getPayload()).contains("REF123");
    }

    @Test
    void enqueueWithBusinessWriteStillEnqueuesEvenIfCalledWithANoOpWrite() {
        // Sanity check that the method doesn't require a "real" write -
        // a no-op Runnable is a valid business write (e.g. nothing extra
        // to persist beyond the outbox entry itself).
        var payload = new OutboxPayloads.SmsPayload("254700000001", "OTP 12345");

        OutboxEntry entry = outboxService.enqueueWithBusinessWrite(OutboxEntryType.OTP_SMS, payload, () -> {});

        assertThat(entry.getId()).isNotNull();
    }

    @Test
    void findExhaustedDelegatesToTheRepository() {
        OutboxEntry exhausted = new OutboxEntry();
        exhausted.setId(UUID.randomUUID());
        exhausted.setStatus(OutboxStatus.EXHAUSTED);
        when(repository.findByStatus(OutboxStatus.EXHAUSTED)).thenReturn(List.of(exhausted));

        List<OutboxEntry> result = outboxService.findExhausted();

        assertThat(result).containsExactly(exhausted);
    }

    @Test
    void everyCurrentOutboxEntryTypeHasARetryPolicyConfigured() {
        // Regression guard: if a new OutboxEntryType constant is ever
        // added without also adding its policy to
        // OutboxRetryPolicy.POLICIES, enqueue() would throw
        // IllegalArgumentException the first time anyone tried to use
        // it. This catches that gap here, at test time, rather than the
        // first time it's hit in a running flow.
          for (OutboxEntryType type : OutboxEntryType.values()) {
            assertThat(OutboxRetryPolicy.forType(type)).isNotNull();
        }
    }
}
