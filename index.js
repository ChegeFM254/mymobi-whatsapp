const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const PORT = process.env.PORT || 3000;

const ACCESS_TOKEN = 'PASTE_YOUR_ACCESS_TOKEN_HERE';
const PHONE_NUMBER_ID = 'PASTE_YOUR_PHONE_NUMBER_ID_HERE';
const VERIFY_TOKEN = 'mymobi_test_123';   // You can change this

app.use(bodyParser.json());

app.get('/webhook', (req, res) => {
  if (req.query['hub.mode'] === 'subscribe' && req.query['hub.verify_token'] === VERIFY_TOKEN) {
    res.send(req.query['hub.challenge']);
  } else {
    res.sendStatus(403);
  }
});

app.post('/webhook', async (req, res) => {
  try {
    const message = req.body.entry?.[0]?.changes?.[0]?.value?.messages?.[0];
    if (!message) return res.sendStatus(200);

    const from = message.from;
    const buttonId = message.interactive?.button_reply?.id || message.interactive?.list_reply?.id;
    const text = message.text?.body?.toLowerCase() || '';

    if (['hi', 'hello', 'loan'].some(word => text.includes(word))) {
      await sendWelcome(from);
    } else if (buttonId) {
      await handleButton(from, buttonId);
    }

    res.sendStatus(200);
  } catch (e) {
    res.sendStatus(200);
  }
});

async function sendWelcome(to) {
  // Welcome Screen
  await sendMessage(to, {
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
  });
}

async function handleButton(to, id) {
  if (id === "civil_servants") {
    await sendOptIn(to);
  } else if (id === "logout") {
    await sendMessage(to, { type: "text", text: { body: "👋 Logged out." } });
  }
  // More buttons will be added later
}

async function sendOptIn(to) {
  await sendMessage(to, {
    type: "interactive",
    interactive: {
      type: "button",
      body: { text: "You are not registered.\nWould you like to OPT IN?" },
      action: {
        buttons: [
          { type: "reply", reply: { id: "optin_yes", title: "Yes" } },
          { type: "reply", reply: { id: "optin_no", title: "No" } }
        ]
      }
    }
  });
}

async function sendMessage(to, messageData) {
  const url = `https://graph.facebook.com/v20.0/${PHONE_NUMBER_ID}/messages`;
  await axios.post(url, {
    messaging_product: "whatsapp",
    to: to,
    ...messageData
  }, {
    headers: { Authorization: `Bearer ${ACCESS_TOKEN}` }
  });
}

app.listen(PORT, () => console.log('Server is running'));
