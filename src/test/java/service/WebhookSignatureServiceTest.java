package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.config.WhatsAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureServiceTest {

    private static final String APP_SECRET = "test_app_secret_12345";

    private WebhookSignatureService signatureService;

    @BeforeEach
    void setUp() {
        WhatsAppProperties properties = new WhatsAppProperties(
                "test_access_token",
                "test_phone_number_id",
                "test_verify_token",
                APP_SECRET,
                "v21.0",
                "http://localhost:3000"
        );
        signatureService = new WebhookSignatureService(properties);
    }

    @Test
    void validSignatureIsAccepted() throws Exception {
        byte[] body = "{\"test\":\"payload\"}".getBytes(StandardCharsets.UTF_8);
        String validSignature = "sha256=" + computeExpectedSignature(body, APP_SECRET);

        assertThat(signatureService.isValid(validSignature, body)).isTrue();
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        byte[] originalBody = "{\"test\":\"payload\"}".getBytes(StandardCharsets.UTF_8);
        String signatureForOriginalBody = "sha256=" + computeExpectedSignature(originalBody, APP_SECRET);

        byte[] tamperedBody = "{\"test\":\"tampered\"}".getBytes(StandardCharsets.UTF_8);

        // Same signature, but the body it was computed for has changed —
        // this is exactly the attack signature verification exists to catch.
        assertThat(signatureService.isValid(signatureForOriginalBody, tamperedBody)).isFalse();
    }

    @Test
    void wrongSecretProducesRejectedSignature() throws Exception {
        byte[] body = "{\"test\":\"payload\"}".getBytes(StandardCharsets.UTF_8);
        String signatureFromWrongSecret = "sha256=" + computeExpectedSignature(body, "wrong_secret");

        assertThat(signatureService.isValid(signatureFromWrongSecret, body)).isFalse();
    }

    @Test
    void missingSignatureHeaderIsRejectedWhenSecretIsConfigured() {
        byte[] body = "{\"test\":\"payload\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(signatureService.isValid(null, body)).isFalse();
    }

    @Test
    void verificationIsSkippedWhenNoAppSecretIsConfiguredYet() {
        // Matches the Node version's graceful-degradation behavior:
        // before a real App Secret is set, verification doesn't block
        // testing — it's skipped, with a startup warning logged elsewhere.
        WhatsAppProperties propertiesWithoutSecret = new WhatsAppProperties(
                "test_access_token",
                "test_phone_number_id",
                "test_verify_token",
                "", // no app secret configured
                "v21.0",
                "http://localhost:3000"
        );
        WebhookSignatureService serviceWithoutSecret = new WebhookSignatureService(propertiesWithoutSecret);

        byte[] body = "{\"test\":\"payload\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(serviceWithoutSecret.isValid(null, body)).isTrue();
        assertThat(serviceWithoutSecret.isValid("garbage-not-even-a-real-signature", body)).isTrue();
    }

    /**
     * Independently computes the expected HMAC-SHA256 hex digest,
     * mirroring exactly what Meta itself computes server-side — this is
     * what makes the "validSignatureIsAccepted" test meaningful rather
     * than circular (it doesn't call the same code path being tested).
     */
    private String computeExpectedSignature(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}