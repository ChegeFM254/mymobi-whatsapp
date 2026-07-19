package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.config.WhatsAppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.HexFormat;

/**
 * Verifies that an incoming webhook request genuinely came from Meta, by
 * checking the X-Hub-Signature-256 header against an HMAC-SHA256 hash of
 * the raw request body, computed using the App Secret.
 *
 * Direct equivalent of isValidWebhookSignature() from the Node.js version.
 * Same graceful-degradation behavior: if no App Secret is configured yet,
 * verification is skipped (with a startup warning logged elsewhere), not
 * hard-failed — so local/early testing isn't blocked before a real App
 * Secret exists. Once appSecret is set, verification becomes mandatory.
 */
@Service
public class WebhookSignatureService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final WhatsAppProperties properties;

    public WebhookSignatureService(WhatsAppProperties properties) {
        this.properties = properties;
    }

    /**
     * @param signatureHeader the raw value of the X-Hub-Signature-256 header, or null if absent
     * @param rawBody         the exact raw bytes of the request body — MUST be the untouched
     *                        bytes Meta sent, not a re-serialized copy, or the hash will never match
     */
    public boolean isValid(String signatureHeader, byte[] rawBody) {
        if (!properties.isSignatureVerificationEnabled()) {
            return true; // not configured yet — see WhatsAppProperties
        }

        if (signatureHeader == null || signatureHeader.isBlank() || rawBody == null) {
            return false;
        }

        String expectedSignature = SIGNATURE_PREFIX + computeHmacSha256Hex(rawBody);

        // Constant-time comparison — MessageDigest.isEqual() is the JDK's
        // built-in method for exactly this purpose: it prevents an
        // attacker from guessing the correct signature one byte at a
        // time by measuring how long the comparison takes to fail.
        return MessageDigest.isEqual(
                signatureHeader.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String computeHmacSha256Hex(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.appSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(rawBody);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is a standard JDK algorithm and appSecret is a
            // plain string key, so this should never actually happen in
            // practice — but if it did, fail closed (reject the request)
            // rather than silently treating it as valid.
            log.error("Failed to compute webhook signature", e);
            return "";
        }
    }
}