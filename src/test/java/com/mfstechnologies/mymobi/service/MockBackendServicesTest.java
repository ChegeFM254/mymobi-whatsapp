package com.mfstechnologies.mymobi.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WORKSTREAM G (outbox pattern): covers both mock backend services in
 * one place, since they share the same shape - succeed by default, and
 * fail on every call when the simulator's failure rate is 1.0. The
 * point is to confirm every method actually consults the simulator;
 * without that, MOCK_BACKEND_FAILURE_RATE would silently do nothing for
 * whichever method forgot to.
 */
class MockBackendServicesTest {

    private static final String PHONE = "254700000001";
    private static final UUID REQUEST_ID = UUID.randomUUID();

    private static final LoanService.ApplicationRequest APPLICATION =
            new LoanService.ApplicationRequest(PHONE, "REF1", 15000, 1, "19999999", "2026-11-04");
    private static final LoanService.ApprovalRequest APPROVAL =
            new LoanService.ApprovalRequest(PHONE, "REF1", 13487);
    private static final LoanService.RepaymentRequest REPAYMENT =
            new LoanService.RepaymentRequest(PHONE, "REF1", 1, 16200);
    private static final CustomerService.CreateRequest CREATE =
            new CustomerService.CreateRequest(PHONE, "Jane", "Wanjiru", "Doe", "jane@example.com", "12345", "87654321", PHONE, "$2a$hash");
    private static final CustomerService.PinResetRequest PIN_RESET =
            new CustomerService.PinResetRequest(PHONE, "$2a$hash");

    private final MockLoanService reliableLoanService = new MockLoanService(new MockBackendFailureSimulator(0.0));
    private final MockLoanService failingLoanService = new MockLoanService(new MockBackendFailureSimulator(1.0));
    private final MockCustomerService reliableCustomerService = new MockCustomerService(new MockBackendFailureSimulator(0.0));
    private final MockCustomerService failingCustomerService = new MockCustomerService(new MockBackendFailureSimulator(1.0));

    @Test
    void theLoanServiceSucceedsOnEveryMethodByDefault() {
        assertThatCode(() -> {
            reliableLoanService.submitApplication(REQUEST_ID, APPLICATION);
            reliableLoanService.approveLoan(REQUEST_ID, APPROVAL);
            reliableLoanService.recordRepayment(REQUEST_ID, REPAYMENT);
        }).doesNotThrowAnyException();
    }

    @Test
    void theCustomerServiceSucceedsOnEveryMethodByDefault() {
        assertThatCode(() -> {
            reliableCustomerService.createCustomer(REQUEST_ID, CREATE);
            reliableCustomerService.resetPin(REQUEST_ID, PIN_RESET);
        }).doesNotThrowAnyException();
    }

    @Test
    void theLoanServiceFailsOnEveryMethodWhenTheFailureRateIsOne() {
        assertThatThrownBy(() -> failingLoanService.submitApplication(REQUEST_ID, APPLICATION))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failingLoanService.approveLoan(REQUEST_ID, APPROVAL))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failingLoanService.recordRepayment(REQUEST_ID, REPAYMENT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theCustomerServiceFailsOnEveryMethodWhenTheFailureRateIsOne() {
        assertThatThrownBy(() -> failingCustomerService.createCustomer(REQUEST_ID, CREATE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failingCustomerService.resetPin(REQUEST_ID, PIN_RESET))
                .isInstanceOf(IllegalStateException.class);
    }
}
