package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * WORKSTREAM B (reactive -> synchronous): fireTimeout's error handling
 * changed from .onErrorResume(...).subscribe() to a plain try/catch,
 * since sendTextMessage() now throws directly instead of carrying
 * errors on a reactive error channel. This file was missed in the
 * initial sweep of Workstream B conversions - found only when the build
 * genuinely failed on it, confirming the value of a full clean build
 * rather than assuming a file-by-file review caught everything.
 */
@Service
public class InactivityTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(InactivityTimeoutService.class);
    private static final long DEFAULT_INACTIVITY_TIMEOUT_SECONDS = 60;
    private static final String TIMEOUT_MESSAGE = "Your session has ended due to a period of inactivity. Please type 'Hi' to start a new session.";

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledFuture<?>> pendingTimeouts = new ConcurrentHashMap<>();
    private final long timeoutMillis;

    private final SessionStore sessionStore;
    private final WhatsAppMessageService messageService;

    @Autowired
    public InactivityTimeoutService(SessionStore sessionStore, WhatsAppMessageService messageService) {
        this(sessionStore, messageService, java.time.Duration.ofSeconds(DEFAULT_INACTIVITY_TIMEOUT_SECONDS));
    }

    InactivityTimeoutService(SessionStore sessionStore, WhatsAppMessageService messageService, java.time.Duration timeout) {
        this.sessionStore = sessionStore;
        this.messageService = messageService;
        this.timeoutMillis = timeout.toMillis();
    }

    public void resetTimeout(String to) {
        cancelTimeout(to);

        ScheduledFuture<?> future = scheduler.schedule(() -> fireTimeout(to), timeoutMillis, TimeUnit.MILLISECONDS);
        pendingTimeouts.put(to, future);
    }

    public void cancelTimeout(String to) {
        ScheduledFuture<?> existing = pendingTimeouts.remove(to);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    private void fireTimeout(String to) {
        sessionStore.delete(to);
        pendingTimeouts.remove(to);

        try {
            messageService.sendTextMessage(to, TIMEOUT_MESSAGE);
        } catch (Exception err) {
            log.warn("Failed to deliver timeout message to {}: {}", to, err.getMessage());
        }
    }
}
