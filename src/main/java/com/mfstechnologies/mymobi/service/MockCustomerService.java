package com.mfstechnologies.mymobi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * WORKSTREAM G (outbox pattern): stands in for the real backend
 * customer service. Logs each request and succeeds (or fails, at the
 * rate configured on MockBackendFailureSimulator). Deliberately logs
 * only the request id and phone number - never the name, national ID,
 * or PIN hash carried in the request.
 *
 * TODO: replace with a real implementation once the customer service's
 * contract is available.
 */
@Service
public class MockCustomerService implements CustomerService {

    private static final Logger log = LoggerFactory.getLogger(MockCustomerService.class);

    private final MockBackendFailureSimulator failureSimulator;

    public MockCustomerService(MockBackendFailureSimulator failureSimulator) {
        this.failureSimulator = failureSimulator;
    }

    @Override
    public void createCustomer(UUID requestId, CreateRequest request) {
        failureSimulator.maybeFail("customer create");
        log.info("mock_customer_created requestId={} phoneNumber={}", requestId, request.phoneNumber());
    }

    @Override
    public void resetPin(UUID requestId, PinResetRequest request) {
        failureSimulator.maybeFail("customer pin reset");
        log.info("mock_customer_pin_reset requestId={} phoneNumber={}", requestId, request.phoneNumber());
    }
}
