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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WORKSTREAM G (outbox pattern): covers processEntry()'s three outcomes
 * (success, retry, exhaustion) and dispatchPendingEntries()'s role as a
 * thin loop over whatever the repository says is due. repository.save()
 * is verified for the resulting entry state in each case, since that's
 * the actual persisted outcome - OutboxRepository is mocked directly
 * (not via FakeRepositories) for the same auto-generated-id reason as
 * OutboxServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class OutboxDispatcherTest {

    private static final String PHONE = "254700000001";

    @Mock
    private OutboxRepository repository;
    @Mock
    private SmsService smsService;
    @Mock
    private MpesaService mpesaService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new OutboxDispatcher(repository, smsService, mpesaService, objectMapper);
    }

    private OutboxEntry pendingEntry(OutboxEntryType type, Object payload, int attempts, int maxAttempts) {
        OutboxEntry entry = new OutboxEntry();
        entry.setId(UUID.randomUUID());
        entry.setType(type);
        entry.setPayload(objectMapper.writeValueAsString(payload));
        entry.setStatus(OutboxStatus.PENDING);
        entry.setAttempts(attempts);
        entry.setMaxAttempts(maxAttempts);
        entry.setNextAttemptAt(Instant.now());
        entry.setCreatedAt(Instant.now());
        return entry;
    }

    // ==================== processEntry: success ====================

    @Test
    void successfulOtpSmsDispatchMarksTheEntryCompleted() {
        var payload = new OutboxPayloads.SmsPayload(PHONE, "OTP 12345");
        OutboxEntry entry = pendingEntry(OutboxEntryType.OTP_SMS, payload, 0, 3);
        when(repository.findById(entry.getId())).thenReturn(Optional.of(entry));

        dispatcher.processEntry(entry.getId());

        verify(smsService).sendSms(PHONE, "OTP 12345");
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.COMPLETED);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getCompletedAt()).isNotNull();
        verify(repository).save(entry);
    }

    @Test
    void successfulStkPushDispatchCallsMpesaServiceWithTheCorrectArguments() {
        var payload = new OutboxPayloads.StkPushPayload(PHONE, 15000.0, "loan_payment");
        OutboxEntry entry = pendingEntry(OutboxEntryType.MPESA_STK_PUSH, payload, 0, 5);
        when(repository.findById(entry.getId())).thenReturn(Optional.of(entry));

        dispatcher.processEntry(entry.getId());

        verify(mpesaService).initiateStkPush(PHONE, 15000.0, "loan_payment");
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.COMPLETED);
    }

    @Test
    void approvalCodeSmsDispatchSendsViaSmsServiceUsingTheMessageField() {
        var payload = new OutboxPayloads.ApprovalCodeSmsPayload(PHONE, "Approval Code 654321", "REF123");
        OutboxEntry entry = pendingEntry(OutboxEntryType.APPROVAL_CODE_SMS, payload, 0, 5);
        when(repository.findById(entry.getId())).thenReturn(Optional.of(entry));

        dispatcher.processEntry(entry.getId());

        verify(smsService).sendSms(PHONE, "Approval Code 654321");
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.COMPLETED);
    }

    // ==================== processEntry: failure with attempts remaining ====================

    @Test
    void failedDispatchWithAttemptsRemainingGoesBackToPendingWithAFutureNextAttempt() {
        var payload = new OutboxPayloads.SmsPayload(PHONE, "OTP 12345");
        OutboxEntry entry = pendingEntry(OutboxEntryType.OTP_SMS, payload, 0, 3); // maxAttempts=3, about to become attempts=1
        when(repository.findById(entry.getId())).thenReturn(Optional.of(entry));
        doThrow(new RuntimeException("gateway timeout")).when(smsService).sendSms(anyString(), anyString());

        Instant before = Instant.now();
        dispatcher.processEntry(entry.getId());

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getLastError()).contains("gateway timeout");
        assertThat(entry.getNextAttemptAt()).isAfter(before);
        verify(repository).save(entry);
    }

    @Test
    void laterAttemptsBackOffFurtherThanEarlierOnes() {
        var payload = new OutboxPayloads.SmsPayload(PHONE, "OTP 12345");
        doThrow(new RuntimeException("down")).when(smsService).sendSms(anyString(), anyString());

        OutboxEntry firstFailure = pendingEntry(OutboxEntryType.OTP_SMS, payload, 0, 5);
              when(repository.findById(firstFailure.getId())).thenReturn(Optional.of(firstFailure));
        Instant beforeFirst = Instant.now();
        dispatcher.processEntry(firstFailure.getId());
        long firstDelaySeconds = firstFailure.getNextAttemptAt().getEpochSecond() - beforeFirst.getEpochSecond();

        OutboxEntry thirdFailure = pendingEntry(OutboxEntryType.OTP_SMS, payload, 2, 5); // already failed twice
        when(repository.findById(thirdFailure.getId())).thenReturn(Optional.of(thirdFailure));
        Instant beforeThird = Instant.now();
        dispatcher.processEntry(thirdFailure.getId());
        long thirdDelaySeconds = thirdFailure.getNextAttemptAt().getEpochSecond() - beforeThird.getEpochSecond();

        assertThat(thirdDelaySeconds).isGreaterThan(firstDelaySeconds);
    }

    // ==================== processEntry: exhaustion ====================

    @Test
    void failedDispatchOnTheLastAllowedAttemptMarksTheEntryExhausted() {
        var payload = new OutboxPayloads.SmsPayload(PHONE, "OTP 12345");
        OutboxEntry entry = pendingEntry(OutboxEntryType.OTP_SMS, payload, 2, 3); // already failed twice, this is attempt 3 of 3
        when(repository.findById(entry.getId())).thenReturn(Optional.of(entry));
        doThrow(new RuntimeException("still down")).when(smsService).sendSms(anyString(), anyString());

        dispatcher.processEntry(entry.getId());

        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.EXHAUSTED);
        assertThat(entry.getAttempts()).isEqualTo(3);
        assertThat(entry.getLastError()).contains("still down");
    }

    // ==================== processEntry: already claimed/processed ====================

    @Test
    void processEntrySkipsAnEntryThatIsNoLongerPending() {
        var payload = new OutboxPayloads.SmsPayload(PHONE, "OTP 12345");
        OutboxEntry entry = pendingEntry(OutboxEntryType.OTP_SMS, payload, 0, 3);
        entry.setStatus(OutboxStatus.COMPLETED); // already handled
        when(repository.findById(entry.getId())).thenReturn(Optional.of(entry));

        dispatcher.processEntry(entry.getId());

        verifyNoInteractions(smsService);
        verifyNoInteractions(mpesaService);
        verify(repository, never()).save(any());
    }

    @Test
    void processEntrySilentlyNoOpsWhenTheEntryNoLongerExists() {
        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        dispatcher.processEntry(missingId);

        verifyNoInteractions(smsService);
        verifyNoInteractions(mpesaService);
    }

    // ==================== dispatchPendingEntries: the poll loop ====================

    @Test
    void dispatchPendingEntriesProcessesEveryEntryTheRepositoryReportsAsDue() {
        var payload1 = new OutboxPayloads.SmsPayload(PHONE, "OTP 11111");
        var payload2 = new OutboxPayloads.SmsPayload("254700000002", "OTP 22222");
        OutboxEntry entry1 = pendingEntry(OutboxEntryType.OTP_SMS, payload1, 0, 3);
        OutboxEntry entry2 = pendingEntry(OutboxEntryType.OTP_SMS, payload2, 0, 3);
      
        when(repository.findByStatusAndNextAttemptAtLessThanEqual(eq(OutboxStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(entry1, entry2));
        when(repository.findById(entry1.getId())).thenReturn(Optional.of(entry1));
        when(repository.findById(entry2.getId())).thenReturn(Optional.of(entry2));

        dispatcher.dispatchPendingEntries();

        verify(smsService).sendSms(PHONE, "OTP 11111");
        verify(smsService).sendSms("254700000002", "OTP 22222");
    }

    @Test
    void dispatchPendingEntriesContinuesToOtherEntriesWhenOneThrowsUnexpectedly() {
        var payload1 = new OutboxPayloads.SmsPayload(PHONE, "OTP 11111");
        var payload2 = new OutboxPayloads.SmsPayload("254700000002", "OTP 22222");
        OutboxEntry entry1 = pendingEntry(OutboxEntryType.OTP_SMS, payload1, 0, 3);
        OutboxEntry entry2 = pendingEntry(OutboxEntryType.OTP_SMS, payload2, 0, 3);

        when(repository.findByStatusAndNextAttemptAtLessThanEqual(eq(OutboxStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(entry1, entry2));
        // entry1's own findById blows up with something genuinely
        // unexpected (not a delivery failure, which processEntry already
        // handles internally) - dispatchPendingEntries' outer try/catch
        // should still let entry2 get processed.
        when(repository.findById(entry1.getId())).thenThrow(new RuntimeException("db hiccup"));
        when(repository.findById(entry2.getId())).thenReturn(Optional.of(entry2));

        dispatcher.dispatchPendingEntries();

        verify(smsService).sendSms("254700000002", "OTP 22222");
    }

    @Test
    void dispatchPendingEntriesDoesNothingWhenNoneAreDue() {
        when(repository.findByStatusAndNextAttemptAtLessThanEqual(eq(OutboxStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of());

        dispatcher.dispatchPendingEntries();

        verifyNoInteractions(smsService);
        verifyNoInteractions(mpesaService);
    }
}
