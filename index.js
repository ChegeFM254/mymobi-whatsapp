const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const PORT = process.env.PORT || 3000;

const ACCESS_TOKEN = 'EAAOxVVXxgvUBR87P5VZCKuGosB6bFeHgvNVb3iyi2NbmkZAZAU0BEEZBtiLHMjUcZCZAgZCFBuAGJivHc1LZAdSkehnNZBpqRKN1uNnH4ENBZBv7XmrRtLYpGQsh0ZAakzq8mfmpuhAASTDd1d1bwNkzDohQ6PF0lr4PhZA9QijKEb9kh9waatrBTfxffH0uOmWbTfcN9h6O8jbFQ0qmP2toyvvdCoUbJsw1NbUCH3bkvTYAbZCnkSSxXltPVBYYQFfZBHIWl3CvmA4ZBLiJE9jUkw9RG8f9OHWG1kUVp8y8uaOPwZDZD';
const PHONE_NUMBER_ID = '1265967949926220';
const VERIFY_TOKEN = 'mymobi_test_123';

app.use(bodyParser.json());

const userSessions = {};

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
    if (!userSessions[from]) userSessions[from] = { step: 'welcome' };

    const session = userSessions[from];
    const buttonId = message.interactive?.button_reply?.id || message.interactive?.list_reply?.id;
    const text = message.text?.body || '';

    if (buttonId) {
      await handleButton(from, buttonId, session);
    } else if (text) {
      await handleTextInput(from, text, session);
    } else if (['hi', 'hello', 'loan'].some(w => text.toLowerCase().includes(w))) {
      await sendWelcome(from);
    }
  } catch (err) {
    console.error(err);
  }
  res.sendStatus(200);
});

// ==================== SCREENS ====================

async function sendWelcome(to) {
  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "list",
      header: { type: "text", text: "Welcome to MyMobi" },
      body: { text: "Select a service" },
      footer: { text: "MyMobi Emergency Loan" },
      action: {
        button: "Choose Option",
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
  await sendMessage(to, payload);
}

async function sendOptIn(to) {
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
  await sendMessage(to, payload);
}

async function sendTerms(to) {
  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "button",
      body: { text: "Please accept T&Cs and Data Privacy Policy of MyMobi Civil Servants Emergency Loan.\nView at: www.mymobi.co.ke" },
      action: {
        buttons: [
          { type: "reply", reply: { id: "accept_tc", title: "✅ Accept" } },
          { type: "reply", reply: { id: "decline_tc", title: "Decline" } }
        ]
      }
    }
  };
  await sendMessage(to, payload);
}

async function sendConfirmation(to, session) {
  const details = `Confirm Details:\n\nName: ${session.firstName || ''} ${session.lastName || ''}\nUPN: ${session.upn || ''}\nNational ID: ${session.nationalId || ''}\n\nIs this correct?`;

  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "button",
      body: { text: details },
      action: {
        buttons: [
          { type: "reply", reply: { id: "confirm_details", title: "✅ Accept" } },
          { type: "reply", reply: { id: "edit_details", title: "✏️ Edit" } }
        ]
      }
    }
  };
  await sendMessage(to, payload);
}

async function sendEditOptions(to) {
  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "button",
      body: { text: "Which field would you like to edit?" },
      action: {
        buttons: [
          { type: "reply", reply: { id: "edit_firstname", title: "First Name" } },
          { type: "reply", reply: { id: "edit_lastname", title: "Last Name" } },
          { type: "reply", reply: { id: "edit_upn", title: "UPN" } },
          { type: "reply", reply: { id: "edit_nationalid", title: "National ID" } }
        ]
      }
    }
  };
  await sendMessage(to, payload);
}

// ==================== SUCCESS + RETURN TO HOME ====================
async function sendSuccess(to) {
  await sendTextMessage(to, "✅ Registration Successful!\n\nYour details have been submitted. You will receive confirmation shortly.");

  // Automatically return to Home/Menu
  setTimeout(async () => {
    await sendWelcome(to);
  }, 1500);
}

// ==================== HANDLERS ====================

async function handleButton(to, id, session) {
  if (id === "civil_servants") {
    session.step = "optin";
    await sendOptIn(to);
  } else if (id === "optin_yes") {
    session.step = "tc";
    await sendTerms(to);
  } else if (id === "accept_tc") {
    session.step = "first_name";
    await sendTextMessage(to, "Enter your First Name");
  } else if (id === "confirm_details") {
    await sendSuccess(to);
  } else if (id === "edit_details") {
    await sendEditOptions(to);
  } else if (id.startsWith("edit_")) {
    session.step = id;
    const fieldName = id.replace("edit_", "").replace("_", " ");
    await sendTextMessage(to, `Enter new ${fieldName}:`);
  }
}

async function handleTextInput(to, text, session) {
  if (session.step === "first_name") {
    session.firstName = text;
    session.step = "last_name";
    await sendTextMessage(to, "Enter your Last Name");
  } else if (session.step === "last_name") {
    session.lastName = text;
    session.step = "upn";
    await sendTextMessage(to, "Enter UPN");
  } else if (session.step === "upn") {
    session.upn = text;
    session.step = "national_id";
    await sendTextMessage(to, "Enter National ID Number");
  } else if (session.step === "national_id") {
    session.nationalId = text;
    await sendConfirmation(to, session);
  } else if (session.step.startsWith("edit_")) {
    const field = session.step.replace("edit_", "");
    if (field === "firstname") session.firstName = text;
    if (field === "lastname") session.lastName = text;
    if (field === "upn") session.upn = text;
    if (field === "nationalid") session.nationalId = text;

    await sendConfirmation(to, session);
  }
}

async function sendTextMessage(to, text) {
  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "text",
    text: { body: text }
  };
  await sendMessage(to, payload);
}

async function sendMessage(to, payload) {
  try {
    await axios.post(`https://graph.facebook.com/v20.0/${PHONE_NUMBER_ID}/messages`, payload, {
      headers: { Authorization: `Bearer ${ACCESS_TOKEN}` }
    });
  } catch (err) {
    console.error("Send failed:", err.response?.data || err.message);
  }
}

app.listen(PORT, () => console.log(`Server started on port ${PORT}`));
