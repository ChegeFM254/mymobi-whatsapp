const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const PORT = process.env.PORT || 3000;

const ACCESS_TOKEN = 'EAAOxVVXxgvUBR9uCZCUrwCVYrQri56MBWpZCRAV8FzCfgosQ1AwvLAILAR3eT2XqCkOj0ZAd7ICfr1OsSYWzPomuw9SdeuD46c4BNYBBYROaXkAwL3msZCO2nERbyGxzL85iljY1lSLTkfJ6tiJMEwCbwdzHLtWJl05ymoxTEL2dWR8kKfCbdfIeJcgLX4gZBm0nczYFwUDZA4FB64hkiWW0bI84yX9b5FBWBvFCNalJRDbmR6CTGnNlrWR2hYDgQDawMZARgl5UIfV1xW1Epou3jaaXv4u1zis89RQ6AZDZD';
const PHONE_NUMBER_ID = '1265967949926220';
const VERIFY_TOKEN = 'mymobi_test_123';

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
    if (message) {
      const from = message.from;
      console.log("Message received from:", from);
      
      // Reply to ANY message
      await sendTextMessage(from, "Hello! I received your message. The bot is working.");
    }
  } catch (err) {
    console.error(err);
  }
  res.sendStatus(200);
});

async function sendTextMessage(to, text) {
  await axios.post(`https://graph.facebook.com/v20.0/${PHONE_NUMBER_ID}/messages`, {
    messaging_product: "whatsapp",
    to: to,
    type: "text",
    text: { body: text }
  }, {
    headers: { Authorization: `Bearer ${ACCESS_TOKEN}` }
  });
}

app.listen(PORT, () => console.log(`Server started on port ${PORT}`));
