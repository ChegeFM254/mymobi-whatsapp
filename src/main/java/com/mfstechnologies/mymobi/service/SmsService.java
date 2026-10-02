package com.mfstechnologies.mymobi.service;

/**
 * WORKSTREAM F (mock service abstraction layer): a clean seam for
 * outbound SMS delivery - everywhere in the app that needs to send an
 * OTP or Approval Code via SMS calls this interface, rather than each
 * flow service independently simulating "SMS arrives a few seconds
 * later as a WhatsApp message" inline. Swapping in a real SMS gateway
 * integration later means implementing this interface once, not
 * touching every call site again.
 *
 * Replaces the 3 near-identical private deliverXAfterDelay() methods
 * that used to live separately in RegistrationFlowService,
 * ForgotPinFlowService, and LoanApplicationFlowService.
 */
public interface SmsService {

    /**
     * Sends the given message to the person's phone number via SMS.
     *
     * @param phoneNumber the recipient's phone number
     * @param message     the exact SMS body to send (e.g. "OTP 12345", "Approval Code 654321")
     */
    void sendSms(String phoneNumber, String message);
}
