package com.mfstechnologies.mymobi.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mfstechnologies.mymobi.config.WhatsAppProperties;
import com.mfstechnologies.mymobi.model.IncomingMessage;
import com.mfstechnologies.mymobi.service.ConversationService;
import com.mfstechnologies.mymobi.service.MessageDeduplicationService;
import com.mfstechnologies.mymobi.service.WebhookSignatureService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * Direct equivalent of the GET /webhook and POST /webhook routes in the
 * Node.js version.
 *
 * IMPORTANT — raw body requirement: signature verification must hash the
 * EXACT bytes Meta sent, not a re-serialized copy of the parsed JSON.
 * The controller accepts the raw body as bytes directly, then parses it
 * manually with Jackson — this guarantees the bytes used for the
 * signature check are identical to what Meta actually sent.
 *
 * As of this session: the controller itself is now thin — signature
 * check, dedup check, parse, then hand off to ConversationService for
 * everything else (trigger words, debounce, session, routing). This
 * mirrors the Node version's structure: the webhook handler does
 * plumbing, handleButton()/handleTextInput() do the actual conversation
 * logic.
 */
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WhatsAppProperties properties;
    private final WebhookSignatureService signatureService;
    private final MessageDeduplicationService deduplicationService;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    public WebhookController(
            WhatsAppProperties properties,
            WebhookSignatureService signatureService,
            MessageDeduplicationService deduplicationService,
            ConversationService conversationService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.signatureService = signatureService;
        this.deduplicationService = deduplicationService;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Subscription verification — Meta calls this once when you configure
     * the webhook URL in the Meta App Dashboard. Direct equivalent of the
     * Node version's app.get('/webhook', ...).
     */
    @GetMapping
    public ResponseEntity<String> verifySubscription(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge
    ) {
        if ("subscribe".equals(mode) && properties.verifyToken().equals(verifyToken)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * Receives incoming messages and status updates. Direct equivalent of
     * the Node version's app.post('/webhook', ...).
     */
    @PostMapping
    public ResponseEntity<Void> receiveWebhook(HttpServletRequest request) throws Exception {
        byte[] rawBody = request.getInputStream().readAllBytes();
        String signatureHeader = request.getHeader("X-Hub-Signature-256");

        if (!signatureService.isValid(signatureHeader, rawBody)) {
            log.warn("webhook_signature_rejected");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        JsonNode root = objectMapper.readTree(new String(rawBody, StandardCharsets.UTF_8));
        JsonNode messageNode = extractFirstMessage(root);

        if (messageNode == null) {
            // No message in this payload (e.g. a status update, not a
            // user message) — acknowledge and do nothing, same as the
            // Node version's `if (!message) return res.sendStatus(200);`
            return ResponseEntity.ok().build();
        }

        IncomingMessage message = IncomingMessage.parse(messageNode);

        if (deduplicationService.isDuplicate(message.messageId())) {
            log.info("duplicate_message_ignored messageId={}", message.messageId());
            return ResponseEntity.ok().build();
        }

        // Fire-and-forget: respond to Meta immediately (matching BUG FIX
        // #7 from the Node version — don't make Meta wait on our reply
        // being sent before acknowledging receipt of the webhook itself),
        // while the actual conversation handling continues in the
        // background.
        conversationService.handleIncomingMessage(message)
                .subscribe(
                        null,
                        error -> log.error("Failed to handle message from {}: {}", message.from(), error.getMessage())
                );

        return ResponseEntity.ok().build();
    }

    private JsonNode extractFirstMessage(JsonNode root) {
        JsonNode messages = root
                .path("entry").path(0)
                .path("changes").path(0)
                .path("value").path("messages");
        return messages.isArray() && !messages.isEmpty() ? messages.get(0) : null;
    }
}
