package com.mfstechnologies.mymobi.service;

import java.util.UUID;

/**
 * WORKSTREAM G (outbox pattern): a clean seam for the backend customer
 * service - the system that will eventually own customer records and
 * credentials. Same arrangement as LoanService: this app keeps its own
 * copy in Postgres and propagates changes here via the outbox, and a
 * real implementation replaces MockCustomerService without touching any
 * caller. See LoanService for why every method takes a requestId and why
 * the request records double as the outbox payload.
 *
 * SENSITIVE DATA: PIN-related requests carry only the bcrypt hash, never
 * the raw PIN - a raw PIN must never be written into a retry queue's
 * table. CreateRequest also carries national ID and contact details, so
 * completed outbox entries are purged after a retention period rather
 * than kept indefinitely.
 */
public interface CustomerService {

    record CreateRequest(
            String phoneNumber,
            String firstName,
            String middleName,
            String lastName,
            String emailAddress,
            String upn,
            String nationalId,
            String mobileNumber,
            String hashedPin
    ) {
    }

    record PinResetRequest(
            String phoneNumber,
            String hashedPin
    ) {
    }

    void createCustomer(UUID requestId, CreateRequest request);

    void resetPin(UUID requestId, PinResetRequest request);
}
