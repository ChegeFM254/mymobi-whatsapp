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

// 60-second inactivity timeout (you can increase this later)
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

// ==================== ORIGINAL SCREENS (kept exactly) ====================

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

// ==================== NEW EMERGENCY LOAN SCREENS ====================

async function sendEmergencyLoanSubMenu(to) {
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
            { id: "apply_loan", title: "Apply Loan", description: "Request a new emergency loan" },
            { id: "approve_loan", title: "Approve Loan", description: "Approve your loan" },
            { id: "pay_loan", title: "Pay Loan", description: "Make a repayment" },
            { id: "home", title: "Home", description: "Return to Main Menu" },
            { id: "logout", title: "Logout", description: "Log out of the app" }
          ]
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
            { id: "home", title: "Home", description: "Return to Main Menu" },
            { id: "logout", title: "Logout", description: "Log out of the app" }
          ]
        }]
      }
    }
  };
  await sendMessage(to, payload);
}

async function sendLoanPeriodOptions(to) {
  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "list",
      header: { type: "text", text: "Select Repayment Period" },
      body: { text: "How long would you like to repay the loan?" },
      footer: { text: "MyMobi" },
      action: {
        button: "Select Period",
        sections: [{
          title: "Available Periods",
          rows: [
            { id: "period_1m", title: "1 Month", description: "Max KES 20,000" },
            { id: "period_2m", title: "2 Months", description: "Max KES 40,000" },
            { id: "period_3m", title: "3 Months", description: "Max KES 60,000" }
          ]
        }]
      }
    }
  };
  await sendMessage(to, payload);
}

async function sendLoanBreakdown(to, session) {
  const amount = session.loanAmount;
  const months = session.loanPeriodMonths;

  // Example values for testing (will be replaced by backend later)
  const upfrontFee = Math.round(amount * 0.08);
  const disbursement = amount - upfrontFee;
  const platformFee = 150;
  const totalRepayable = amount + upfrontFee + platformFee;
  const monthlyInstallment = Math.round(totalRepayable / months);

  const breakdownText = 
`Loan Amount: KES ${amount}
Upfront Fees: KES ${upfrontFee}
Disbursement: KES ${disbursement}
Monthly Installment: KES ${monthlyInstallment}
Platform Fee: KES ${platformFee}

Do you want to proceed?`;

  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "button",
      body: { text: breakdownText },
      action: {
        buttons: [
          { type: "reply", reply: { id: "accept_loan", title: "Accept" } },
          { type: "reply", reply: { id: "decline_loan", title: "Decline" } }
        ]
      }
    }
  };
  await sendMessage(to, payload);
}

async function sendPayrollPrompt(to) {
  await sendTextMessage(to, "Please enter your Payroll Number to complete the transaction.");
}

// ==================== HANDLERS ====================

async function handleButton(to, id, session) {
  if (id === "civil_servants") {
    const user = registeredUsers[to];
    if (user && user.status === "blocked") {
      await sendTextMessage(to, "Your account is blocked. Please contact Customer Care.");
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
  else if (id === "buy_airtime") {
    await sendTextMessage(to, "Buy Airtime feature coming soon.");
    await sendWelcome(to);
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
  else if (id === "emergency_loan") {
    await sendEmergencyLoanSubMenu(to);
    session.step = "loan_submenu";
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

  // ==================== EMERGENCY LOAN HANDLERS ====================
  else if (id === "apply_loan") {
    session.step = "loan_period";
    await sendLoanPeriodOptions(to);
  }
  else if (id === "period_1m" || id === "period_2m" || id === "period_3m") {
    const periodMap = {
      "period_1m": { months: 1, limit: 20000 },
      "period_2m": { months: 2, limit: 40000 },
      "period_3m": { months: 3, limit: 60000 }
    };
    const selected = periodMap[id];
    session.loanPeriodMonths = selected.months;
    session.loanLimit = selected.limit;

    await sendTextMessage(to, `You have a loan limit of KES ${selected.limit} payable in ${selected.months} month(s) from your payslip.`);
    await sendTextMessage(to, "Please enter the Loan Amount you wish to apply for:");
    session.step = "loan_amount";
  }
  else if (id === "accept_loan") {
    session.step = "enter_payroll";
    await sendPayrollPrompt(to);
  }
  else if (id === "decline_loan") {
    await sendTextMessage(to, "Loan application cancelled.");
    await sendEmergencyLoanSubMenu(to);
  }
  else if (id === "approve_loan" || id === "pay_loan") {
    await sendTextMessage(to, "This feature is coming soon.");
    await sendEmergencyLoanSubMenu(to);
  }
}

async function handleTextInput(to, text, session) {
  const cleanText = text.trim();
  const step = session.step;

  // KYC DATA COLLECTION (original)
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

  // EDIT FLOW (original)
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

  // ==================== EMERGENCY LOAN ====================
  if (step === "loan_amount") {
    const amount = parseInt(cleanText.replace(/[^0-9]/g, ''));
    if (isNaN(amount) || amount < 1000 || amount > session.loanLimit) {
      await sendTextMessage(to, `Please enter a valid amount between KES 1,000 and KES ${session.loanLimit}.`);
      return;
    }
    session.loanAmount = amount;
    await sendLoanBreakdown(to, session);
    return;
  }

  if (step === "enter_payroll") {
    if (!cleanText) {
      await sendTextMessage(to, "Please enter your Payroll Number.");
      return;
    }
    session.payrollNumber = cleanText;

    const user = registeredUsers[to];
    if (user && !user.loans) user.loans = [];

    if (user) {
      user.loans.push({
        amount: session.loanAmount,
        periodMonths: session.loanPeriodMonths,
        payrollNumber: cleanText,
        status: "Submitted",
        appliedAt: new Date().toISOString()
      });
    }

    await sendTextMessage(to, "Your loan request has been submitted. Please wait for an SMS from MyMobi.");
    await sendPostSubmissionSubMenu(to);
    return;
  }

  // REGISTRATION: OTP + PIN (original)
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
        await sendTextMessage(to, "Too many incorrect attempts. Your PIN has been deactivated.");
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

  // RETURNING USER: ENTER PIN + VERIFICATION CODE (original)
  if (step === "enter_pin") {
    if (!cleanText) { await sendTextMessage(to, "Please enter your 5-digit PIN."); return; }
    if (!/^\d{5}$/.test(cleanText)) { await sendTextMessage(to, "Invalid PIN. Please enter exactly 5 digits."); return; }

    const user = registeredUsers[to];
    if (!user) { await sendTextMessage(to, "User not found. Please register first."); return; }
    if (user.status === "blocked") {
      await sendTextMessage(to, "Your account is blocked. Please contact Customer Care.");
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
        await sendTextMessage(to, "Your account is blocked. Please contact Customer Care.");
      } else {
        await sendTextMessage(to, `Incorrect PIN. You have ${3 - user.failedPinAttempts} attempt(s) remaining.`);
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
        await sendTextMessage(to, `Incorrect code. You have ${3 - session.verificationAttempts} attempt(s) remaining.`);
      }
    }
    return;
  }

  // FORGOT PIN (original)
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

  // OPT OUT (original)
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
    if (!user) { await sendTextMessage(to, "User not found."); return; }
    if (cleanText === user.pin) {
      user.status = "opted_out";
      delete user.pin;
      await sendTextMessage(to, "You have been successfully opted out of the Emergency Loan service.");
    } else {
      await sendTextMessage(to, "Incorrect PIN. Opt out cancelled.");
    }
    return;
  }

  // LOGOUT CONFIRM
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
