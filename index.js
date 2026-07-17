require('dotenv').config();
const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const PORT = process.env.PORT || 3000;

// ==================== CONFIG (now from environment) ====================
const ACCESS_TOKEN = process.env.WHATSAPP_ACCESS_TOKEN;
const PHONE_NUMBER_ID = process.env.WHATSAPP_PHONE_NUMBER_ID;
const VERIFY_TOKEN = process.env.WHATSAPP_VERIFY_TOKEN || 'mymobi_test_123';

if (!ACCESS_TOKEN || !PHONE_NUMBER_ID) {
  console.warn('⚠️  WHATSAPP_ACCESS_TOKEN or WHATSAPP_PHONE_NUMBER_ID is not set. Create a .env file (see .env.example).');
}

app.use(bodyParser.json());

const userSessions = {};

// ==================== REGISTERED USERS STORAGE ====================
const registeredUsers = {};   // Key = WhatsApp number (from), Value = user data

// ==================== MESSAGE DEDUPLICATION ====================
// WhatsApp/Meta will re-send (retry) a webhook call if your server doesn't
// respond fast enough, or after certain network hiccups. Without tracking
// which message IDs have already been handled, a single retry causes the
// whole reply flow to run twice (or more) in rapid succession — which is
// exactly what trips WhatsApp's (#131056) "pair rate limit" error, since
// it looks like you're firing several messages at the same recipient in
// a very short window. Every incoming WhatsApp message has a unique `id`;
// we remember IDs we've already processed and skip duplicates.
const processedMessageIds = new Map(); // messageId -> processedAtTimestamp
const MESSAGE_ID_TTL_MS = 10 * 60 * 1000; // keep IDs for 10 minutes, then forget them

function isDuplicateMessage(messageId) {
  if (!messageId) return false; // can't dedupe without an id; let it through

  const now = Date.now();

  if (processedMessageIds.has(messageId)) {
    return true; // already handled this exact message — it's a retry
  }

  processedMessageIds.set(messageId, now);

  // Light cleanup so this map doesn't grow forever
  for (const [id, ts] of processedMessageIds) {
    if (now - ts > MESSAGE_ID_TTL_MS) {
      processedMessageIds.delete(id);
    }
  }

  return false;
}

// 60-second inactivity timeout
function resetTimeout(from) {
  if (userSessions[from] && userSessions[from].timeoutId) {
    clearTimeout(userSessions[from].timeoutId);
  }

  userSessions[from].timeoutId = setTimeout(() => {
    // Delete the session first (more reliable)
    delete userSessions[from];

    // Then send the timeout message (non-blocking)
    sendTextMessage(from, "⏰ Your session has timed out due to inactivity.").catch(() => {
      // Ignore errors when sending timeout message
    });
  }, 60000); // 60 seconds
}

app.get('/webhook', (req, res) => {
  if (req.query['hub.mode'] === 'subscribe' && req.query['hub.verify_token'] === VERIFY_TOKEN) {
    res.send(req.query['hub.challenge']);
  } else {
    res.sendStatus(403);
  }
});

// ==================== BUG FIX #1 ====================
// Original code did `return;` inside the try block after sendWelcome(),
// which skipped res.sendStatus(200) entirely. That left Meta's webhook
// call hanging until it timed out and retried, causing duplicate
// welcome messages. Fix: never return early past the response — always
// fall through to res.sendStatus(200).
app.post('/webhook', async (req, res) => {
  try {
    const message = req.body.entry?.[0]?.changes?.[0]?.value?.messages?.[0];
    if (!message) return res.sendStatus(200);

    // BUG FIX #4: ignore retried/duplicate webhook deliveries for a
    // message we've already handled — see MESSAGE DEDUPLICATION above.
    if (isDuplicateMessage(message.id)) {
      return res.sendStatus(200);
    }

    const from = message.from;
    const text = message.text?.body || '';
    const lowerText = text.toLowerCase().trim();
    const isTriggerWord = ['hi', 'hello', 'loan', 'start'].includes(lowerText) || lowerText.includes('531');
    const buttonId = message.interactive?.button_reply?.id || message.interactive?.list_reply?.id;

    // Create new session if it doesn't exist
    if (!userSessions[from]) {
      userSessions[from] = { step: 'welcome', isNewSession: true };
    }

    resetTimeout(from);
    const session = userSessions[from];

    // BUG FIX #4 (continued): debounce guard now covers BOTH button taps
    // and typed text, not just text. Previously this only lived inside
    // handleTextInput(), so double-tapping a button had no protection.
    if (session.lastProcessed && (Date.now() - session.lastProcessed < 800)) {
      return res.sendStatus(200);
    }
    session.lastProcessed = Date.now();

    // Only show Welcome page once per fresh session
    if (isTriggerWord && session.step === 'welcome' && session.isNewSession === true) {
      await sendWelcome(from);
      session.isNewSession = false;   // Prevent it from showing again
    } else if (buttonId) {
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
            failedPinAttempts: 0
        };
        console.log(`User registered: ${session.mobileNumber}`);
    }

    // Clear sensitive/one-time session data up front, so even if a send
    // below fails, the session isn't left dangling on "confirm_new_pin"
    // waiting to time out.
    if (userSessions[to]) {
        delete userSessions[to].otp;
        delete userSessions[to].newPin;
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
}

// ==================== BUG FIX #2 (new function) ====================
// Forgot-PIN previously reused sendRegistrationComplete(), which only
// writes a PIN when session.mobileNumber is set. Returning users never
// go through the mobile-number collection step, so that guard silently
// failed and the PIN was never updated in registeredUsers — the user
// stayed locked out with their old PIN, while seeing a "Registration
// Complete" message that made no sense for a PIN reset.
async function sendPinResetComplete(to, session) {
  const user = registeredUsers[to];
  if (user && session.newPin) {
    user.pin = session.newPin;
    user.failedPinAttempts = 0;
    user.status = "active";
    console.log(`PIN reset for user: ${to}`);
  }

  if (userSessions[to]) {
    delete userSessions[to].otp;
    delete userSessions[to].newPin;
    delete userSessions[to].isPinReset;
  }

  await sendTextMessage(to,
    "✅ Your PIN has been updated successfully.\n\n" +
    "🔒 Do not share this PIN with anyone."
  );
  await sendMainMenu(to);
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

// ==================== BUG FIX #3 ====================
// Original: id.replace("edit_", "").replace("_", " ") mangled labels
// like "edit_nationalid" -> "nationalid" (no underscore to replace).
// Fix: explicit lookup map so every field gets a proper display name.
const EDIT_FIELD_LABELS = {
  edit_firstname: "First Name",
  edit_lastname: "Last Name",
  edit_upn: "UPN",
  edit_nationalid: "National ID",
  edit_mobilenumber: "Mobile Number (Mpesa)"
};

// ==================== HANDLERS ====================

async function handleButton(to, id, session) {
  if (id === "civil_servants") {
    const user = registeredUsers[to];

    if (user && user.status === "blocked") {
      await sendTextMessage(to, "Your account is blocked. Please contact Customer Care for assistance on WhatsApp 0758 035 381");
      return;
    }

    if (user && user.status === "active") {
      // Returning user - show authentication options
      session.step = "auth_menu";
      await sendAuthMenu(to);
    } else {
      // New user or opted out - start registration
      session.step = "optin";
      await sendOptIn(to);
    }
  }
  // ==================== AUTHENTICATION MENU (Returning Users) ====================
  else if (id === "enter_pin") {
    session.step = "enter_pin";
    await sendTextMessage(to, "Enter your PIN:");
  }
  else if (id === "forgot_pin") {
    session.step = "forgot_pin";
    session.isPinReset = true; // marks this as a reset flow, not fresh registration
    session.otp = "67890"; // simulated OTP, different from registration OTP
    session.otpAttempts = 0;
    await sendTextMessage(to, "A new OTP has been sent to your registered mobile number.\n\nPlease enter the OTP:");
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
    const fieldName = EDIT_FIELD_LABELS[id] || "field";
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
  else {
    // Fallback for unrecognized button ids so users never get silence
    await sendTextMessage(to, "Sorry, I didn't understand that option. Returning to the main menu.");
    await sendWelcome(to);
  }
}

async function handleTextInput(to, text, session) {
  const cleanText = text.trim();
  const step = session.step;

  // =====================================================
  // KYC DATA COLLECTION (with strict guard)
  // =====================================================
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

  // =====================================================
  // EDIT FLOW
  // =====================================================
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

  // =====================================================
  // REGISTRATION: OTP + PIN SETUP
  // =====================================================
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
      // BUG FIX #2: route to the correct completion handler depending on
      // whether this is a fresh registration or a forgot-PIN reset.
      if (session.isPinReset) {
        await sendPinResetComplete(to, session);
      } else {
        await sendRegistrationComplete(to, session);
      }
    } else {
      await sendTextMessage(to, "The PINs do not match. Please enter your new 5-digit PIN again:");
      session.step = "enter_new_pin";
    }
    return;
  }

  // =====================================================
  // RETURNING USER: ENTER PIN + VERIFICATION CODE
  // =====================================================
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

  // =====================================================
  // FORGOT PIN
  // =====================================================
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

  // =====================================================
  // OPT OUT
  // =====================================================
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

  // Fallback for unrecognized step values so users never get silence
  await sendTextMessage(to, "Sorry, something went wrong. Let's start over.");
  await sendWelcome(to);
  delete userSessions[to];
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

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// ==================== OUTBOUND MESSAGE QUEUE (BUG FIX #5, rebuilt) ====================
// WhatsApp enforces a per-(business number, recipient) throttle. If two
// messages to the same person land too close together — even a hand-tuned
// 1.5s gap inserted at one call site — it can still trip error 131056,
// especially once several messages have already gone out in the same
// short test session, and it's easy to miss a call site when delays are
// scattered by hand through the code (as happened above).
//
// Instead, every outbound message is funneled through ONE queue per
// recipient. The queue:
//   1. Sends messages to a given recipient strictly one at a time.
//   2. Enforces a minimum gap since the last successful send to that
//      recipient, enforced centrally — no per-call-site sleep() needed.
//   3. Automatically retries with exponential backoff specifically when
//      WhatsApp responds with a rate-limit error (131056 or 130429),
//      instead of silently dropping the message and leaving the session
//      stuck (which is what caused "Registration Complete" to vanish).
//
// This is the single place that governs message pacing — nowhere else
// in the code should call axios directly or add its own delays.

const MIN_GAP_MS = 3000;          // minimum spacing between messages to the same recipient
const MAX_SEND_RETRIES = 4;
const BASE_RETRY_DELAY_MS = 4000; // doubles each retry: 4s, 8s, 16s, 32s

const recipientQueues = new Map(); // "to" phone number -> { tail: Promise, lastSentAt: number }

// Prevent recipientQueues from growing forever on a long-running server —
// numbers that haven't messaged in a while are safe to forget, since a
// fresh entry is created automatically the next time they do.
const QUEUE_ENTRY_TTL_MS = 60 * 60 * 1000; // 1 hour
setInterval(() => {
  const now = Date.now();
  for (const [to, state] of recipientQueues) {
    if (now - state.lastSentAt > QUEUE_ENTRY_TTL_MS) {
      recipientQueues.delete(to);
    }
  }
}, 15 * 60 * 1000); // sweep every 15 minutes

function sendMessage(to, payload) {
  const state = recipientQueues.get(to) || { tail: Promise.resolve(), lastSentAt: 0 };

  const task = state.tail
    .catch(() => {}) // never let a prior failure break the chain for this recipient
    .then(async () => {
      const elapsed = Date.now() - state.lastSentAt;
      if (elapsed < MIN_GAP_MS) {
        await sleep(MIN_GAP_MS - elapsed);
      }
      await sendWithRetry(to, payload);
      state.lastSentAt = Date.now();
    });

  state.tail = task;
  recipientQueues.set(to, state);
  return task;
}

async function sendWithRetry(to, payload, attempt = 0) {
  try {
    await axios.post(`https://graph.facebook.com/v20.0/${PHONE_NUMBER_ID}/messages`, payload, {
      headers: { Authorization: `Bearer ${ACCESS_TOKEN}` }
    });
  } catch (err) {
    const errorCode = err.response?.data?.error?.code;
    const isRateLimitError = errorCode === 131056 || errorCode === 130429;

    if (isRateLimitError && attempt < MAX_SEND_RETRIES) {
      const delay = BASE_RETRY_DELAY_MS * Math.pow(2, attempt);
      console.warn(`Rate limited sending to ${to} (code ${errorCode}). Retrying in ${delay}ms (attempt ${attempt + 1}/${MAX_SEND_RETRIES})`);
      await sleep(delay);
      return sendWithRetry(to, payload, attempt + 1);
    }

    console.error("Send failed:", err.response?.data || err.message);
    // Deliberately not re-thrown: a failed send (after retries) shouldn't
    // crash the webhook handler. Session state for this bot is already
    // updated in memory before messages are sent (see
    // sendRegistrationComplete / sendPinResetComplete), so a delivery
    // failure doesn't strand a user in an inconsistent step.
  }
}

app.listen(PORT, () => console.log(`Server started on port ${PORT}`));
