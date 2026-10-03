package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.OutboxEntry;
import com.mfstechnologies.mymobi.model.OutboxStatus;
import com.mfstechnologies.mymobi.session.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WORKSTREAM G (outbox pattern): polls for due outbox entries and
 * attempts delivery against the relevant mock service.
 *
 * dispatchPendingEntries() runs on a fixed schedule (every 15s) and is
 * deliberately NOT @Transactional itself - it just fetches the list and
 * hands each entry to processEntry() individually, so one entry's
 * failure (or even an exception fetching its payload) can never roll
 * back another entry's successful processing.
 *
 * processEntry() IS @Transactional, and re-fetches the entry by id
 * inside that transaction (rather than trusting the instance passed in
 * from the outer query) - this gets a properly managed entity and
 * re-checks status == PENDING, so if this were ever running across
 * multiple instances, a second instance that already claimed this entry
 * would be safely skipped rather than double-processed. Low-cost
 * defensive measure; this app currently runs as a single instance
 * (WEB_CONCURRENCY=1), so it's not solving an active problem today.
 *
 * SCOPE NOTE: deliberately calls MockSmsService / MockMpesaService
 * as-is, including their own internal ~5s simulated delay - this stacks
 * with the dispatcher's own poll interval, which is redundant but safe.
 * A natural follow-up (not done in this pass) would be to drop that
 * internal delay now that the dispatcher's poll interval provides the
 * "arrives a bit later" feel on its own - held off because
 * MpesaService/SmsService are still called directly (not via the
 * outbox) by flows not yet migrated to this pattern (e.g. Payslip/Loan
 * Document STK pushes), and removing the delay there would be an
 * unscoped behavior change to those call sites.
 */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository repository;
    private final SmsService smsService;
    private final MpesaService mpesaService;
    private final ObjectMapper objectMapper;

    public OutboxDispatcher(
            OutboxRepository repository,
            SmsService smsService,
            MpesaService mpesaService,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.smsService = smsService;
        this.mpesaService = mpesaService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 15000)
    public void dispatchPendingEntries() {
        List<OutboxEntry> ready = repository.findByStatusAndNextAttemptAtLessThanEqual(OutboxStatus.PENDING, Instant.now());
        for (OutboxEntry entry : ready) {
            try {
                processEntry(entry.getId());
            } catch (Exception err) {
                // processEntry's own try/catch handles delivery failures;
                // this outer catch is just a last-resort guard so one
                // genuinely unexpected error (e.g. a DB hiccup) can't
                // kill the whole poll cycle for every other entry.
                log.error("Unexpected error processing outbox entry {}: {}", entry.getId(), err.getMessage());
            }
        }
    }

    @Transactional
    public void processEntry(UUID entryId) {
        Optional<OutboxEntry> current = repository.findById(entryId);
        if (current.isEmpty()) {
            return;
        }
        OutboxEntry entry = current.get();
        if (entry.getStatus() != OutboxStatus.PENDING) {
            return; // already claimed or processed - see class-level note on multi-instance safety
        }

        entry.setStatus(OutboxStatus.PROCESSING);
        entry.setAttempts(entry.getAttempts() + 1);

        try {
            dispatch(entry);
            entry.setStatus(OutboxStatus.COMPLETED);
            entry.setCompletedAt(Instant.now());
        } catch (Exception err) {
            entry.setLastError(err.getMessage());
            if (entry.getAttempts() >= entry.getMaxAttempts()) {
                entry.setStatus(OutboxStatus.EXHAUSTED);
                log.error("outbox_entry_exhausted id={} type={} attempts={}", entry.getId(), entry.getType(), entry.getAttempts());
            } else {
                entry.setStatus(OutboxStatus.PENDING);
                OutboxRetryPolicy policy = OutboxRetryPolicy.forType(entry.getType());
                entry.setNextAttemptAt(Instant.now().plus(policy.delayForAttempt(entry.getAttempts())));
            }
        }

        repository.save(entry);
    }

    private void dispatch(OutboxEntry entry) {
        switch (entry.getType()) {
            case OTP_SMS -> {
                var payload = objectMapper.readValue(entry.getPayload(), OutboxPayloads.SmsPayload.class);
                smsService.sendSms(payload.phoneNumber(), payload.message());
            }
            case APPROVAL_CODE_SMS -> {
                var payload = objectMapper.readValue(entry.getPayload(), OutboxPayloads.ApprovalCodeSmsPayload.class);
                smsService.sendSms(payload.phoneNumber(), payload.message());
            }
            case MPESA_STK_PUSH -> {
                var payload = objectMapper.readValue(entry.getPayload(), OutboxPayloads.StkPushPayload.class);
                mpesaService.initiateStkPush(payload.phoneNumber(), payload.amount(), payload.purpose());
            }
        }
    }
}
