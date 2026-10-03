package com.mfstechnologies.mymobi.model;

/**
 * WORKSTREAM G (outbox pattern): the kinds of durable, retryable work
 * the outbox dispatcher knows how to carry out. Each type has its own
 * retry policy (see OutboxRetryPolicies) and its own payload shape
 * (documented per constant below) - the dispatcher deserializes
 * OutboxEntry.payload differently depending on this value.
 */
public enum OutboxEntryType {

    /** Payload: {"phoneNumber": "...", "message": "OTP 12345"} - used by both Registration and Forgot PIN, which share the exact same delivery mechanism. */
    OTP_SMS,

    /** Payload: {"phoneNumber": "...", "message": "Approval Code 654321", "refNo": "..."} - refNo is used for the staleness check before showing the Approve Loan screen. */
    APPROVAL_CODE_SMS,

    /** Payload: {"phoneNumber": "...", "amount": 15000.0, "purpose": "loan_payment"} */
    MPESA_STK_PUSH
}
