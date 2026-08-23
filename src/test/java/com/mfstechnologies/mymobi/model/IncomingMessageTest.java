package com.mfstechnologies.mymobi.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IncomingMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesAPlainTextMessage() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {
                  "id": "wamid.TEXT123",
                  "from": "254700000001",
                  "type": "text",
                  "text": { "body": "hi" }
                }
                """);

        IncomingMessage message = IncomingMessage.parse(node);

        assertThat(message.messageId()).isEqualTo("wamid.TEXT123");
        assertThat(message.from()).isEqualTo("254700000001");
        assertThat(message.text()).isEqualTo("hi");
        assertThat(message.hasText()).isTrue();
        assertThat(message.hasButton()).isFalse();
    }

    @Test
    void parsesAButtonReply() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {
                  "id": "wamid.BTN123",
                  "from": "254700000001",
                  "type": "interactive",
                  "interactive": {
                    "type": "button_reply",
                    "button_reply": { "id": "civil_servants", "title": "Civil Servants" }
                  }
                }
                """);

        IncomingMessage message = IncomingMessage.parse(node);

        assertThat(message.buttonId()).isEqualTo("civil_servants");
        assertThat(message.hasButton()).isTrue();
        assertThat(message.hasText()).isFalse();
    }

    @Test
    void parsesAListReplyTheSameWayAsAButtonReply() throws Exception {
        // WhatsApp list selections and button taps arrive in different
        // JSON shapes but both need to be treated as "a button was
        // tapped" — matching the Node version's
        // `message.interactive?.button_reply?.id || message.interactive?.list_reply?.id` fallback.
        JsonNode node = objectMapper.readTree("""
                {
                  "id": "wamid.LIST123",
                  "from": "254700000001",
                  "type": "interactive",
                  "interactive": {
                    "type": "list_reply",
                    "list_reply": { "id": "loan_statement_menu", "title": "Loan Statement" }
                  }
                }
                """);

        IncomingMessage message = IncomingMessage.parse(node);

        assertThat(message.buttonId()).isEqualTo("loan_statement_menu");
        assertThat(message.hasButton()).isTrue();
    }

    @Test
    void missingFieldsParseAsNullRatherThanThrowing() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {
                  "id": "wamid.MINIMAL",
                  "from": "254700000001"
                }
                """);

        IncomingMessage message = IncomingMessage.parse(node);

        assertThat(message.text()).isNull();
        assertThat(message.buttonId()).isNull();
        assertThat(message.hasText()).isFalse();
        assertThat(message.hasButton()).isFalse();
    }
}
