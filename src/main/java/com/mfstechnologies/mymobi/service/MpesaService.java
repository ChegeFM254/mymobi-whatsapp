package com.mfstechnologies.mymobi.service;

/**
 * WORKSTREAM F (mock service abstraction layer): a clean seam for the
 * M-Pesa STK Push integration - everywhere in the app that needs to
 * trigger an M-Pesa payment prompt calls this interface, rather than
 * each flow service independently simulating it inline. Swapping in a
 * real Daraja API integration later means implementing this interface
 * once, not touching every call site again.
 *
 * In reality, "STK Push" triggers a native payment prompt directly on
 * the person's phone via Safaricom's own M-Pesa app/system - the prompt
 * itself, and the 4-digit M-Pesa PIN entry, happen entirely outside our
 * app, on the phone's own M-Pesa UI. We never see or validate that PIN
 * ourselves. The WhatsApp message a mock/real implementation sends here
 * is purely an explanatory companion message, not a substitute for the
 * native prompt.
 */
public interface MpesaService {

    /**
     * Initiates an M-Pesa STK Push payment prompt for the given amount.
     *
     * @param phoneNumber the person's phone number (also their M-Pesa number)
     * @param amount      the amount in KES to be charged
     * @param purpose     a short, internal label for what this payment is for (e.g. "payslip", "loan_statement") - used for logging only, never shown to the person
     */
    void initiateStkPush(String phoneNumber, double amount, String purpose);
}
