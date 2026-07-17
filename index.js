require('dotenv').config();
const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');

// ============================================================================
// STANDING CONVENTION — READ BEFORE ADDING ANY NEW SCREEN
// ============================================================================
// WhatsApp's interactive "button" message type supports a MAXIMUM of 3
// buttons. Any screen that needs 4+ options (Accept/Decline/Back/Home/
// Logout, for example) MUST use the interactive "list" type instead —
// this is what every multi-option menu in this file already does
// (sendMainMenu, sendEmergencyLoanMenu, sendLoanTenureOptions,
// sendLoanAmountMenu, sendLoanBreakdown, sendEditOptions).
//
// Rule of thumb when adding a new screen:
//   - 2-3 short actions (e.g. Accept/Decline)        -> type: "button"
//   - Anything with Back/Home/Logout, or 4+ options   -> type: "list"
//
// Navigable list menus should also register themselves with the Back
// navigation system — see MENU_BACK_MAP further down this file.
// ============================================================================

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

// ==================== EMERGENCY LOAN ====================
const loanApplications = {}; // Key = WhatsApp number, Value = array of ALL submitted loan applications (audit trail)

// The user's CURRENT loan (drives which options the Emergency Loan menu
// shows). Only one active loan per user is supported, matching the
// product spec ("no purpose in displaying Apply Loan and Pay Loan menus"
// while an application is pending). Absence of an entry (or status
// "paid"/"cancelled") means the user is free to apply for a new loan.
//
// status lifecycle: "pending_approval" -> "approved" -> "paid"
//                                       -> "cancelled" (from pending_approval only)
const currentLoans = {}; // Key = WhatsApp number, Value = current loan record

// Tenure options shown when a user starts a loan application. `limit` is
// the maximum loan amount allowed for that repayment period, per the
// product spec document.
const LOAN_TENURE_OPTIONS = {
  tenure_1: { months: 1, limit: 20000, label: "1 Month" },
  tenure_2: { months: 2, limit: 40000, label: "2 Months" },
  tenure_3: { months: 3, limit: 60000, label: "3 Months" }
};

// TODO: replace with a real call to the loan calculation backend once it
// exists. For now this returns fixed placeholder figures taken directly
// from the product spec document for upfrontFee/disbursement/monthlyInstallment
// — they do NOT scale with the amount or tenure entered by the user.
// platformFee is the one figure that IS a confirmed rule (KES 150 per
// month of tenure — 1mo=150, 2mo=300, 3mo=450), so it's calculated rather
// than hardcoded. Kept as an async function so swapping in a real API
// call later (e.g. axios.post to a loans microservice) requires no
// changes at any call site.
const PLATFORM_FEE_PER_MONTH = 150;

async function getLoanBreakdown(loanAmount, tenureMonths) {
  return {
    loanAmount: loanAmount,
    upfrontFee: 2943,
    disbursement: 32057,
    monthlyInstallment: 14442,
    platformFee: PLATFORM_FEE_PER_MONTH * tenureMonths
  };
}

// TODO: replace with real backend-issued values once the loan system
// exists. For now these are simulated locally so the Approve Loan flow
// can be built and tested end-to-end.
function generateLoanRefNo() {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  let ref = "MH";
  for (let i = 0; i < 6; i++) {
    ref += chars[Math.floor(Math.random() * chars.length)];
  }
  return ref;
}

function generateApprovalCode() {
  return String(Math.floor(100000 + Math.random() * 900000)); // 6 digits
}

function computeDueDate(tenureMonths) {
  const due = new Date();
  due.setMonth(due.getMonth() + tenureMonths);
  return due.toISOString().split('T')[0]; // YYYY-MM-DD
}

// TODO: replace with a real Safaricom Daraja API STK Push integration
// once it's available. For now, payment is simulated as immediately
// successful so the Pay Loan flow can be built and tested end-to-end
// ahead of the real payments backend. Kept as an async function
// returning a result object so swapping in the real API call later
// requires no changes at the call site.
async function triggerMpesaStkPush(to, amount) {
  console.log(`[SIMULATED] M-Pesa STK Push triggered for ${to}: KES ${amount}`);
  return { success: true };
}


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
        "• For your protection, we strongly recommend deleting this chat or the messages containing your PIN"
    );
  await sendMainMenu(to, session);
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
  await sendMainMenu(to, session);
}

async function sendMainMenu(to, session) {
    if (session) session.currentMenu = "civil_servants_menu";
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

// ==================== EMERGENCY LOAN SCREENS ====================

async function sendEmergencyLoanMenu(to, session) {
  if (session) session.currentMenu = "emergency_loan_menu";

  const loan = currentLoans[to];
  let rows;

  if (loan && loan.status === "pending_approval") {
    // Application submitted, awaiting approval code entry. Apply Loan
    // and Pay Loan have no purpose here — per spec, only these options
    // are relevant until the pending application is resolved.
    rows = [
      { id: "approve_loan_menu", title: "Approve Loan", description: "Enter your approval code" },
      { id: "cancel_loan", title: "Cancel Loan", description: "Cancel this loan application" },
      { id: "back", title: "Back", description: "Go back" },
      { id: "home", title: "Home", description: "Return to home" },
      { id: "logout", title: "Logout", description: "Log out of the app" }
    ];
  } else if (loan && loan.status === "approved") {
    // Loan is approved/disbursed with an outstanding balance. Apply Loan
    // isn't relevant until this one is fully paid.
    rows = [
      { id: "pay_loan_menu", title: "Pay Loan", description: "Make an early repayment" },
      { id: "back", title: "Back", description: "Go back" },
      { id: "home", title: "Home", description: "Return to home" },
      { id: "logout", title: "Logout", description: "Log out of the app" }
    ];
  } else {
    // No loan, or previous one is fully paid/cancelled — free to apply.
    rows = [
      { id: "apply_loan", title: "Apply Loan", description: "Apply for an emergency loan" },
      { id: "back", title: "Back", description: "Go back" },
      { id: "home", title: "Home", description: "Return to home" },
      { id: "logout", title: "Logout", description: "Log out of the app" }
    ];
  }

  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "list",
      header: { type: "text", text: "Emergency Loan" },
      body: { text: "What would you like to do?" },
      footer: { text: "MyMobi" },
      action: {
        button: "Select Option",
        sections: [{ title: "Options", rows: rows }]
      }
    }
  };
  await sendMessage(to, payload);
}

async function sendLoanTenureOptions(to, session) {
  if (session) session.currentMenu = "loan_tenure_menu";
  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "list",
      header: { type: "text", text: "Apply Loan" },
      body: { text: "Select your repayment period:" },
      footer: { text: "MyMobi Emergency Loan" },
      action: {
        button: "Select Period",
        sections: [{
          title: "Repayment Period",
          rows: [
            { id: "tenure_1", title: LOAN_TENURE_OPTIONS.tenure_1.label, description: `Loan limit KES ${LOAN_TENURE_OPTIONS.tenure_1.limit.toLocaleString()}` },
            { id: "tenure_2", title: LOAN_TENURE_OPTIONS.tenure_2.label, description: `Loan limit KES ${LOAN_TENURE_OPTIONS.tenure_2.limit.toLocaleString()}` },
            { id: "tenure_3", title: LOAN_TENURE_OPTIONS.tenure_3.label, description: `Loan limit KES ${LOAN_TENURE_OPTIONS.tenure_3.limit.toLocaleString()}` },
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

async function sendLoanBreakdown(to, breakdown, session) {
  if (session) session.currentMenu = "loan_breakdown_menu";

  const details =
`Loan ${breakdown.loanAmount.toLocaleString()}
Upfront Fees ${breakdown.upfrontFee.toLocaleString()}
Disbursement ${breakdown.disbursement.toLocaleString()}
Monthly Installment ${breakdown.monthlyInstallment.toLocaleString()}
Platform Fee ${breakdown.platformFee.toLocaleString()}`;

  // WhatsApp's "button" interactive type supports a maximum of 3 buttons,
  // but this screen needs 5 options (Accept/Decline/Back/Home/Logout) —
  // so, like the other multi-option menus in this app, it uses "list"
  // instead.
  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "list",
      header: { type: "text", text: "Loan Breakdown" },
      body: { text: details },
      footer: { text: "MyMobi Emergency Loan" },
      action: {
        button: "Select Option",
        sections: [{
          title: "Options",
          rows: [
            { id: "accept_loan", title: "✅ Accept", description: "Confirm and proceed" },
            { id: "decline_loan", title: "Decline", description: "Cancel this loan application" },
            { id: "back", title: "Back", description: "Return to Enter Loan Amount menu" },
            { id: "home", title: "Home", description: "Return to home" },
            { id: "logout", title: "Logout", description: "Log out of the app" }
          ]
        }]
      }
    }
  };
  await sendMessage(to, payload);
}

// Shown after a tenure is selected (and as the "Back" target from the
// loan breakdown screen). Gives the user a proper menu with navigation
// options rather than dropping straight into free-text entry with no
// way back.
async function sendLoanAmountMenu(to, session) {
  if (session) session.currentMenu = "loan_amount_menu";

  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "list",
      header: { type: "text", text: "Apply Loan" },
      body: { text: `Loan limit: KES ${session.loanLimit.toLocaleString()} over ${session.loanTenureMonths} month${session.loanTenureMonths > 1 ? 's' : ''}.` },
      footer: { text: "MyMobi Emergency Loan" },
      action: {
        button: "Select Option",
        sections: [{
          title: "Options",
          rows: [
            { id: "start_loan_amount_entry", title: "Enter Loan Amount", description: "Type the amount you wish to borrow" },
            { id: "back", title: "Back", description: "Select a different repayment period" },
            { id: "home", title: "Home", description: "Return to home" }
          ]
        }]
      }
    }
  };
  await sendMessage(to, payload);
}

// Shared prompt used both when "Enter Loan Amount" is selected from
// sendLoanAmountMenu, and internally wherever the free-text amount
// prompt needs to be (re)sent — keeps every call site in sync.
async function sendEnterLoanAmountPrompt(to, session) {
  session.step = "enter_loan_amount";
  await sendTextMessage(to, `Enter Loan Amount (e.g., 35000). Your limit is KES ${session.loanLimit.toLocaleString()}:`);
}

// ==================== APPROVE LOAN SCREENS ====================

async function sendApproveLoanDetails(to, session) {
  if (session) session.currentMenu = "approve_loan_details_menu";

  const loan = currentLoans[to];
  const details = `You are about to approve ${loan.tenureMonths}-month loan of KES ${loan.loanAmount.toLocaleString()} Ref. No. ${loan.refNo} payable on ${loan.dueDate}`;

  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "list",
      header: { type: "text", text: "Approve Loan" },
      body: { text: details },
      footer: { text: "MyMobi Emergency Loan" },
      action: {
        button: "Select Option",
        sections: [{
          title: "Options",
          rows: [
            { id: "confirm_approve_loan", title: "Approve Loan", description: "Confirm and proceed" },
            { id: "cancel_loan", title: "Cancel Loan", description: "Cancel this loan application" },
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

// ==================== CANCEL LOAN SCREENS ====================

async function sendCancelLoanConfirm(to, session) {
  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "button",
      body: { text: "Are you sure you want to cancel your loan application?" },
      action: {
        buttons: [
          { type: "reply", reply: { id: "cancel_loan_yes", title: "Yes" } },
          { type: "reply", reply: { id: "cancel_loan_no", title: "No" } }
        ]
      }
    }
  };
  await sendMessage(to, payload);
}

// ==================== PAY LOAN SCREENS (EARLY REPAYMENT) ====================

async function sendPayLoanOptions(to, session) {
  if (session) session.currentMenu = "pay_loan_menu";

  const loan = currentLoans[to];
  const remainingInstallments = loan.tenureMonths - loan.installmentsPaid;

  const rows = [];
  for (let n = 1; n <= remainingInstallments; n++) {
    const amount = loan.breakdown.monthlyInstallment * n;
    rows.push({
      id: `pay_installments_${n}`,
      title: `${n} Installment${n > 1 ? 's' : ''}`,
      description: `KES ${amount.toLocaleString()}`
    });
  }
  rows.push({ id: "back", title: "Back", description: "Go back" });
  rows.push({ id: "home", title: "Home", description: "Return to home" });
  rows.push({ id: "logout", title: "Logout", description: "Log out of the app" });

  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "list",
      header: { type: "text", text: "Pay Loan" },
      body: { text: "Select how many installments you'd like to pay:" },
      footer: { text: "MyMobi Emergency Loan" },
      action: {
        button: "Select Option",
        sections: [{ title: "Payment Options", rows: rows }]
      }
    }
  };
  await sendMessage(to, payload);
}

async function sendPayLoanConfirm(to, session, installmentsToPay) {
  if (session) session.currentMenu = "pay_loan_confirm_menu";

  const loan = currentLoans[to];
  const payAmount = loan.breakdown.monthlyInstallment * installmentsToPay;
  const totalOwed = loan.breakdown.monthlyInstallment * (loan.tenureMonths - loan.installmentsPaid);
  const balance = totalOwed - payAmount;

  session.pendingPaymentInstallments = installmentsToPay;

  const details = `You are about to pay ${installmentsToPay} installment${installmentsToPay > 1 ? 's' : ''} of ${payAmount.toLocaleString()}. Loan Balance ${balance.toLocaleString()}`;

  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "list",
      header: { type: "text", text: "Confirm Payment" },
      body: { text: details },
      footer: { text: "MyMobi Emergency Loan" },
      action: {
        button: "Select Option",
        sections: [{
          title: "Options",
          rows: [
            { id: "proceed_payment", title: "Proceed", description: "Pay via M-Pesa" },
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


// ==================== BUG FIX #6 ====================
// Maps a screen's session.currentMenu identifier to the function that
// renders the menu ONE LEVEL UP from it. "Back" looks up the user's
// current screen here and calls the corresponding previous screen —
// as opposed to "Home", which always jumps to Welcome regardless of
// where the user currently is.
//
// To add a new navigable menu in future: have its send function set
// session.currentMenu = "some_id", then add an entry here mapping
// "some_id" -> the function that shows the menu it was reached from.
const MENU_BACK_MAP = {
  civil_servants_menu: (to, session) => sendWelcome(to),                    // Civil Servants Menu -> Welcome
  emergency_loan_menu: (to, session) => sendMainMenu(to, session),          // Emergency Loan submenu -> Civil Servants Menu
  loan_tenure_menu: (to, session) => sendEmergencyLoanMenu(to, session),    // Loan tenure list (Select Period) -> Emergency Loan submenu
  loan_amount_menu: (to, session) => sendLoanTenureOptions(to, session),    // Loan Amount menu -> Select Period page
  loan_breakdown_menu: (to, session) => sendLoanAmountMenu(to, session),    // Loan breakdown -> Loan Amount menu
  approve_loan_details_menu: (to, session) => sendEmergencyLoanMenu(to, session), // Approve Loan details -> Emergency Loan submenu
  pay_loan_menu: (to, session) => sendEmergencyLoanMenu(to, session),       // Pay Loan options -> Emergency Loan submenu
  pay_loan_confirm_menu: (to, session) => sendPayLoanOptions(to, session)   // Pay Loan confirm -> Pay Loan options
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
    await sendEmergencyLoanMenu(to, session);
  }
  else if (id === "apply_loan") {
    const existingLoan = currentLoans[to];
    if (existingLoan && (existingLoan.status === "pending_approval" || existingLoan.status === "approved")) {
      // Defensive: sendEmergencyLoanMenu already hides "Apply Loan" while
      // a loan is active, so this should only fire on a stale/replayed
      // button tap. Confirmed rule: no new applications until the
      // current loan is fully repaid.
      await sendTextMessage(to, "You already have an active loan. Please complete or repay it before applying for a new one.");
      await sendEmergencyLoanMenu(to, session);
      return;
    }
    await sendLoanTenureOptions(to, session);
  }
  else if (LOAN_TENURE_OPTIONS[id]) {
    const tenure = LOAN_TENURE_OPTIONS[id];
    session.loanTenureMonths = tenure.months;
    session.loanLimit = tenure.limit;
    await sendEnterLoanAmountPrompt(to, session);
  }
  else if (id === "start_loan_amount_entry") {
    await sendEnterLoanAmountPrompt(to, session);
  }
  else if (id === "accept_loan") {
    if (!session.loanBreakdown) {
      // Defensive: shouldn't happen in normal flow, but avoids a crash
      // if a stale button is tapped after the session moved on.
      await sendTextMessage(to, "That loan application has expired. Let's start again.");
      await sendEmergencyLoanMenu(to, session);
      return;
    }
    session.step = "enter_payroll_number";
    await sendTextMessage(to, "Please Enter Payroll Number to complete the transaction:");
  }
  else if (id === "decline_loan") {
    delete session.loanTenureMonths;
    delete session.loanLimit;
    delete session.loanAmount;
    delete session.loanBreakdown;
    await sendTextMessage(to, "Loan application declined.");
    await sendEmergencyLoanMenu(to, session);
  }
  // ==================== APPROVE LOAN ====================
  else if (id === "approve_loan_menu") {
    const loan = currentLoans[to];
    if (!loan || loan.status !== "pending_approval") {
      await sendTextMessage(to, "There's no pending loan application to approve.");
      await sendEmergencyLoanMenu(to, session);
      return;
    }
    await sendApproveLoanDetails(to, session);
  }
  else if (id === "confirm_approve_loan") {
    session.step = "enter_approval_payroll_number";
    await sendTextMessage(to, "Enter Payroll Number:");
  }
  // ==================== CANCEL LOAN ====================
  else if (id === "cancel_loan") {
    await sendCancelLoanConfirm(to, session);
  }
  else if (id === "cancel_loan_yes") {
    delete currentLoans[to];
    await sendTextMessage(to, "Your loan application has been successfully cancelled.");
    await sendWelcome(to);
  }
  else if (id === "cancel_loan_no") {
    // Returns to the pending-loan menu (Approve Loan / Cancel Loan), per spec.
    await sendEmergencyLoanMenu(to, session);
  }
  // ==================== PAY LOAN (EARLY REPAYMENT) ====================
  else if (id === "pay_loan_menu") {
    const loan = currentLoans[to];
    if (!loan || loan.status !== "approved") {
      await sendTextMessage(to, "There's no active loan to pay.");
      await sendEmergencyLoanMenu(to, session);
      return;
    }
    await sendPayLoanOptions(to, session);
  }
  else if (id.startsWith("pay_installments_")) {
    const installments = parseInt(id.replace("pay_installments_", ""), 10);
    await sendPayLoanConfirm(to, session, installments);
  }
  else if (id === "proceed_payment") {
    const loan = currentLoans[to];
    const installments = session.pendingPaymentInstallments;

    if (!loan || !installments) {
      await sendTextMessage(to, "That payment session has expired. Let's start again.");
      await sendEmergencyLoanMenu(to, session);
      return;
    }

    const payAmount = loan.breakdown.monthlyInstallment * installments;
    const stkResult = await triggerMpesaStkPush(to, payAmount);

    if (!stkResult.success) {
      await sendTextMessage(to, "Payment could not be processed. Please try again.");
      return;
    }

    loan.installmentsPaid += installments;
    if (loan.installmentsPaid >= loan.tenureMonths) {
      loan.status = "paid";
    }
    delete session.pendingPaymentInstallments;

    await sendTextMessage(to, "Thank you for using MyMobi.");
    await sendMainMenu(to, session);
  }
  else if (id === "get_payslip") {
    await sendTextMessage(to, "You selected Get Payslip. (Feature coming soon)");
  }
  else if (id === "back") {
    // BUG FIX #6: "Back" and "Home" previously did the exact same thing
    // (both jumped to Welcome). Back should return to the menu the user
    // came FROM, not always the top-level Welcome screen. MENU_BACK_MAP
    // looks up the previous screen based on session.currentMenu, which
    // each navigable menu function sets on itself when it's shown.
    const goBack = MENU_BACK_MAP[session.currentMenu] || ((t, s) => sendWelcome(t));
    await goBack(to, session);
  }
  else if (id === "home") {
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
  // EMERGENCY LOAN: AMOUNT ENTRY + PAYROLL NUMBER
  // =====================================================
  if (step === "enter_loan_amount") {
    // Only digits, no decimals/commas/symbols — keeps parsing unambiguous
    if (!/^\d+$/.test(cleanText)) {
      await sendTextMessage(to, "Please enter a valid loan amount in KES (numbers only, e.g. 35000).");
      return;
    }

    const amount = parseInt(cleanText, 10);

    if (amount <= 0) {
      await sendTextMessage(to, "Please enter a loan amount greater than 0.");
      return;
    }

    if (amount > session.loanLimit) {
      await sendTextMessage(to, `That exceeds your loan limit of KES ${session.loanLimit.toLocaleString()}. Please enter a lower amount.`);
      return;
    }

    session.loanAmount = amount;
    const breakdown = await getLoanBreakdown(amount, session.loanTenureMonths);
    session.loanBreakdown = breakdown;
    session.step = "loan_confirm";
    await sendLoanBreakdown(to, breakdown, session);
    return;
  }

  if (step === "enter_payroll_number") {
    if (!cleanText) {
      await sendTextMessage(to, "Please enter your Payroll Number.");
      return;
    }

    session.payrollNumber = cleanText;

    const refNo = generateLoanRefNo();
    const approvalCode = generateApprovalCode(); // TODO: real backend will generate/send this via SMS
    const dueDate = computeDueDate(session.loanTenureMonths);

    // This becomes the user's CURRENT loan — drives what the Emergency
    // Loan menu shows from now on (Approve Loan / Cancel Loan, until
    // resolved).
    currentLoans[to] = {
      loanAmount: session.loanAmount,
      tenureMonths: session.loanTenureMonths,
      breakdown: session.loanBreakdown,
      payrollNumber: session.payrollNumber,
      refNo: refNo,
      approvalCode: approvalCode,
      approvalCodeAttempts: 0,
      dueDate: dueDate,
      status: "pending_approval",
      installmentsPaid: 0,
      submittedAt: new Date().toISOString()
    };

    // Also keep a permanent audit trail of every application ever made.
    if (!loanApplications[to]) loanApplications[to] = [];
    loanApplications[to].push({ ...currentLoans[to] });

    console.log(`Loan application submitted by ${to}: KES ${session.loanAmount} over ${session.loanTenureMonths} month(s), Ref ${refNo}, approval code ${approvalCode} (simulated)`);

    await sendTextMessage(to, "Your loan request has been submitted. Please wait for an SMS from MyMobi.");

    // Clean up loan-application session fields — the durable state now
    // lives in currentLoans[to], not the session.
    delete session.loanTenureMonths;
    delete session.loanLimit;
    delete session.loanAmount;
    delete session.loanBreakdown;
    delete session.payrollNumber;

    // TODO: remove this simulated delivery once the real backend sends
    // the approval code via SMS. For now, it arrives as a separate
    // WhatsApp message 5 seconds after submission so the flow can be
    // tested end-to-end without checking server logs. The Emergency
    // Loan menu (Approve Loan / Cancel Loan) is only shown AFTER the
    // code arrives, not before. Fire-and-forget: errors here shouldn't
    // affect the rest of the submission flow.
    setTimeout(async () => {
      try {
        await sendTextMessage(to, `Approval Code ${approvalCode}`);
        await sendEmergencyLoanMenu(to, session);
      } catch (err) {
        // Ignore errors in this simulated delayed delivery
      }
    }, 5000);

    return;
  }

  // =====================================================
  // APPROVE LOAN: PAYROLL NUMBER + APPROVAL CODE
  // =====================================================
  if (step === "enter_approval_payroll_number") {
    if (!cleanText) {
      await sendTextMessage(to, "Please enter your Payroll Number.");
      return;
    }
    session.approvalPayrollNumber = cleanText;
    session.step = "enter_approval_code";
    await sendTextMessage(to, "Enter Approval Code:");
    return;
  }

  if (step === "enter_approval_code") {
    const loan = currentLoans[to];

    if (!loan || loan.status !== "pending_approval") {
      await sendTextMessage(to, "That loan application is no longer pending. Let's start again.");
      await sendEmergencyLoanMenu(to, session);
      return;
    }

    if (!/^\d{6}$/.test(cleanText)) {
      await sendTextMessage(to, "Invalid code. Please enter the 6-digit approval code.");
      return;
    }

    if (cleanText === loan.approvalCode) {
      loan.status = "approved";
      loan.approvedAt = new Date().toISOString();
      delete session.approvalPayrollNumber;

      await sendTextMessage(to, "Your loan approval has been received and is being processed. Please wait for an SMS notification from MyMobi.");
      await sendMainMenu(to, session);
    } else {
      loan.approvalCodeAttempts = (loan.approvalCodeAttempts || 0) + 1;

      if (loan.approvalCodeAttempts >= 3) {
        await sendTextMessage(to, "Too many incorrect attempts. Please try again later.");
        delete session.approvalPayrollNumber;
        await sendEmergencyLoanMenu(to, session);
      } else {
        const attemptsLeft = 3 - loan.approvalCodeAttempts;
        await sendTextMessage(to, `Incorrect code. You have ${attemptsLeft} attempt(s) remaining.`);
      }
    }
    return;
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
      await sendMainMenu(to, session);
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
