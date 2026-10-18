package com.mfstechnologies.mymobi.model;

/**
 * WORKSTREAM G (outbox pattern): the kinds of durable, retryable work
 * the outbox dispatcher knows how to carry out. Each type has its own
 * retry policy (see OutboxRetryPolicy) and its own payload shape
 * (documented per constant below) - the dispatcher deserializes
 * OutboxEntry.payload differently depending on this value.
 *
 * Two families: notification deliveries (OTP_SMS, APPROVAL_CODE_SMS),
 * and requests to the backend services (the LOAN_* and CUSTOMER_* types,
 * which call the LoanService / CustomerService interfaces). The backend
 * request payloads are those interfaces' own nested request records,
 * serialized as-is - there is deliberately no separate outbox-only copy
 * of each shape to keep in sync.
 */
public enum OutboxEntryType {

    /** Payload: OutboxPayloads.SmsPayload {"phoneNumber": "...", "message": "OTP 12345"} - used by both Registration and Forgot PIN, which share the exact same delivery mechanism. */
    OTP_SMS,

    /** Payload: OutboxPayloads.ApprovalCodeSmsPayload {"phoneNumber": "...", "message": "Approval Code 654321", "refNo": "..."} */
    APPROVAL_CODE_SMS,

    /**
     * Payload: OutboxPayloads.StkPushPayload {"phoneNumber": "...", "amount": 15000.0, "purpose": "loan_payment"}.
     * Currently NOT raised by any flow: STK pushes are interactive and
     * time-sensitive, so they still call MpesaService directly rather
     * than going through retry-with-backoff. Kept (with its dispatcher
     * handling) for when the real M-Pesa integration's design settles.
     */
    MPESA_STK_PUSH,

    /** Payload: LoanService.ApplicationRequest - a new loan application, raised together with its APPROVAL_CODE_SMS. */
    LOAN_APPLICATION_SUBMIT,

    /** Payload: LoanService.ApprovalRequest - a loan was approved; the point at which disbursement happens. */
    LOAN_APPROVAL,

    /** Payload: LoanService.RepaymentRequest - one or more installments were paid. */
    LOAN_REPAYMENT_RECORD,

    /** Payload: CustomerService.CreateRequest - a newly registered customer (carries the bcrypt PIN hash, never the raw PIN). */
    CUSTOMER_CREATE,

    /** Payload: CustomerService.PinResetRequest - a customer reset their PIN (bcrypt hash only, never the raw PIN). */
    CUSTOMER_PIN_RESET
}
