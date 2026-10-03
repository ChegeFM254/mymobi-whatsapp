package com.mfstechnologies.mymobi.controller;

import com.mfstechnologies.mymobi.service.OutboxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * WORKSTREAM G (outbox pattern): minimal monitoring endpoint for
 * entries that exhausted all retry attempts - these represent SMS/STK
 * pushes that genuinely never got delivered and need manual follow-up.
 *
 * Protected by a shared-secret header (X-Admin-Key) rather than left
 * open - this app's SecurityConfig permits all requests by default
 * (required for the WhatsApp webhook), so without this check, anyone
 * with the URL could see phone numbers, SMS content, and loan amounts
 * for every failed delivery. Requires app.admin-api-key (env var
 * ADMIN_API_KEY) to be set - if it's blank/unset, this endpoint refuses
 * every request rather than failing open.
 */
@RestController
@RequestMapping("/admin/outbox")
public class OutboxAdminController {

    private final OutboxService outboxService;
    private final String adminApiKey;

    public OutboxAdminController(
            OutboxService outboxService,
            @Value("${app.admin-api-key:}") String adminApiKey
    ) {
        this.outboxService = outboxService;
        this.adminApiKey = adminApiKey;
    }

    @GetMapping("/exhausted")
    public ResponseEntity<List<OutboxEntryView>> listExhausted(@RequestHeader(value = "X-Admin-Key", required = false) String providedKey) {
        if (adminApiKey.isBlank() || !adminApiKey.equals(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<OutboxEntryView> views = outboxService.findExhausted().stream()
                .map(OutboxEntryView::from)
                .toList();
        return ResponseEntity.ok(views);
    }
}
