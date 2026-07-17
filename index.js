const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const PORT = process.env.PORT || 3000;

const ACCESS_TOKEN = 'EAAOxVVXxgvUBR7ZAnegjAq5UZCfeYqEkhevYGOEhXhLjkV6hIFdyB7uLI7mrAyvHHZBdXBEfZBnbwOLAfqJK2zryzGKKGNvtfpGLFi3QO054qhkVo8f9mYP4KIG5a0ZALX6ZABltcWEJQSsHE7Lc307OZCyNAARzQ0IdcLpy30FrpRI6OpFeZBFZCfHFkSIhOTHpQDgZDZD';
const PHONE_NUMBER_ID = '1265967949926220';
const VERIFY_TOKEN = 'mymobi_test_123';

app.use(bodyParser.json());

const userSessions = {};
const registeredUsers = {};

// 60-second inactivity timeout
function resetTimeout(from) {
  if (userSessions[from] && userSessions[from].timeoutId) {
    clearTimeout(userSessions[from].timeoutId);
  }

  userSessions[from].timeoutId = setTimeout(() => {
    delete userSessions[from];
    sendTextMessage(from, "⏰ Your session has timed out due to inactivity.").catch(() => {});
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
    const text = message.text?.body || '';
    const lowerText = text.toLowerCase().trim();
    const isTriggerWord = ['hi', 'hello', 'loan', 'start'].includes(lowerText) || lowerText.includes('531');
    const buttonId = message.interactive?.button_reply?.id || message.interactive?.list_reply?.id;

    if (!userSessions[from]) {
      userSessions[from] = { step: 'welcome', isNewSession: true };
    }

    resetTimeout(from);
    const session = userSessions[from];

    if (isTriggerWord && session.step === 'welcome' && session.isNewSession === true) {
      await sendWelcome(from);
      session.isNewSession = false;
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
      footer: { text: "MyMobi" },
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

async function sendAuthMenu(to) {
  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "button",
      body: { text: "Welcome back! Please choose an option:" },
      action: {
        buttons: [
          { type: "reply", reply: { id: "enter_pin", title: "Enter PIN" } },
          { type: "reply", reply: { id: "forgot_pin", title: "Forgot PIN" } },
          { type: "reply", reply: { id: "opt_out", title: "Opt Out" } }
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

async function sendEnterNewPIN(to) {
  await sendTextMessage(to, "Create a new 5-digit PIN for your account.\n\nDo not share this PIN with anyone.");
}

async function sendConfirmNewPIN(to) {
  await sendTextMessage(to, "Please re-enter your new 5-digit PIN to confirm.");
}

async function sendRegistrationComplete(to, session) {
  if (session && session.mobileNumber && session.newPin) {
    registeredUsers[to] = {
      mobileNumber: session.mobileNumber,
      pin: session.newPin,
      status: "active",
      failedPinAttempts: 0,
      loans: []
    };
    console.log(`User registered: ${session.mobileNumber}`);
  }

  await sendTextMessage(to, 
    "🎉 Registration Complete!\n\n" +
    "Your account has been successfully set up.\n\n" +
    "🔒 Security Notice:\n" +
    "• Your PIN is now active\n" +
    "• Do not share this PIN with anyone\n" +
    "• For your protection, we strongly recommend deleting this chat or the messages containing your PIN\n" +
    "• You can change your PIN later from the app settings"
  );

  await sendMainMenu(to);

  if (userSessions[to]) {
    delete userSessions[to].otp;
    delete userSessions[to].newPin;
  }
}

async function sendMainMenu(to) {
  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "list",
      header: { type: "text", text: "Main Menu" },
      body: { text: "What would you like to do?" },
      footer: { text: "MyMobi" },
      action: {
        button: "Select Option",
        sections: [{
          title: "Options",
          rows: [
            { id: "emergency_loan", title: "Emergency Loan", description: "Apply for emergency loan" },
            { id: "get_payslip", title: "Get Payslip", description: "Download your payslip" },
            { id: "back", title: "Back", description: "Go back" },
            { id: "home", title: "Home", description: "Return to home" },
            { id: "logout", title: "Logout", description: "Log out of the app" }
          ]
        }]
      }
    }
  };
  await sendMessage(to, payload);
}

// ==================== HANDLERS ====================

async function handleButton(to, id, session) {
  if (id === "civil_servants") {
    const user = registeredUsers[to];

    if (user && user.status === "blocked") {
      await sendTextMessage(to, "Your account is blocked. Please contact Customer Care for assistance on WhatsApp 0758 035 381");
      return;
    }

    if (user && user.status === "active") {
      session.step = "auth_menu";
      await sendAuthMenu(to);
    } else {
      session.step = "optin";
      await sendOptIn(to);
    }
  }
  else if (id === "enter_pin") {
    session.step = "enter_pin";
    await sendTextMessage(to, "Enter your PIN:");
  }
  else if (id === "forgot_pin") {
    session.step = "forgot_pin";
    await sendTextMessage(to, "A new OTP has been sent to your registered mobile number.\n\nPlease enter the OTP:");
    session.otp = "67890";
  }
  else if (id === "opt_out") {
    session.step = "opt_out_confirmation";
    await sendTextMessage(to, "You are about to OPT OUT of Emergency Loan Services.\n\nDo you want to proceed? (Yes/No)");
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
    session.otp = "12345";
    session.otpAttempts = 0;
    session.step = "enter_otp";
    await sendTextMessage(to, "An OTP has been sent to your M-Pesa number.\n\nPlease enter the OTP:");
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

  // KYC DATA COLLECTION
  if (["first_name", "last_name", "upn", "national_id", "mobile_number"].includes(step)) {
    if (step === "first_name") {
      if (!cleanText) {
        await sendTextMessage(to, "Please enter your First Name.");
        return;
      }
      session.firstName = cleanText;
      session.step = "last_name";
      await sendTextMessage(to, "Enter your Last Name");
      return;
    }

    if (step === "last_name") {
      if (!cleanText) {
        await sendTextMessage(to, "Please enter your Last Name.");
        return;
      }
      session.lastName = cleanText;
      session.step = "upn";
      await sendTextMessage(to, "Enter UPN");
      return;
    }

    if (step === "upn") {
      if (!cleanText) {
        await sendTextMessage(to, "Please enter your UPN.");
        return;
      }
      session.upn = cleanText;
      session.step = "national_id";
      await sendTextMessage(to, "Enter National ID Number");
      return;
    }

    if (step === "national_id") {
      if (!cleanText) {
        await sendTextMessage(to, "Please enter your National ID Number.");
        return;
      }
      session.nationalId = cleanText;
      session.step = "mobile_number";
      await sendTextMessage(to, "Enter Mobile Number (Mpesa)");
      return;
    }

    if (step === "mobile_number") {
      if (!cleanText) {
        await sendTextMessage(to, "Please enter your Mobile Number (Mpesa).");
        return;
      }
      session.mobileNumber = cleanText;
      await sendConfirmation(to, session);
      return;
    }
  }

  // EDIT FLOW
  if (step.startsWith("edit_")) {
    if (!cleanText) {
      await sendTextMessage(to, "Please enter a valid value.");
      return;
    }

    const field = step.replace("edit_", "");
    if (field === "firstname") session.firstName = cleanText;
    if (field === "lastname") session.lastName = cleanText;
    if (field === "upn") session.upn = cleanText;
    if (field === "nationalid") session.nationalId = cleanText;
    if (field === "mobilenumber") session.mobileNumber = cleanText;

    await sendConfirmation(to, session);
    return;
  }

  // REGISTRATION: OTP + PIN SETUP
  if (step === "enter_otp") {
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
        await sendTextMessage(to, "Too many incorrect attempts. Your PIN has been deactivated. Please try again after 30 minutes.");
        delete userSessions[to];
      } else {
        await sendTextMessage(to, `Incorrect OTP. You have ${3 - session.otpAttempts} attempt(s) remaining.`);
      }
    }
    return;
  }

  if (step === "enter_new_pin") {
    if (!/^\d{5}$/.test(cleanText)) {
      await sendTextMessage(to, "Invalid PIN. Please enter exactly 5 digits.");
      return;
    }

    if (cleanText === session.otp) {
      await sendTextMessage(to, "Your new PIN cannot be the same as the OTP. Please choose a different 5-digit PIN.");
      return;
    }

    session.newPin = cleanText;
    session.step = "confirm_new_pin";
    await sendConfirmNewPIN(to);
    return;
  }

  if (step === "confirm_new_pin") {
    if (cleanText === session.newPin) {
      await sendRegistrationComplete(to, session);
    } else {
      await sendTextMessage(to, "The PINs do not match. Please enter your new 5-digit PIN again:");
      session.step = "enter_new_pin";
    }
    return;
  }

  // RETURNING USER: ENTER PIN + VERIFICATION CODE
  if (step === "enter_pin") {
    if (!cleanText) {
      await sendTextMessage(to, "Please enter your 5-digit PIN.");
      return;
    }

    if (!/^\d{5}$/.test(cleanText)) {
      await sendTextMessage(to, "Invalid PIN. Please enter exactly 5 digits.");
      return;
    }

    const user = registeredUsers[to];

    if (!user) {
      await sendTextMessage(to, "User not found. Please register first.");
      return;
    }

    if (user.status === "blocked") {
      await sendTextMessage(to, "Your account is blocked. Please contact Customer Care for assistance on WhatsApp 0758 035 381");
      return;
    }

    if (cleanText === user.pin) {
      user.failedPinAttempts = 0;
      session.step = "enter_verification_code";
      await sendTextMessage(to, "Enter Verification Code:");
    } else {
      user.failedPinAttempts = (user.failedPinAttempts || 0) + 1;

      if (user.failedPinAttempts >= 3) {
        user.status = "blocked";
        await sendTextMessage(to, "Your account is blocked. Please contact Customer Care for assistance on WhatsApp 0758 035 381");
      } else {
        const attemptsLeft = 3 - user.failedPinAttempts;
        await sendTextMessage(to, `Incorrect PIN. You have ${attemptsLeft} attempt(s) remaining.`);
      }
    }
    return;
  }

  if (step === "enter_verification_code") {
    const verificationCode = "67890";

    if (!/^\d{5}$/.test(cleanText)) {
      await sendTextMessage(to, "Invalid code. Please enter a 5-digit verification code.");
      return;
    }

    if (cleanText === verificationCode) {
      await sendTextMessage(to, "Verification successful!");
      await sendMainMenu(to);
    } else {
      session.verificationAttempts = (session.verificationAttempts || 0) + 1;

      if (session.verificationAttempts >= 3) {
        await sendTextMessage(to, "Too many incorrect attempts. Please start again.");
        session.step = "enter_pin";
        await sendTextMessage(to, "Enter your 5-digit PIN:");
      } else {
        const attemptsLeft = 3 - session.verificationAttempts;
        await sendTextMessage(to, `Incorrect code. You have ${attemptsLeft} attempt(s) remaining.`);
      }
    }
    return;
  }

  // FORGOT PIN
  if (step === "forgot_pin") {
    if (!/^\d{5}$/.test(cleanText)) {
      await sendTextMessage(to, "Invalid OTP. Please enter a 5-digit OTP.");
      return;
    }

    if (cleanText === session.otp) {
      session.step = "enter_new_pin";
      await sendTextMessage(to, "OTP verified. Please create a new 5-digit PIN:");
    } else {
      await sendTextMessage(to, "Incorrect OTP. Please try again.");
    }
    return;
  }

  // OPT OUT
  if (step === "opt_out_confirmation") {
    const response = cleanText.toLowerCase();

    if (response === "yes" || response === "y") {
      session.step = "opt_out_pin";
      await sendTextMessage(to, "To confirm opt out, please enter your 5-digit PIN:");
    } else if (response === "no" || response === "n") {
      await sendTextMessage(to, "Opt out cancelled.");
      await sendAuthMenu(to);
    } else {
      await sendTextMessage(to, "Please reply with Yes or No.");
    }
    return;
  }

  if (step === "opt_out_pin") {
    const user = registeredUsers[to];

    if (!user) {
      await sendTextMessage(to, "User not found.");
      return;
    }

    if (cleanText === user.pin) {
      user.status = "opted_out";
      delete user.pin;
      await sendTextMessage(to, "You have been successfully opted out of the Emergency Loan service.");
    } else {
      await sendTextMessage(to, "Incorrect PIN. Opt out cancelled.");
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

app.listen(PORT, () => console.log(`Server started on port ${PORT}`));
