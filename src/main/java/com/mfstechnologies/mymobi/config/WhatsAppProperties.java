package com.mfstechnologies.mymobi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized WhatsApp Cloud API configuration. All values are read from
 * environment variables / application.yml — nothing is hardcoded, matching
 * how the Node.js version used process.env.
 *
 * The Graph API version is deliberately configurable (not a constant baked
 * into the code) so bumping it later — e.g. from v21.0 to a newer version —
 * is a config change in Render's environment variables, not a code change
 * or redeploy of new logic.
 */
@ConfigurationProperties(prefix = "whatsapp")
public record WhatsAppProperties(
        String accessToken,
        String phoneNumberId,
        String verifyToken,
        String appSecret,
        String graphApiVersion,
        String publicBaseUrl
) {
    /**
     * Compact constructor: applies the same defaults the Node .env setup
     * used (WHATSAPP_VERIFY_TOKEN defaulted to "mymobi_test_123", the
     * Graph API version defaulted to v21.0). appSecret is allowed to be
     * blank — see WebhookSignatureService, which treats a blank secret
     * the same way the Node version did: signature verification is
     * skipped with a startup warning, not a hard failure, so local
     * testing isn't blocked before a real App Secret is configured.
     */
    public WhatsAppProperties {
        if (verifyToken == null || verifyToken.isBlank()) {
            verifyToken = "mymobi_test_123";
        }
        if (graphApiVersion == null || graphApiVersion.isBlank()) {
            graphApiVersion = "v21.0";
        }
    }

    public boolean isSignatureVerificationEnabled() {
        return appSecret != null && !appSecret.isBlank();
    }
}