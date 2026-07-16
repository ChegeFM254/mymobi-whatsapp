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

// ==================== TIMEOUT ====================
function resetTimeout(from) {
  if (userSessions[from] && userSessions[from].timeoutId) {
    clearTimeout(userSessions[from].timeoutId);
  }
  userSessions[from].timeoutId = setTimeout(() => {
    delete userSessions[from];
    sendTextMessage(from, "⏰ Your session has timed out due to inactivity.").catch(() => {});
  }, 60000);
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
            { id: "civil_servants", title: "Civil Servants", description: "Emergency Loan & Payslip" }
          ]
        }]
      }
    }
  };
  await sendMessage(to, payload);
}

// ==================== EMERGENCY LOAN SUB-MENU ====================
// Initial menu (with Apply Loan)
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

// Post-submission menu (ONLY Approve Loan, Home, Logout)
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

  // Example values (will be replaced by backend later)
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
  if (id === "emergency_loan") {
    await sendEmergencyLoanSubMenu(to);
    session.step = "loan_submenu";
  }
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
  else if (id === "approve_loan") {
    await sendTextMessage(to, "Approve Loan feature coming soon.");
    await sendEmergencyLoanSubMenu(to);
  }
  else if (id === "home") {
    await sendMainMenu(to);
  }
  else if (id === "logout") {
    session.step = "logout_confirm";
    await sendTextMessage(to, "Are you sure you want to log out? (Yes/No)");
  }
}

async function handleTextInput(to, text, session) {
  const cleanText = text.trim();
  const step = session.step;

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
    
    // Return to limited sub-menu (only Approve Loan, Home, Logout)
    await sendPostSubmissionSubMenu(to);
    return;
  }

  if (step === "logout_confirm") {
    if (cleanText.toLowerCase() === "yes") {
      await sendTextMessage(to, "You have been logged out.");
      delete userSessions[to];
    } else {
      await sendEmergencyLoanSubMenu(to);
    }
    return;
  }

  // Keep all your existing text input handlers (KYC, PIN, OTP, registration, etc.)
}

// ==================== EXISTING FUNCTIONS ====================
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
            { id: "emergency_loan", title: "Emergency Loan", description: "Apply or manage loans" },
            { id: "get_payslip", title: "Get Payslip", description: "Download your payslip" },
            { id: "logout", title: "Logout", description: "Log out" }
          ]
        }]
      }
    }
  };
  await sendMessage(to, payload);
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
