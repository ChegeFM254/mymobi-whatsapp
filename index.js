const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const PORT = process.env.PORT || 3000;

const ACCESS_TOKEN = 'EAAOxVVXxgvUBR7wNYKXZAvFlmOc18MwpvIsQAoxBr3ZC06UgYkghWOyRPDLS5rFWsd4GemlVRRzELVeD9xtUTVZCZCGFp2PVQ1mtVS9Gju4mcQgSQ05l02kdRGYA8vJCWMIaQcoXqfAJUBR6on3C4pwkRyCjC2UiBbiNYvgiRL7sO5AVtF5XBxYAS56ZADIL6AmRmK2ps8F8kbB69UPW5Rt25ZA7Q0bRIashp9k8sCZC8PeLycoc9mWEoTZA4nsIiaxsPZAr5brMAh6XHBtKpzwKO7DZB1ZBTZBZA1CVHHvffjgZDZD';        // ← Update
const PHONE_NUMBER_ID = '1265967949926220';  // ← Update
const VERIFY_TOKEN = 'mymobi_test_123';

app.use(bodyParser.json());

app.get('/webhook', (req, res) => {
  console.log("Verification request received");
  if (req.query['hub.mode'] === 'subscribe' && req.query['hub.verify_token'] === VERIFY_TOKEN) {
    res.send(req.query['hub.challenge']);
  } else {
    res.sendStatus(403);
  }
});

app.post('/webhook', async (req, res) => {
  console.log("=== NEW MESSAGE RECEIVED ===");
  console.log(JSON.stringify(req.body, null, 2));

  try {
    const message = req.body.entry?.[0]?.changes?.[0]?.value?.messages?.[0];
    
    if (message) {
      const from = message.from;
      console.log(`Message from: ${from}`);

      const text = message.text?.body?.toLowerCase() || '';

      if (text.includes('hi') || text.includes('hello') || text.includes('loan')) {
        await sendWelcome(from);
      } else if (message.interactive) {
        const buttonId = message.interactive.button_reply?.id || message.interactive.list_reply?.id;
        console.log(`Option selected: ${buttonId}`);

        if (buttonId === "civil_servants") {
          await sendOptIn(from);
        } else if (buttonId === "logout") {
          await sendTextMessage(from, "👋 You have been logged out successfully.");
        }
      }
    }
  } catch (err) {
    console.error("Error processing message:", err);
  }

  res.sendStatus(200);
});

// ==================== MAIN MENU (List) ====================
async function sendWelcome(to) {
  try {
    const payload = {
      messaging_product: "whatsapp",
      to: to,
      type: "interactive",
      interactive: {
        type: "list",
        header: { type: "text", text: "Welcome to MyMobi" },
        body: { text: "How can we assist you today?" },
        footer: { text: "MyMobi Emergency Loan" },
        action: {
          button: "Select Service",
          sections: [{
            title: "Services",
            rows: [
              { id: "civil_servants", title: "Civil Servants", description: "Emergency Loan" },
              { id: "buy_airtime", title: "Buy Airtime", description: "Quick top up" }
            ]
          }]
        }
      }
    };

    await axios.post(`https://graph.facebook.com/v20.0/${PHONE_NUMBER_ID}/messages`, payload, {
      headers: { Authorization: `Bearer ${ACCESS_TOKEN}` }
    });
    console.log("Welcome List sent");
  } catch (err) {
    console.error("Failed to send welcome:", err.response?.data || err.message);
  }
}

// ==================== OPT IN SCREEN ====================
async function sendOptIn(to) {
  try {
    const payload = {
      messaging_product: "whatsapp",
      to: to,
      type: "interactive",
      interactive: {
        type: "button",
        body: { text: "You are not registered for this service.\nWould you like to OPT IN?" },
        action: {
          buttons: [
            { type: "reply", reply: { id: "optin_yes", title: "Yes" } },
            { type: "reply", reply: { id: "optin_no", title: "No" } }
          ]
        }
      }
    };

    await axios.post(`https://graph.facebook.com/v20.0/${PHONE_NUMBER_ID}/messages`, payload, {
      headers: { Authorization: `Bearer ${ACCESS_TOKEN}` }
    });
    console.log("Opt In message sent");
  } catch (err) {
    console.error("Failed to send Opt In:", err.response?.data || err.message);
  }
}

// ==================== HELPER ====================
async function sendTextMessage(to, text) {
  try {
    await axios.post(`https://graph.facebook.com/v20.0/${PHONE_NUMBER_ID}/messages`, {
      messaging_product: "whatsapp",
      to: to,
      type: "text",
      text: { body: text }
    }, {
      headers: { Authorization: `Bearer ${ACCESS_TOKEN}` }
    });
  } catch (err) {
    console.error("Failed to send text:", err.response?.data || err.message);
  }
}

app.listen(PORT, () => console.log(`Server started on port ${PORT}`));
