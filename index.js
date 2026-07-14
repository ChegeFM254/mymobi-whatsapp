const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const PORT = process.env.PORT || 3000;

const ACCESS_TOKEN = 'EAAOxVVXxgvUBR0jTvAcKZAunT68lBvfVzSCNHzpkljrwzTZAuqNVmZBnnFvyDUifj4Qbv7nABHz9XXFTJDfTSzGYMXOtmNpCZCayhmSOvQx5CR9AEvU6FAqM9ZBsUYpHQefknXBamTqxD6C9OGZAvKL9Xo99uNf1KOOwfwgmr3RH6TC9iKzOsqIHsXv5KFhQdwsNZCdthK6VXAaMg3ZBn2OcBoZCgropj5cZAZBtUfywhiY0GEBZCGxNuttUkrw8p8Ae2vVBnHTMeGjTZAc6YdUEL2HZCooNTXQMlcGQN00chH8QZDZD';   // ← Change this
const PHONE_NUMBER_ID = '1225664000624541'; // ← Change this
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
