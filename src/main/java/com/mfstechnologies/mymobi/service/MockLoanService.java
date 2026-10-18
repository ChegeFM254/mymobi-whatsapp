package com.mfstechnologies.mymobi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * WORKSTREAM G (outbox pattern): stands in for the real backend loan
 * service. Logs each request and succeeds (or fails, at the rate
 * configured on MockBackendFailureSimulator). Logs identify the request
 * and loan but never anything more sensitive than a reference number
 * and amounts.
 *
 * TODO: replace with a real implementation once the loan service's
 * contract is available.
 */
@Service
public class MockLoanService implements LoanService {

    private static final Logger log = LoggerFactory.getLogger(MockLoanService.class);

    private final MockBackendFailureSimulator failureSimulator;

    public MockLoanService(MockBackendFailureSimulator failureSimulator) {
        this.failureSimulator = failureSimulator;
    }

    @Override
    public void submitApplication(UUID requestId, ApplicationRequest request) {
        failureSimulator.maybeFail("loan application submit");
        log.info("mock_loan_application_submitted requestId={} refNo={} amount={} tenureMonths={}",
                requestId, request.refNo(), request.loanAmount(), request.tenureMonths());
    }

    @Override
    public void approveLoan(UUID requestId, ApprovalRequest request) {
        failureSimulator.maybeFail("loan approval");
        log.info("mock_loan_approved requestId={} refNo={} disbursementAmount={}",
                requestId, request.refNo(), request.disbursementAmount());
    }

    @Override
    public void recordRepayment(UUID requestId, RepaymentRequest request) {
        failureSimulator.maybeFail("loan repayment");
        log.info("mock_loan_repayment_recorded requestId={} refNo={} installments={} amount={}",
                requestId, request.refNo(), request.installmentsInThisPayment(), request.amount());
    }
}
