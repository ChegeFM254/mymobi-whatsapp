const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const PORT = process.env.PORT || 3000;

const ACCESS_TOKEN = 'EAAOxVVXxgvUBRzs2TqOGnIyfTz03eQz8zMnAs1Kxm5Nygi33myDBtHKqDbgKnJPGCTQlt6ZCynDntZBaZCsb9hJVxgYmkaNEzYWF0XSTIfgEyQiWDxTRi1iV6wmmrIZCafZB8h8iQdrEV2rbDgbrWBU403L5ZCPKXdv1JbE4shp3ecFj18ddp0pALhQES3AQZDZD';
const PHONE_NUMBER_ID = '1265967949926220';
const VERIFY_TOKEN = 'mymobi_test_123';

app.use(bodyParser.json());

const userSessions = {};

// 60-second inactivity timeout
function resetTimeout(from) {
  if (userSessions[from] && userSessions[from].timeoutId) {
    clearTimeout(userSessions[from].timeoutId);
  }

  userSessions[from].timeoutId = setTimeout(async () => {
    await sendTextMessage(from, "⏰ Your session has timed out due to inactivity.");
    await sendWelcome(from);
    delete userSessions[from];
  }, 60000);
}

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

    resetTimeout(from);

    const session = userSessions[from];
    const buttonId = message.interactive?.button_reply?.id || message.interactive?.list_reply?.id;
    const text = message.text?.body || '';

    const lowerText = text.toLowerCase().trim();
    const isTriggerWord = ['hi', 'hello', 'loan', 'start'].includes(lowerText) || lowerText.includes('531');

    if (isTriggerWord && (session.step === 'welcome' || !session.step)) {
      await sendWelcome(from);
      return;
    }

    if (buttonId) {
      await handleButton(from, buttonId, session);
    } else if (text) {
      await handleTextInput(from, text, session);
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
  const details = 
`Confirm Details:

First Name: ${session.firstName || ''}
Last Name: ${session.lastName || ''}
UPN: ${session.upn || ''}
National ID: ${session.nationalId || ''}
Mobile Number (Mpesa): ${session.mobileNumber || ''}

Is this correct?`;

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
      type: "list",
      header: { type: "text", text: "Edit Details" },
      body: { text: "Which field would you like to edit?" },
      footer: { text: "MyMobi" },
      action: {
        button: "Select Field",
        sections: [{
          title: "Available Fields",
          rows: [
            { id: "edit_firstname", title: "First Name", description: "Update your first name" },
            { id: "edit_lastname", title: "Last Name", description: "Update your last name" },
            { id: "edit_upn", title: "UPN", description: "Update your UPN" },
            { id: "edit_nationalid", title: "National ID", description: "Update your National ID" },
            { id: "edit_mobilenumber", title: "Mobile Number (Mpesa)", description: "Update your M-Pesa number" },
            { id: "exit_edit", title: "Exit", description: "Return to Confirm Details" }
          ]
        }]
      }
    }
  };
  await sendMessage(to, payload);
}

async function sendSuccess(to) {
  await sendTextMessage(to, "✅ Registration Data Received\n\nThank you. Your details have been received and are being processed. You will be notified of the outcome shortly.");

  setTimeout(async () => {
    await sendWelcome(to);
    delete userSessions[to];
  }, 5000);
}

// ==================== HANDLERS ====================

async function handleButton(to, id, session) {
  if (id === "civil_servants") {
    session.step = "optin";
    await sendOptIn(to);
  } 
  else if (id === "optin_yes") {
    session.step = "tc";
    await sendTerms(to);
  } 
  else if (id === "optin_no") {
    await sendWelcome(to);
  } 
  else if (id === "accept_tc") {
    session.step = "first_name";
    await sendTextMessage(to, "Enter your First Name");
  } 
  else if (id === "decline_tc") {
    await sendWelcome(to);
  } 
  else if (id === "confirm_details") {
    await sendSuccess(to);
  } 
  else if (id === "edit_details") {
    await sendEditOptions(to);
  } 
  else if (id === "exit_edit") {
    await sendConfirmation(to, session);
  } 
  else if (id.startsWith("edit_")) {
    session.step = id;
    let fieldName = id.replace("edit_", "").replace("_", " ");
    if (fieldName === "mobilenumber") fieldName = "Mobile Number (Mpesa)";
    await sendTextMessage(to, `Enter new ${fieldName}:`);
  }
// ==================== NEW: Confirm Details + Main Menu ====================

  else if (id === "confirm_details") {
    await triggerOTPAndShowEnterOTPScreen(to, session);
  } 
  else if (id === "exit_edit") {
    await sendConfirmation(to, session);
  } 
  // Main Menu buttons (after PIN setup)
  else if (id === "emergency_loan") {
    await sendTextMessage(to, "You selected Emergency Loan. (Feature coming soon)");
  } 
  else if (id === "get_payslip") {
    await sendTextMessage(to, "You selected Get Payslip. (Feature coming soon)");
  } 
  else if (id === "back" || id === "home") {
    await sendWelcome(to);
  } 
  else if (id === "logout") {
    await sendTextMessage(to, "You have been logged out.");
    delete userSessions[to];
  }
  
}

async function handleTextInput(to, text, session) {
  const cleanText = text.trim();
  const step = session.step;

  if (step === "first_name") {
    session.firstName = cleanText;
    session.step = "last_name";
    await sendTextMessage(to, "Enter your Last Name");
    return;
  }

  if (step === "last_name") {
    session.lastName = cleanText;
    session.step = "upn";
    await sendTextMessage(to, "Enter UPN");
    return;
  }

  if (step === "upn") {
    session.upn = cleanText;
    session.step = "national_id";
    await sendTextMessage(to, "Enter National ID Number");
    return;
  }

  if (step === "national_id") {
    session.nationalId = cleanText;
    session.step = "mobile_number";
    await sendTextMessage(to, "Enter Mobile Number (Mpesa)");
    return;
  }

  if (step === "mobile_number") {
    session.mobileNumber = cleanText;
    await sendConfirmation(to, session);
    return;
  }

  if (step.startsWith("edit_")) {
    const field = step.replace("edit_", "");
    if (field === "firstname") session.firstName = cleanText;
    if (field === "lastname") session.lastName = cleanText;
    if (field === "upn") session.upn = cleanText;
    if (field === "nationalid") session.nationalId = cleanText;
    if (field === "mobilenumber") session.mobileNumber = cleanText;

    await sendConfirmation(to, session);
  }
    // ==================== NEW: OTP & PIN HANDLING ====================
  if (step === "enter_otp") {
    const cleanText = text.trim();

    if (!/^\d{5}$/.test(cleanText)) {
      await sendTextMessage(to, "Invalid OTP. Please enter a 5-digit number.");
      return;
    }

    if (cleanText === session.otp) {
      session.step = "enter_new_pin";
      await sendEnterNewPIN(to);
    } else {
      session.otpAttempts = (session.otpAttempts || 0) + 1;

      if (session.otpAttempts >= 3) {
        await sendTextMessage(to, "PIN Deactivated. Please try again after 30 minutes.");
        delete userSessions[to];
      } else {
        await sendTextMessage(to, `Incorrect PIN. You have ${3 - session.otpAttempts} attempt(s) remaining.`);
      }
    }
    return;
  }

  if (step === "enter_new_pin") {
    const cleanText = text.trim();

    if (!/^\d{5}$/.test(cleanText)) {
      await sendTextMessage(to, "Invalid PIN. Please enter exactly 5 digits.");
      return;
    }

    session.newPin = cleanText;
    session.step = "confirm_new_pin";
    await sendConfirmNewPIN(to);
    return;
  }

  if (step === "confirm_new_pin") {
    if (text.trim() === session.newPin) {
      await sendRegistrationComplete(to);
      delete userSessions[to];
    } else {
      await sendTextMessage(to, "PINs do not match. Please enter your new 5-digit PIN again:");
      session.step = "enter_new_pin";
    }
    return;
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

// ==================== NEW: OTP & PIN HANDLING ====================

async function triggerOTPAndShowEnterOTPScreen(to, session) {
    // TODO: Call your backend API to verify data and send OTP
    session.otp = "12345";           // Replace this with real OTP from backend
    session.otpAttempts = 0;
    session.step = "enter_otp";

    await sendTextMessage(to, "A 5-digit OTP has been sent to your M-Pesa number.\n\nPlease enter the OTP:");
}

async function sendEnterNewPIN(to) {
    await sendTextMessage(to, "Enter your new 5-digit PIN:");
}

async function sendConfirmNewPIN(to) {
    await sendTextMessage(to, "Confirm your new 5-digit PIN:");
}

async function sendRegistrationComplete(to) {
    await sendTextMessage(to, "🎉 Registration Complete!\n\nYour account has been successfully set up.");

    setTimeout(async () => {
        await sendMainMenu(to);
    }, 1500);
}

async function sendMainMenu(to) {
    const payload = {
        messaging_product: "whatsapp",
        to: to,
        type: "interactive",
        interactive: {
            type: "button",
            body: { text: "What would you like to do?" },
            action: {
                buttons: [
                    { type: "reply", reply: { id: "emergency_loan", title: "Emergency Loan" } },
                    { type: "reply", reply: { id: "get_payslip", title: "Get Payslip" } },
                    { type: "reply", reply: { id: "back", title: "Back" } },
                    { type: "reply", reply: { id: "home", title: "Home" } },
                    { type: "reply", reply: { id: "logout", title: "Logout" } }
                ]
            }
        }
    };
    await sendMessage(to, payload);
}

app.listen(PORT, () => console.log(`Server started on port ${PORT}`));
