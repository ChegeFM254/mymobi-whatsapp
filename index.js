const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');
const crypto = require('crypto');

const app = express();
const PORT = process.env.PORT || 3000;

const ACCESS_TOKEN = 'EAAOxVVXxgvUBR7ZAnegjAq5UZCfeYqEkhevYGOEhXhLjkV6hIFdyB7uLI7mrAyvHHZBdXBEfZBnbwOLAfqJK2zryzGKKGNvtfpGLFi3QO054qhkVo8f9mYP4KIG5a0ZALX6ZABltcWEJQSsHE7Lc307OZCyNAARzQ0IdcLpy30FrpRI6OpFeZBFZCfHFkSIhOTHpQDgZDZD';
const PHONE_NUMBER_ID = '1265967949926220';
const VERIFY_TOKEN = 'mymobi_test_123';

app.use(bodyParser.json());

const userSessions = {};
const registeredUsers = {};

// ==================== HELPER FUNCTIONS (NEW) ====================
function hasPendingLoan(user) {
  return user && user.loans && user.loans.some(loan => loan.status === "Pending");
}

function hasApprovedLoan(user) {
  return user && user.loans && user.loans.some(loan => loan.status === "Approved");
}

function getPendingLoan(user) {
  return user && user.loans ? user.loans.find(loan => loan.status === "Pending") : null;
}

function getApprovedLoan(user) {
  return user && user.loans ? user.loans.find(loan => loan.status === "Approved") : null;
}

// ==================== WEBHOOK ====================
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

// ==================== ORIGINAL SCREENS (Unchanged) ====================

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
    "Your account has been successfully set up."
  );

  await sendMainMenu(to);

  if (userSessions[to]) {
    delete userSessions[to].otp;
    delete userSessions[to].newPin;
  }
}

// ==================== UPDATED sendMainMenu ====================
async function sendMainMenu(to) {
  const user = registeredUsers[to];
  const hasCurrentLoan = user && (hasPendingLoan(user) || hasApprovedLoan(user));

  let rows = [
    { id: "get_payslip", title: "Get Payslip", description: "Download your payslip" },
    { id: "home", title: "Home", description: "Return to home" },
    { id: "logout", title: "Logout", description: "Log out of the app" }
  ];

  if (!hasCurrentLoan) {
    rows.unshift({ id: "emergency_loan", title: "Emergency Loan", description: "Apply for emergency loan" });
  } else if (hasApprovedLoan(user) && !hasPendingLoan(user)) {
    rows.unshift({ id: "pay_loan", title: "Pay Loan", description: "Make a repayment" });
  }

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
          rows: rows
        }]
      }
    }
  };
  await sendMessage(to, payload);
}

// ==================== NEW EMERGENCY LOAN SCREENS ====================

async function sendEmergencyLoanSubMenu(to) {
  const user = registeredUsers[to];
  const hasPending = hasPendingLoan(user);

  let rows = hasPending 
    ? [
        { id: "approve_loan", title: "Approve Loan", description: "Approve your pending loan" },
        { id: "cancel_loan", title: "Cancel Loan", description: "Cancel your application" },
        { id: "home", title: "Home", description: "Return to Main Menu" },
        { id: "logout", title: "Logout", description: "Log out of the app" }
      ]
    : [
        { id: "apply_loan", title: "Apply Loan", description: "Request a new emergency loan" },
        { id: "approve_loan", title: "Approve Loan", description: "Approve your loan" },
        { id: "pay_loan", title: "Pay Loan", description: "Make a repayment" },
        { id: "home", title: "Home", description: "Return to Main Menu" },
        { id: "logout", title: "Logout", description: "Log out of the app" }
      ];

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
        sections: [{
          title: "Options",
          rows: rows
        }]
      }
    }
  };
  await sendMessage(to, payload);
}

async function sendPostSubmissionSubMenu(to) {
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
        sections: [{
          title: "Options",
          rows: [
            { id: "approve_loan", title: "Approve Loan", description: "Approve your submitted loan" },
            { id: "cancel_loan", title: "Cancel Loan", description: "Cancel your application" },
            { id: "home", title: "Home", description: "Return to Main Menu" },
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
  const user = registeredUsers[to];

  if (id === "civil_servants") {
    if (user && user.status === "active") {
      session.step = "auth_menu";
      await sendAuthMenu(to);
    } else {
      session.step = "optin";
      await sendOptIn(to);
    }
  }
  else if (id === "emergency_loan") {
    await sendEmergencyLoanSubMenu(to);
  }
  else if (id === "apply_loan") {
    if (user && (hasPendingLoan(user) || hasApprovedLoan(user))) {
      await sendTextMessage(to, "You already have a Current Loan");
      await sendMainMenu(to);
    } else {
      session.step = "loan_period";
      await sendLoanPeriodOptions(to);
    }
  }
  else if (id === "approve_loan") {
    const pendingLoan = getPendingLoan(user);
    if (!pendingLoan) {
      await sendTextMessage(to, "You don't have any pending loan to approve.");
      await sendEmergencyLoanSubMenu(to);
      return;
    }
    session.currentLoan = pendingLoan;
    session.step = "approve_payroll";
    await sendTextMessage(to, `You are about to approve ${pendingLoan.periodMonths}-month loan of KES ${pendingLoan.amount}`);
    await sendTextMessage(to, "Please enter your Payroll Number:");
  }
  else if (id === "cancel_loan") {
    const pendingLoan = getPendingLoan(user);
    if (!pendingLoan) {
      await sendTextMessage(to, "No pending loan to cancel.");
      await sendEmergencyLoanSubMenu(to);
      return;
    }
    session.currentLoan = pendingLoan;
    session.step = "cancel_confirm";
    await sendTextMessage(to, "Are you sure you want to cancel your loan application? (Yes/No)");
  }
  else if (id === "pay_loan") {
    const approvedLoan = getApprovedLoan(user);
    if (!approvedLoan) {
      await sendTextMessage(to, "You don't have any approved loan.");
      await sendMainMenu(to);
      return;
    }
    session.currentLoan = approvedLoan;
    session.step = "pay_installment";
    await sendTextMessage(to, "Pay Loan feature coming soon (installment selection).");
  }
  else if (id === "enter_pin") {
    session.step = "enter_pin";
    await sendTextMessage(to, "Enter your 5-digit PIN:");
  }
  else if (id === "forgot_pin") {
    session.step = "forgot_pin";
    session.otp = "67890";
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
    let fieldName = id.replace("edit_", "").replace("_", " ");
    if (fieldName === "mobilenumber") fieldName = "Mobile Number (Mpesa)";
    await sendTextMessage(to, `Enter new ${fieldName}:`);
  }
  else if (id === "get_payslip") {
    await sendTextMessage(to, "Get Payslip feature coming soon.");
    await sendMainMenu(to);
  } 
  else if (id === "back" || id === "home") {
    await sendMainMenu(to);
  } 
  else if (id === "logout") {
    session.step = "logout_confirm";
    await sendTextMessage(to, "Are you sure you want to log out? (Yes/No)");
  }
}

// ==================== TEXT INPUT (Original + New) ====================

async function handleTextInput(to, text, session) {
  const cleanText = text.trim();
  const step = session.step;
  const user = registeredUsers[to];

  // ==================== NEW: APPROVE LOAN ====================
  if (step === "approve_payroll") {
    session.payrollNumber = cleanText;
    session.step = "approve_code";

    const approvalCode = Math.floor(100000 + Math.random() * 900000).toString();
    session.approvalCode = approvalCode;
    session.approvalCodeExpiry = Date.now() + (72 * 60 * 60 * 1000);

    const hashedCode = crypto.createHash('sha256').update(approvalCode).digest('hex');
    if (session.currentLoan) session.currentLoan.approvalCodeHash = hashedCode;

    await sendTextMessage(to, `Your approval code is: **${approvalCode}** (Valid for 72 hours)`);
    await sendTextMessage(to, "Please enter the 6-digit Approval Code:");
    return;
  }

  if (step === "approve_code") {
    if (cleanText === session.approvalCode) {
      if (session.currentLoan) session.currentLoan.status = "Approved";
      await sendTextMessage(to, "Your loan approval has been received and is being processed. Please wait for an SMS notification from MyMobi.");
      await sendMainMenu(to);
    } else {
      await sendTextMessage(to, "Invalid approval code. Please try again.");
    }
    return;
  }

  // ==================== NEW: CANCEL LOAN ====================
  if (step === "cancel_confirm") {
    if (cleanText.toLowerCase() === "yes") {
      if (session.currentLoan) session.currentLoan.status = "Cancelled";
      await sendTextMessage(to, "Your loan application has been successfully cancelled.");
      await sendEmergencyLoanSubMenu(to);
    } else {
      await sendEmergencyLoanSubMenu(to);
    }
    return;
  }

  // ==================== ORIGINAL KYC + REGISTRATION + AUTH ====================
  if (["first_name", "last_name", "upn", "national_id", "mobile_number"].includes(step)) {
    if (step === "first_name") {
      if (!cleanText) { await sendTextMessage(to, "Please enter your First Name."); return; }
      session.firstName = cleanText;
      session.step = "last_name";
      await sendTextMessage(to, "Enter your Last Name");
      return;
    }
    if (step === "last_name") {
      if (!cleanText) { await sendTextMessage(to, "Please enter your Last Name."); return; }
      session.lastName = cleanText;
      session.step = "upn";
      await sendTextMessage(to, "Enter UPN");
      return;
    }
    if (step === "upn") {
      if (!cleanText) { await sendTextMessage(to, "Please enter your UPN."); return; }
      session.upn = cleanText;
      session.step = "national_id";
      await sendTextMessage(to, "Enter National ID Number");
      return;
    }
    if (step === "national_id") {
      if (!cleanText) { await sendTextMessage(to, "Please enter your National ID Number."); return; }
      session.nationalId = cleanText;
      session.step = "mobile_number";
      await sendTextMessage(to, "Enter Mobile Number (Mpesa)");
      return;
    }
    if (step === "mobile_number") {
      if (!cleanText) { await sendTextMessage(to, "Please enter your Mobile Number (Mpesa)."); return; }
      session.mobileNumber = cleanText;
      await sendConfirmation(to, session);
      return;
    }
  }

  // EDIT FLOW
  if (step.startsWith("edit_")) {
    if (!cleanText) { await sendTextMessage(to, "Please enter a valid value."); return; }
    const field = step.replace("edit_", "");
    if (field === "firstname") session.firstName = cleanText;
    if (field === "lastname") session.lastName = cleanText;
    if (field === "upn") session.upn = cleanText;
    if (field === "nationalid") session.nationalId = cleanText;
    if (field === "mobilenumber") session.mobileNumber = cleanText;
    await sendConfirmation(to, session);
    return;
  }

  // REGISTRATION OTP + PIN
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
        await sendTextMessage(to, "Too many incorrect attempts.");
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
      await sendTextMessage(to, "Your new PIN cannot be the same as the OTP.");
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
      await sendTextMessage(to, "The PINs do not match. Please try again.");
      session.step = "enter_new_pin";
    }
    return;
  }

  // RETURNING USER PIN + VERIFICATION
  if (step === "enter_pin") {
    if (!cleanText) { await sendTextMessage(to, "Please enter your 5-digit PIN."); return; }
    if (!/^\d{5}$/.test(cleanText)) { await sendTextMessage(to, "Invalid PIN."); return; }

    const user = registeredUsers[to];
    if (!user) { await sendTextMessage(to, "User not found."); return; }
    if (user.status === "blocked") {
      await sendTextMessage(to, "Your account is blocked.");
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
        await sendTextMessage(to, "Your account is blocked.");
      } else {
        await sendTextMessage(to, `Incorrect PIN. You have ${3 - user.failedPinAttempts} attempt(s) remaining.`);
      }
    }
    return;
  }

  if (step === "enter_verification_code") {
    const verificationCode = "67890";
    if (!/^\d{5}$/.test(cleanText)) {
      await sendTextMessage(to, "Invalid code.");
      return;
    }
    if (cleanText === verificationCode) {
      await sendTextMessage(to, "Verification successful!");
      await sendMainMenu(to);
    } else {
      session.verificationAttempts = (session.verificationAttempts || 0) + 1;
      if (session.verificationAttempts >= 3) {
        await sendTextMessage(to, "Too many incorrect attempts.");
        session.step = "enter_pin";
      } else {
        await sendTextMessage(to, `Incorrect code. You have ${3 - session.verificationAttempts} attempt(s) remaining.`);
      }
    }
    return;
  }

  // FORGOT PIN & OPT OUT (kept from original)
  if (step === "forgot_pin") {
    if (!/^\d{5}$/.test(cleanText)) {
      await sendTextMessage(to, "Invalid OTP.");
      return;
    }
    if (cleanText === session.otp) {
      session.step = "enter_new_pin";
      await sendTextMessage(to, "OTP verified. Please create a new 5-digit PIN:");
    } else {
      await sendTextMessage(to, "Incorrect OTP.");
    }
    return;
  }

  if (step === "opt_out_confirmation") {
    const response = cleanText.toLowerCase();
    if (response === "yes" || response === "y") {
      session.step = "opt_out_pin";
      await sendTextMessage(to, "To confirm opt out, please enter your 5-digit PIN:");
    } else {
      await sendAuthMenu(to);
    }
    return;
  }

  if (step === "opt_out_pin") {
    const user = registeredUsers[to];
    if (user && cleanText === user.pin) {
      user.status = "opted_out";
      delete user.pin;
      await sendTextMessage(to, "You have been successfully opted out.");
    } else {
      await sendTextMessage(to, "Incorrect PIN. Opt out cancelled.");
    }
    return;
  }

  if (step === "logout_confirm") {
    if (cleanText.toLowerCase() === "yes") {
      await sendTextMessage(to, "You have been logged out.");
      delete userSessions[to];
    } else {
      await sendMainMenu(to);
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
