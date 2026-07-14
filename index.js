const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const PORT = process.env.PORT || 3000;

const ACCESS_TOKEN = 'EAAOxVVXxgvUBR7wNYKXZAvFlmOc18MwpvIsQAoxBr3ZC06UgYkghWOyRPDLS5rFWsd4GemlVRRzELVeD9xtUTVZCZCGFp2PVQ1mtVS9Gju4mcQgSQ05l02kdRGYA8vJCWMIaQcoXqfAJUBR6on3C4pwkRyCjC2UiBbiNYvgiRL7sO5AVtF5XBxYAS56ZADIL6AmRmK2ps8F8kbB69UPW5Rt25ZA7Q0bRIashp9k8sCZC8PeLycoc9mWEoTZA4nsIiaxsPZAr5brMAh6XHBtKpzwKO7DZB1ZBTZBZA1CVHHvffjgZDZD';   // ← Change this
const PHONE_NUMBER_ID = '1265967949926220'; // ← Change this
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
        console.log("Sending Welcome message");
        await sendWelcome(from);
      } else if (message.interactive) {
  const buttonId = message.interactive.button_reply?.id;
  console.log(`Button pressed: ${buttonId}`);

  if (buttonId === "civil_servants") {
    await sendOptIn(to);
  } else if (buttonId === "logout") {
    await sendTextMessage(to, "👋 You have been logged out.");
  }
}
    }
  } catch (err) {
    console.error("Error processing message:", err);
  }

  res.sendStatus(200);
});

async function sendWelcome(to) {
  try {
    const payload = {
      messaging_product: "whatsapp",
      to: to,
      type: "interactive",
      interactive: {
        type: "button",
        header: { type: "text", text: "Welcome to MyMobi" },
        body: { text: "Select a service" },
        action: {
          buttons: [
            { type: "reply", reply: { id: "civil_servants", title: "Civil Servants" } },
            { type: "reply", reply: { id: "buy_airtime", title: "Buy Airtime" } },
            { type: "reply", reply: { id: "logout", title: "Logout" } }
          ]
        }
      }
    };

    await axios.post(`https://graph.facebook.com/v20.0/${PHONE_NUMBER_ID}/messages`, payload, {
      headers: { Authorization: `Bearer ${ACCESS_TOKEN}` }
    });
    console.log("Welcome message sent successfully");
  } catch (err) {
    console.error("Failed to send message:", err.response?.data || err.message);
  }
}

app.listen(PORT, () => console.log(`Server started on port ${PORT}`));
