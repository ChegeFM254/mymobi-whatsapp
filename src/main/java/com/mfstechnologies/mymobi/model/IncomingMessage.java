package com.mfstechnologies.mymobi.model;

import tools.jackson.databind.JsonNode;

/**
 * A cleanly-parsed incoming WhatsApp message — pulls the handful of
 * fields the rest of the app actually needs (sender, message ID, typed
 * text, tapped button/list id) out of Meta's deeply-nested webhook JSON
 * shape, so nothing else in the codebase needs to know that shape.
 *
 * Equivalent to how the Node.js version destructured `message.from`,
 * `message.text?.body`, `message.interactive?.button_reply?.id` etc.
 * directly in the webhook handler — pulled into its own type here since
 * Java benefits more from an explicit model than repeating JsonNode
 * path-navigation in multiple places.
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

        return new IncomingMessage(messageId, from, text, buttonId);
    }

    public boolean hasButton() {
        return buttonId != null && !buttonId.isBlank();
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
