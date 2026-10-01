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

    @Test
    void parsesAWhatsAppFlowSubmissionAsPlainText() throws Exception {
        // WORKSTREAM E: the submitted "code" value is deliberately folded
        // into the same text() field a typed reply would use - this is
        // what lets the rest of the app treat a Flow submission (e.g.
        // entering a PIN via the Flow) identically to typed text, with
        // zero changes needed to step routing or validation.
        JsonNode node = objectMapper.readTree("""
                {
                  "id": "wamid.FLOW123",
                  "from": "254700000001",
                  "type": "interactive",
                  "interactive": {
                    "type": "nfm_reply",
                    "nfm_reply": {
                      "name": "flow",
                      "body": "Sent",
                      "response_json": "{\\"flow_token\\":\\"abc123\\",\\"code\\":\\"54321\\"}"
                    }
                  }
                }
                """);

        IncomingMessage message = IncomingMessage.parse(node);

        assertThat(message.text()).isEqualTo("54321");
        assertThat(message.hasText()).isTrue();
        assertThat(message.hasButton()).isFalse();
    }

    @Test
    void malformedFlowResponseJsonParsesAsNullRatherThanThrowing() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {
                  "id": "wamid.FLOWBAD",
                  "from": "254700000001",
                  "type": "interactive",
                  "interactive": {
                    "type": "nfm_reply",
                    "nfm_reply": {
                      "name": "flow",
                      "response_json": "{not valid json"
                    }
                  }
                }
                """);

        IncomingMessage message = IncomingMessage.parse(node);

        assertThat(message.text()).isNull();
        assertThat(message.hasText()).isFalse();
    }

    @Test
    void flowResponseWithoutACodeFieldParsesAsNull() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {
                  "id": "wamid.FLOWNOCODE",
                  "from": "254700000001",
                  "type": "interactive",
                  "interactive": {
                    "type": "nfm_reply",
                    "nfm_reply": {
                      "name": "flow",
                      "response_json": "{\\"flow_token\\":\\"abc123\\"}"
                    }
                  }
                }
                """);

        IncomingMessage message = IncomingMessage.parse(node);

        assertThat(message.text()).isNull();
        assertThat(message.hasText()).isFalse();
    }
}
