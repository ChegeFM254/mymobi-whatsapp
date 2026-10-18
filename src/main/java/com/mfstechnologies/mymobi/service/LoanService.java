package com.mfstechnologies.mymobi.service;

import java.util.UUID;

/**
 * WORKSTREAM G (outbox pattern): a clean seam for the backend loan
 * service - the system that will eventually own loan applications,
 * approvals/disbursement, and repayments. Today this app keeps its own
 * copy of loan state in Postgres and propagates each change here via
 * the outbox, so a lost or crashed request can be retried rather than
 * silently dropped. A real implementation (HTTP client etc.) replaces
 * MockLoanService; nothing that calls this interface changes.
 *
 * Every method takes a requestId - the id of the OutboxEntry driving the
 * call. It stays the same across retries of the same request, so a real
 * backend can recognise a retry and avoid applying it twice (which
 * matters most for approval/disbursement and repayments, where a
 * duplicate moves money). It's passed as a separate argument rather than
 * stored inside the request records because the entry's id only exists
 * once it has been saved, after its payload was already serialized.
 *
 * The request records are also what gets serialized into the outbox
 * entry's payload, so changing their shape affects entries already
 * waiting in the outbox.
 */
public interface LoanService {

    record ApplicationRequest(
            String phoneNumber,
            String refNo,
            int loanAmount,
            int tenureMonths,
            String payrollNumber,
            String dueDate
    ) {
    }

    record ApprovalRequest(
            String phoneNumber,
            String refNo,
            int disbursementAmount
    ) {
    }

    record RepaymentRequest(
            String phoneNumber,
            String refNo,
            int installmentsInThisPayment,
            int amount
    ) {
    }

    void submitApplication(UUID requestId, ApplicationRequest request);

    void approveLoan(UUID requestId, ApprovalRequest request);

    void recordRepayment(UUID requestId, RepaymentRequest request);
}
