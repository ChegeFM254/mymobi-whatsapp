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
 * attempts delivery - SMS notifications via SmsService, and backend
 * requests via LoanService / CustomerService.
 *
 * dispatchPendingEntries() runs on a fixed schedule (every 15s) and is
 * deliberately NOT @Transactional itself - it just fetches the list
 * (oldest first) and hands each entry to processEntry() individually,
 * so one entry's failure (or even an exception fetching its payload)
 * can never roll back another entry's successful processing.
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
 * Backend requests are called with the entry's own id as their
 * requestId. That id is stable across every retry of the same entry, so
 * a real backend can use it to recognise a retry and not apply the same
 * request twice.
 *
 * An entry whose type has no handling below fails loudly (and so is
 * retried, then exhausted, and shows up in the exhausted-entries
 * endpoint) rather than silently counting as delivered - a switch
 * statement with no matching case would otherwise do nothing and mark
 * the entry COMPLETED. OutboxDispatcherTest guards this by dispatching
 * every OutboxEntryType.
 *
 * Note on delays: MockSmsService has its own ~5s simulated delay, which
 * stacks with this dispatcher's poll interval - redundant but harmless.
 * MockMpesaService has no such delay, and STK pushes aren't routed
 * through the outbox by any flow at present.
 */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository repository;
    private final SmsService smsService;
    private final MpesaService mpesaService;
    private final LoanService loanService;
    private final CustomerService customerService;
    private final ObjectMapper objectMapper;
    
    public OutboxDispatcher(
            OutboxRepository repository,
            SmsService smsService,
            MpesaService mpesaService,
            LoanService loanService,
            CustomerService customerService,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.smsService = smsService;
        this.mpesaService = mpesaService;
        this.loanService = loanService;
        this.customerService = customerService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 15000)
    public void dispatchPendingEntries() {
        List<OutboxEntry> ready = repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(OutboxStatus.PENDING, Instant.now());
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
                var payload = read(entry, OutboxPayloads.SmsPayload.class);
                smsService.sendSms(payload.phoneNumber(), payload.message());
            }
            case APPROVAL_CODE_SMS -> {
                var payload = read(entry, OutboxPayloads.ApprovalCodeSmsPayload.class);
                smsService.sendSms(payload.phoneNumber(), payload.message());
            }
            case MPESA_STK_PUSH -> {
                var payload = read(entry, OutboxPayloads.StkPushPayload.class);
                mpesaService.initiateStkPush(payload.phoneNumber(), payload.amount(), payload.purpose());
            }
            case LOAN_APPLICATION_SUBMIT ->
                    loanService.submitApplication(entry.getId(), read(entry, LoanService.ApplicationRequest.class));
            case LOAN_APPROVAL ->
                    loanService.approveLoan(entry.getId(), read(entry, LoanService.ApprovalRequest.class));
            case LOAN_REPAYMENT_RECORD ->
                    loanService.recordRepayment(entry.getId(), read(entry, LoanService.RepaymentRequest.class));
            case CUSTOMER_CREATE ->
                    customerService.createCustomer(entry.getId(), read(entry, CustomerService.CreateRequest.class));
            case CUSTOMER_PIN_RESET ->
                    customerService.resetPin(entry.getId(), read(entry, CustomerService.PinResetRequest.class));
            default -> throw new IllegalStateException("No dispatcher handling for outbox type: " + entry.getType());
        }
    }

    private <T> T read(OutboxEntry entry, Class<T> payloadType) {
        return objectMapper.readValue(entry.getPayload(), payloadType);
    }
}
