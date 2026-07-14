const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const PORT = process.env.PORT || 3000;

const ACCESS_TOKEN = 'EAAOxVVXxgvUBRwmi9nAExZC2cQW6ues7wEZCQSDnY7i1RMdt7wDmZAVJUjEkgnE1dohMdxFeuzg1ByjrCeB6ytU9a4Api7lblflnpr5MVj7RKLkqV8k3X20w2OkE0ourq97dDoFbJB009CtAqwrCYGNQSJSTkBs8vdNnfskm1dZAaiNm8a81F6t6xvhQvjCZAsysQlo1mZAJq3TZCoWX3hqYkh4UtpYxDDSsWVGKws9Ms1P6enZBMpcDHvczDAXqd5zrlaBoZAczBGBfm1NjBsh2gLZBR5pcxZADuhPohPeLwZDZD';
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
