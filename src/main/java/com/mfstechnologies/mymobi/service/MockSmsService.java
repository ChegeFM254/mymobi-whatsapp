package com.mfstechnologies.mymobi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * WORKSTREAM F (mock service abstraction layer): stands in for a real
 * SMS gateway integration. Simulates network/delivery latency by
 * delivering the message as a separate WhatsApp message a few seconds
 * later, rather than sending it immediately - this is purely a testing
 * convenience (so the person sees something close to how a real SMS
 * arrival would feel), not a real SMS send.
 *
 * TODO: replace with a real SMS gateway implementation once one is
 * selected/integrated (see the technical integration plan).
 */
@Service
public class MockSmsService implements SmsService {

    private static final Logger log = LoggerFactory.getLogger(MockSmsService.class);
    private static final long SIMULATED_DELAY_SECONDS = 5;

    private final WhatsAppMessageService messageService;

    public MockSmsService(WhatsAppMessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public void sendSms(String phoneNumber, String message) {
        CompletableFuture.runAsync(
                () -> {
                    try {
                        messageService.sendTextMessage(phoneNumber, message);
                    } catch (Exception err) {
                        log.error("Failed to deliver simulated SMS to {}: {}", phoneNumber, err.getMessage());
                    }
                },
                CompletableFuture.delayedExecutor(SIMULATED_DELAY_SECONDS, TimeUnit.SECONDS)
        );
    }
}
