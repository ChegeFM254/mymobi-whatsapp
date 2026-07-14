const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const PORT = process.env.PORT || 3000;

const ACCESS_TOKEN = 'EAAOxVVXxgvUBR3nkWQLNMSEHU3DZCNGWE8eT1nZAZCajQ0ZAFwMUemn3NtYkqhkt2ZCsZAD0lH53eq62L9VOoaYCngF0YB3m2Y4H030cS3ZC2Q22gppTUf3tLPIPljXY5Iw48j8FyF5HY8XB2yCEo08r5sxfiWHw1VUqaE20wLoA2frC81kBzIVdw7fGePMUQdajgGMijtY3lQXuy8m3qbrPO9vxdPrGOUYZBOsORI5Cas95f4oGArUl4sdZCp5s79a0P2YQuWQtSbVoCVrF9DjuVmnT5ljQJK6mfwUP3nwZDZD';
const PHONE_NUMBER_ID = '1225664000624541';
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
    const entry = req.body.entry?.[0];
    const change = entry?.changes?.[0];
    const message = change?.value?.messages?.[0];

    if (message) {
      const from = message.from;
      console.log(`Message from: ${from}`);

      if (message.text) {
        console.log(`Text: ${message.text.body}`);
        await sendWelcome(from);
      } else if (message.interactive) {
        const id = message.interactive.button_reply?.id;
        console.log(`Button clicked: ${id}`);
      }
    }
  } catch (err) {
    console.error("Error:", err);
  }

  res.sendStatus(200);
});

async function sendWelcome(to) {
  console.log(`Sending welcome to ${to}`);
  // ... (rest of the sendWelcome function from previous code)
  // I'll give the full updated file if needed
}

app.listen(PORT, () => console.log(`Server started on port ${PORT}`));
