package com.mfstechnologies.mymobi.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A cleanly-parsed incoming WhatsApp message - pulls the handful of
 * fields the rest of the app actually needs (sender, message ID, typed
 * text, tapped button/list id) out of Meta's deeply-nested webhook JSON
 * shape, so nothing else in the codebase needs to know that shape.
 *
 * Equivalent to how the Node.js version destructured message.from,
 * message.text?.body, message.interactive?.button_reply?.id etc.
 * directly in the webhook handler - pulled into its own type here since
 * Java benefits more from an explicit model than repeating JsonNode
 * path-navigation in multiple places.
 *
 * WORKSTREAM E (WhatsApp Flows webview for PIN/OTP/Approval Code):
 * a Flow submission (interactive.type == "nfm_reply") is deliberately
 * folded into the same "text" field a typed reply would use. This means
 * the entire rest of the app (step routing in ConversationService,
 * validation, business logic in every flow service) needs ZERO changes
 * to support Flow-based entry: from its point of view, a Flow submission
 * and a typed message are indistinguishable.
 */
public record IncomingMessage(
        String messageId,
        String from,
        String text,
        String buttonId
) {
    public static IncomingMessage parse(JsonNode messageNode) {
        String messageId = messageNode.path("id").asText(null);
        String from = messageNode.path("from").asText(null);
        String text = messageNode.path("text").path("body").asText(null);

        String buttonId = messageNode.path("interactive").path("button_reply").path("id").asText(null);
        if (buttonId == null) {
            buttonId = messageNode.path("interactive").path("list_reply").path("id").asText(null);
        }

        if (text == null && isFlowResponse(messageNode)) {
            text = extractFlowResponseCode(messageNode);
        }

        return new IncomingMessage(messageId, from, text, buttonId);
    }

    private static boolean isFlowResponse(JsonNode messageNode) {
        return "nfm_reply".equals(messageNode.path("interactive").path("type").asText(null));
    }

    /**
     * The Flow's submitted data arrives as a JSON string nested inside
     * the webhook payload (response_json), not as a native JSON object -
     * this is Meta's actual wire format, not a parsing shortcut taken
     * here. Requires a second parse pass on that string.
     */
    private static String extractFlowResponseCode(JsonNode messageNode) {
        String responseJson = messageNode.path("interactive").path("nfm_reply").path("response_json").asText(null);
        if (responseJson == null || responseJson.isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = new ObjectMapper().readTree(responseJson);
            return parsed.path("code").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasButton() {
        return buttonId != null && !buttonId.isBlank();
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
