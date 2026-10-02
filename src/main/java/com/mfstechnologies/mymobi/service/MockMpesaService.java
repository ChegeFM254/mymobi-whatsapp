package com.mfstechnologies.mymobi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * WORKSTREAM F (mock service abstraction layer): stands in for a real
 * M-Pesa Daraja API integration. Sends the explanatory WhatsApp message
 * a person would see alongside the native M-Pesa payment prompt, and
 * logs the simulated event - replacing the inline
 * log.info("mpesa_stk_push_simulated...") calls and duplicated message
 * text that used to be scattered across LoanPaymentFlowService,
 * PayslipFlowService, and LoanDocumentFlowService (which previously had
 * two slightly different copies of this same message, and
 * LoanPaymentFlowService was missing it entirely).
 *
 * TODO: replace with a real implementation once Dispatcher/Daraja
 * integration is ready (see the technical integration plan).
 */
@Service
public class MockMpesaService implements MpesaService {

    private static final Logger log = LoggerFactory.getLogger(MockMpesaService.class);

    private final WhatsAppMessageService messageService;

    public MockMpesaService(WhatsAppMessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public void initiateStkPush(String phoneNumber, double amount, String purpose) {
        messageService.sendTextMessage(phoneNumber,
                String.format("You are about to make payment to MyMobi of KES %,.2f. Please enter your Mpesa PIN.", amount));

        log.info("mpesa_stk_push_simulated to={} purpose={} amount={}", phoneNumber, purpose, amount);
    }
}
