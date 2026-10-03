package com.mfstechnologies.mymobi.service;

/**
 * WORKSTREAM G (outbox pattern): the payload shapes for each
 * OutboxEntryType, serialized to/from OutboxEntry.payload as JSON.
 * Grouped in one file since they're small and only ever used together
 * by OutboxService (writing) and OutboxDispatcher (reading).
 */
public final class OutboxPayloads {

    private OutboxPayloads() {
    }

    /** OutboxEntryType.OTP_SMS */
    public record SmsPayload(String phoneNumber, String message) {
    }

    /** OutboxEntryType.APPROVAL_CODE_SMS - refNo is used for the staleness check before showing the Approve Loan screen (handled separately, not by this payload). */
    public record ApprovalCodeSmsPayload(String phoneNumber, String message, String refNo) {
    }

    /** OutboxEntryType.MPESA_STK_PUSH */
    public record StkPushPayload(String phoneNumber, double amount, String purpose) {
    }
}
