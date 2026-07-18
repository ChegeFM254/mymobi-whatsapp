require('dotenv').config();
const crypto = require('crypto');
const bcrypt = require('bcryptjs');
const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');
const rateLimit = require('express-rate-limit');

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
// Optional for now — see webhook signature verification below. Find this
// in Meta App Dashboard -> App Settings -> Basic -> App Secret. Different
// from your access token.
const APP_SECRET = process.env.WHATSAPP_APP_SECRET;

if (!ACCESS_TOKEN || !PHONE_NUMBER_ID) {
  logWarn('config_missing', { missing: 'WHATSAPP_ACCESS_TOKEN or WHATSAPP_PHONE_NUMBER_ID', message: '⚠️  WHATSAPP_ACCESS_TOKEN or WHATSAPP_PHONE_NUMBER_ID is not set. Create a .env file (see .env.example).' });
}
if (!APP_SECRET) {
  logWarn('webhook_signature_verification_disabled', { reason: 'WHATSAPP_APP_SECRET not set', message: '⚠️  WHATSAPP_APP_SECRET is not set — webhook signature verification is DISABLED. Anyone who finds this URL can currently send fake webhook events. Set WHATSAPP_APP_SECRET when you are ready to lock this down (see .env.example). This warning does not block testing.' });
}

// bodyParser's `verify` callback captures the raw request bytes before
// JSON parsing — required because signature verification (below) must
// hash the exact bytes Meta sent, not our re-serialized parsed copy of
// them (which can differ in whitespace/key order and would never match).
app.use(bodyParser.json({
  verify: (req, res, buf) => {
    req.rawBody = buf;
  }
}));
// Needed for the document viewer's UPN entry form (a plain HTML <form>,
// not JSON) — separate from the JSON parser used by the WhatsApp webhook.
app.use(bodyParser.urlencoded({ extended: true }));

// ==================== ITEM 4: SERVER-LEVEL RATE LIMITING ====================
// Separate from the outbound message queue (which paces OUR replies to
// WhatsApp) — this protects the server itself from being hammered with
// requests directly, whether by accident, misconfiguration, or abuse.
// 120 requests/minute per IP comfortably covers legitimate WhatsApp
// webhook traffic (Meta's servers) while blocking abusive volumes.
const webhookRateLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 120,
  standardHeaders: true,
  legacyHeaders: false,
  message: 'Too many requests.'
});
app.use('/webhook', webhookRateLimiter);

// ==================== ITEM 1: WEBHOOK SIGNATURE VERIFICATION ====================
// Meta signs every real webhook POST with an HMAC-SHA256 signature (the
// X-Hub-Signature-256 header), computed using your App Secret. Verifying
// it proves the request genuinely came from Meta — without this, anyone
// who finds this URL could POST fake "messages" claiming to be from any
// phone number.
//
// Graceful by design: if APP_SECRET isn't set yet, verification is
// skipped with a warning (already logged above at startup) rather than
// rejecting requests — so this doesn't block your current testing. Once
// WHATSAPP_APP_SECRET is set in your environment, verification becomes
// mandatory and any request with a missing/invalid signature is rejected.
function isValidWebhookSignature(req) {
  if (!APP_SECRET) {
    return true; // not configured yet — see warning above
  }

  const signatureHeader = req.get('X-Hub-Signature-256');
  if (!signatureHeader || !req.rawBody) {
    return false;
  }

  const expectedSignature = 'sha256=' + crypto
    .createHmac('sha256', APP_SECRET)
    .update(req.rawBody)
    .digest('hex');

  // Constant-time comparison — prevents timing attacks that could let an
  // attacker guess the correct signature one byte at a time.
  const a = Buffer.from(signatureHeader);
  const b = Buffer.from(expectedSignature);
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

// ==================== ITEM 2: PIN HASHING ====================
// PINs are never stored or compared as plain text. hashPin() is used
// once, when a PIN is first saved (registration or reset); verifyPin()
// is used every time a returning user types their PIN to log in — it
// hashes what they typed and compares hashes, never the raw PIN itself.
// A leaked database no longer exposes anyone's actual PIN.
const PIN_SALT_ROUNDS = 10;

// ==================== ITEM 9: STRUCTURED LOGGING ====================
// Plain console.log/error scattered through the code is hard to search
// and impossible to filter by severity or event type once there's real
// volume. This wraps every log line in a consistent JSON shape — still
// visible in Render's existing log viewer exactly as before, but now
// greppable/parseable by a future log aggregation tool without needing
// any new external service today.
function log(level, event, data = {}) {
  const entry = { timestamp: new Date().toISOString(), level, event, ...data };
  const line = JSON.stringify(entry);
  if (level === 'error') console.error(line);
  else if (level === 'warn') console.warn(line);
  else console.log(line);
}
function logInfo(event, data) { log('info', event, data); }
function logWarn(event, data) { log('warn', event, data); }
function logError(event, data) { log('error', event, data); }

async function hashPin(plainPin) {
  return bcrypt.hash(plainPin, PIN_SALT_ROUNDS);
}

async function verifyPin(plainPin, storedHash) {
  if (!storedHash) return false;
  return bcrypt.compare(plainPin, storedHash);
}

const userSessions = {};

// ==================== REGISTERED USERS STORAGE ====================
const registeredUsers = {};   // Key = WhatsApp number (from), Value = user data

// ==================== LOG IN LOCKOUT (10-minute temporary block) ====================
// Keyed by phone number rather than the user record, so a lockout still
// applies even if the entered UPN never matched any local account —
// otherwise repeatedly guessing a wrong UPN would never trigger it.
// Replaces the old permanent "contact Customer Care" block, which was
// tied to the PIN-only login flow this new UPN+PIN flow replaces.
const loginLockouts = {}; // Key = WhatsApp number -> unlock timestamp (ms)
const LOGIN_LOCKOUT_DURATION_MS = 10 * 60 * 1000; // 10 minutes
const MAX_LOGIN_ATTEMPTS = 3;

function getLoginLockoutMinutesRemaining(to) {
  const unlockAt = loginLockouts[to];
  if (!unlockAt) return 0;
  const remainingMs = unlockAt - Date.now();
  if (remainingMs <= 0) {
    delete loginLockouts[to];
    return 0;
  }
  return Math.ceil(remainingMs / 60000);
}

function applyLoginLockout(to) {
  loginLockouts[to] = Date.now() + LOGIN_LOCKOUT_DURATION_MS;
}

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

// ==================== DOCUMENT DELIVERY (Payslip / Loan Statement / Loan Clearance) ====================
// Generated documents are served via a short-lived link to a small,
// separate web page (NOT part of the WhatsApp chat) that gates access
// behind the person's UPN, per the product spec. Documents are HTML
// pages, not real PDF files, for now — see the "PDF for now" TODO below.
//
// Key = random token (the last part of the link) -> {
//   to, docType, upn (must match to unlock), html, createdAt
// }
// In-memory only, same caveat as every other data store in this app —
// lost on restart. Entries expire after 24 hours either way.
const documentStore = {};
const DOCUMENT_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours
const documentCleanupInterval = setInterval(() => {
  const now = Date.now();
  for (const [token, doc] of Object.entries(documentStore)) {
    if (now - doc.createdAt > DOCUMENT_TTL_MS) {
      delete documentStore[token];
    }
  }
}, 60 * 60 * 1000); // sweep hourly
documentCleanupInterval.unref(); // see BUG FIX #10 note on the identical pattern above

const DOCUMENT_COST_PER_UNIT = 23.20; // KES, per product spec — same rate for all three document types
const LENDER_NAME = "MFS Technologies Limited"; // shown on Loan Statement and Loan Clearance Letter

function generateDocumentToken() {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let token = '';
  for (let i = 0; i < 8; i++) token += chars[Math.floor(Math.random() * chars.length)];
  return token;
}

// SECURITY: firstName/lastName are free-text with no format restriction
// (unlike UPN/National ID, which are digits-only and therefore safe).
// Without escaping, a malicious name could inject script content into
// the document web page below — a stored XSS risk. Every piece of
// user-controlled text gets run through this before going into HTML.
function escapeHtml(value) {
  if (value === undefined || value === null) return '';
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

// Base URL documents are served from. Render *usually* provides
// RENDER_EXTERNAL_URL automatically for web services, but this isn't
// guaranteed across every plan/configuration — so PUBLIC_BASE_URL can
// be set explicitly to override it (recommended once you know your
// real Render URL), falling back to Render's auto value, then to
// localhost for local testing.
const BASE_URL = process.env.PUBLIC_BASE_URL || process.env.RENDER_EXTERNAL_URL || `http://localhost:${process.env.PORT || 3000}`;

// ==================== DOCUMENT HTML TEMPLATES ====================
// Per product decision: these are HTML pages standing in for real PDFs
// for now (viewable, printable-to-PDF via the browser), not actual PDF
// files — a real PDF library is a planned upgrade once this flow is
// proven out end-to-end.
function documentPageWrapper(title, bodyHtml) {
  return `<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${escapeHtml(title)} - MyMobi</title>
<style>
  body { font-family: Arial, Helvetica, sans-serif; max-width: 700px; margin: 40px auto; padding: 0 20px; color: #222; line-height: 1.5; }
  .header { border-bottom: 3px solid #0a7d3e; padding-bottom: 16px; margin-bottom: 24px; }
  .header h1 { color: #0a7d3e; margin: 0 0 4px 0; }
  table { width: 100%; border-collapse: collapse; margin: 16px 0; }
  td, th { padding: 8px 12px; text-align: left; border-bottom: 1px solid #ddd; }
  th { background: #f5f5f5; }
  .total-row td { font-weight: bold; border-top: 2px solid #333; }
  .footer { margin-top: 32px; font-size: 12px; color: #888; }
  .notice { background: #fff8e1; border: 1px solid #ffe082; padding: 12px; border-radius: 6px; margin: 20px 0; font-size: 14px; }
  button.download-btn { background: #0a7d3e; color: white; border: none; padding: 10px 20px; border-radius: 6px; font-size: 15px; cursor: pointer; }
  @media print { .no-print { display: none; } }
</style>
</head>
<body>
  <div class="header"><h1>MyMobi</h1><p>${escapeHtml(title)}</p></div>
  ${bodyHtml}
  <div class="notice no-print">
    📄 This is a placeholder document for testing (a downloadable PDF version is planned). Use your browser's Print option and choose "Save as PDF" to keep a copy.
  </div>
  <div class="no-print"><button class="download-btn" onclick="window.print()">Download / Print</button></div>
  <div class="footer">Generated by MyMobi &middot; ${new Date().toISOString()}</div>
</body>
</html>`;
}

function generatePayslipHtml(user, months) {
  // Placeholder/dummy figures — confirmed acceptable for now since no
  // real payroll data exists anywhere in this system yet.
  const basicSalary = 45000;
  const allowances = 8000;
  const grossPay = basicSalary + allowances;
  const paye = 6200;
  const nssf = 1080;
  const shif = 1350;
  const otherDeductions = 500;
  const totalDeductions = paye + nssf + shif + otherDeductions;
  const netPay = grossPay - totalDeductions;

  let monthSections = '';
  const now = new Date();
  for (let i = 0; i < months; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
    const label = d.toLocaleString('en-US', { month: 'long', year: 'numeric' });
    monthSections += `
      <h3>${escapeHtml(label)}</h3>
      <table>
        <tr><th>Earnings</th><th>Amount (KES)</th></tr>
        <tr><td>Basic Salary</td><td>${basicSalary.toLocaleString()}</td></tr>
        <tr><td>Allowances</td><td>${allowances.toLocaleString()}</td></tr>
        <tr><td><strong>Gross Pay</strong></td><td><strong>${grossPay.toLocaleString()}</strong></td></tr>
        <tr><th>Deductions</th><th>Amount (KES)</th></tr>
        <tr><td>PAYE</td><td>${paye.toLocaleString()}</td></tr>
        <tr><td>NSSF</td><td>${nssf.toLocaleString()}</td></tr>
        <tr><td>SHIF</td><td>${shif.toLocaleString()}</td></tr>
        <tr><td>Other Deductions</td><td>${otherDeductions.toLocaleString()}</td></tr>
        <tr class="total-row"><td>Net Pay</td><td>KES ${netPay.toLocaleString()}</td></tr>
      </table>`;
  }

  const body = `
    <p><strong>Name:</strong> ${escapeHtml(user.firstName)} ${escapeHtml(user.lastName)}<br>
    <strong>UPN:</strong> ${escapeHtml(user.upn)}<br>
    <strong>Period:</strong> Last ${months} month${months > 1 ? 's' : ''}</p>
    <div class="notice">⚠️ Placeholder figures for testing — not real payroll data.</div>
    ${monthSections}
  `;
  return documentPageWrapper('Payslip', body);
}

function generateLoanStatementHtml(user, loan) {
  const { remainingBalance } = calculateLoanBalance(loan.breakdown.monthlyInstallment, loan.tenureMonths, loan.installmentsPaid);
  const statusLabels = { pending_approval: 'Pending Approval', approved: 'Current (Active)', paid: 'Paid', cancelled: 'Cancelled' };
  const statusLabel = statusLabels[loan.status] || loan.status;
  const statementDate = new Date().toISOString().split('T')[0];

  const body = `
    <table>
      <tr><td>Statement Date</td><td>${escapeHtml(statementDate)}</td></tr>
      <tr><td>Lender</td><td>${escapeHtml(LENDER_NAME)}</td></tr>
      <tr><td>First Name</td><td>${escapeHtml(user.firstName)}</td></tr>
      <tr><td>Last Name</td><td>${escapeHtml(user.lastName)}</td></tr>
      <tr><td>UPN</td><td>${escapeHtml(user.upn)}</td></tr>
      <tr><td>Loan Principal</td><td>KES ${loan.loanAmount.toLocaleString()}</td></tr>
      <tr><td>Installments Paid</td><td>${loan.installmentsPaid} of ${loan.tenureMonths}</td></tr>
      <tr class="total-row"><td>Loan Balance</td><td>KES ${remainingBalance.toLocaleString()}</td></tr>
      <tr><td>Loan Due Date</td><td>${escapeHtml(loan.dueDate)}</td></tr>
      <tr><td>Loan Status</td><td>${escapeHtml(statusLabel)}</td></tr>
    </table>
  `;
  return documentPageWrapper('Loan Statement', body);
}

function generateLoanClearanceHtml(user, loan) {
  const { totalObligation, remainingBalance } = calculateLoanBalance(loan.breakdown.monthlyInstallment, loan.tenureMonths, loan.installmentsPaid);
  const letterDate = new Date().toISOString().split('T')[0];

  const body = `
    <p>This is to certify that the below-named individual has fully repaid their loan facility with ${escapeHtml(LENDER_NAME)}.</p>
    <table>
      <tr><td>Letter Date</td><td>${escapeHtml(letterDate)}</td></tr>
      <tr><td>Lender</td><td>${escapeHtml(LENDER_NAME)}</td></tr>
      <tr><td>First Name</td><td>${escapeHtml(user.firstName)}</td></tr>
      <tr><td>Last Name</td><td>${escapeHtml(user.lastName)}</td></tr>
      <tr><td>UPN</td><td>${escapeHtml(user.upn)}</td></tr>
      <tr><td>Loan Repayment Amount</td><td>KES ${totalObligation.toLocaleString()}</td></tr>
      <tr class="total-row"><td>Loan Balance</td><td>KES ${remainingBalance.toLocaleString()}</td></tr>
      <tr><td>Loan Due Date</td><td>${escapeHtml(loan.dueDate)}</td></tr>
      <tr><td>Loan Status</td><td>Paid</td></tr>
    </table>
    <p>No further amounts are owed on this loan facility as of the date of this letter.</p>
  `;
  return documentPageWrapper('Loan Clearance Letter', body);
}

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

// Used for registration OTP and returning-user Verification Code, which
// are both validated as 5 digits elsewhere in this file.
function generateFiveDigitCode() {
  return String(Math.floor(10000 + Math.random() * 90000)); // 5 digits
}

// ==================== KYC FIELD VALIDATION RULES ====================
// Shared between initial entry and the Edit Details flow, so both paths
// enforce identical rules and can't drift out of sync with each other.
function isValidUpn(text) {
  // Up to 11 digits, must start with 1 or 2.
  return /^[12]\d{0,10}$/.test(text);
}

function isValidNationalId(text) {
  // Exactly 8 digits, cannot start with 0.
  return /^[1-9]\d{7}$/.test(text);
}

function isValidMobileNumber(text) {
  // Either 10 digits starting with 0 (e.g. 0722730336), or 12 digits
  // starting with the 254 country code (e.g. 254722730336).
  return /^(0\d{9}|254\d{9})$/.test(text);
}

const UPN_ERROR_MESSAGE = "UPN should be up to 11 digits and start with 1 or 2. Please try again.";
const NATIONAL_ID_ERROR_MESSAGE = "National ID should be exactly 8 digits and cannot start with 0. Please try again.";
const MOBILE_NUMBER_ERROR_MESSAGE = "Mobile Number should be 10 digits starting with 0 (e.g. 0722730336) or 12 digits starting with 254 (e.g. 254722730336). Please try again.";

function computeDueDate(tenureMonths) {
  const due = new Date();
  due.setMonth(due.getMonth() + tenureMonths);
  return due.toISOString().split('T')[0]; // YYYY-MM-DD
}

// ITEM 10 (reusability win, found while adding tests): this exact math
// used to be duplicated with slightly different-looking expressions in
// two places (the pre-payment preview screen and the post-payment
// confirmation) — mathematically equivalent, but two places that could
// silently drift apart from each other if one was ever edited without
// the other. Pulled into one pure, easily unit-tested function instead.
function calculateLoanBalance(monthlyInstallment, tenureMonths, installmentsPaidSoFar) {
  const totalObligation = monthlyInstallment * tenureMonths;
  const remainingBalance = totalObligation - (monthlyInstallment * installmentsPaidSoFar);
  return { totalObligation, remainingBalance };
}

// Per product decision: Loan Statement covers the current/active loan,
// or the most recent one on record if there's no active loan right now.
// Safe to use loanApplications as a fallback now that it stores object
// REFERENCES (see the fix near loanApplications.push), not stale copies.
function getRelevantLoanForStatement(to) {
  if (currentLoans[to]) return currentLoans[to];
  const history = loanApplications[to];
  if (history && history.length > 0) return history[history.length - 1];
  return null;
}

// Loan Clearance Letter is only valid for a fully PAID loan, per spec —
// searches current loan first, then history, most recent first.
function findMostRecentPaidLoan(to) {
  if (currentLoans[to] && currentLoans[to].status === "paid") return currentLoans[to];
  const history = loanApplications[to];
  if (!history) return null;
  for (let i = history.length - 1; i >= 0; i--) {
    if (history[i].status === "paid") return history[i];
  }
  return null;
}

// TODO: replace with a real Safaricom Daraja API STK Push integration
// once it's available. For now, payment is simulated as immediately
// successful so the Pay Loan flow can be built and tested end-to-end
// ahead of the real payments backend. Kept as an async function
// returning a result object so swapping in the real API call later
// requires no changes at the call site.
async function triggerMpesaStkPush(to, amount) {
  logInfo('mpesa_stk_push_simulated', { to, amount });
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

// ==================== ITEM 6: HEALTH CHECK ====================
// Lightweight endpoint for Render's own health checks and any future
// uptime-monitoring tool — deliberately does nothing except confirm the
// process is up and responding, with no dependency on WhatsApp config.
app.get('/', (req, res) => {
  res.status(200).json({ status: 'ok', service: 'mymobi-whatsapp-bot' });
});
app.get('/health', (req, res) => {
  res.status(200).json({ status: 'ok', service: 'mymobi-whatsapp-bot' });
});

// ==================== DOCUMENT VIEWER (UPN-gated) ====================
// A small, separate web page — NOT part of the WhatsApp chat — that a
// Payslip/Loan Statement/Loan Clearance link points to. Per spec, access
// is gated behind the person's UPN. Brute-force protection: the link is
// permanently disabled after 5 wrong UPN attempts, requiring a fresh
// link from WhatsApp rather than allowing unlimited guessing.
function documentUpnFormPage(token, errorMessage, disableForm) {
  return `<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>MyMobi - Verify Your Identity</title>
<style>
  body { font-family: Arial, Helvetica, sans-serif; max-width: 420px; margin: 60px auto; padding: 0 20px; color: #222; text-align: center; }
  h1 { color: #0a7d3e; }
  input { width: 100%; padding: 10px; font-size: 16px; margin: 12px 0; box-sizing: border-box; border: 1px solid #ccc; border-radius: 6px; }
  button { background: #0a7d3e; color: white; border: none; padding: 12px 24px; border-radius: 6px; font-size: 16px; cursor: pointer; width: 100%; }
  button:disabled { background: #aaa; cursor: not-allowed; }
  .error { color: #b00020; margin: 12px 0; font-size: 14px; }
</style>
</head>
<body>
  <h1>MyMobi</h1>
  <p>Please enter your UPN Number to access this document.</p>
  ${errorMessage ? `<p class="error">${escapeHtml(errorMessage)}</p>` : ''}
  <form method="POST" action="/documents/${escapeHtml(token)}">
    <input type="text" name="upn" placeholder="Your UPN" inputmode="numeric" pattern="[0-9]*" required ${disableForm ? 'disabled' : ''}>
    <button type="submit" ${disableForm ? 'disabled' : ''}>View Document</button>
  </form>
</body>
</html>`;
}

app.get('/documents/:token', (req, res) => {
  const doc = documentStore[req.params.token];
  if (!doc) {
    return res.status(404).send(documentUpnFormPage(req.params.token, 'This link is invalid or has expired. Please request a new one from WhatsApp.', true));
  }
  res.send(documentUpnFormPage(req.params.token, null, false));
});

app.post('/documents/:token', (req, res) => {
  const doc = documentStore[req.params.token];
  if (!doc) {
    return res.status(404).send(documentUpnFormPage(req.params.token, 'This link is invalid or has expired. Please request a new one from WhatsApp.', true));
  }

  const enteredUpn = (req.body.upn || '').trim();

  if (enteredUpn === doc.upn) {
    logInfo('document_viewed', { token: req.params.token, docType: doc.docType, to: doc.to });
    return res.send(doc.html);
  }

  doc.failedAttempts = (doc.failedAttempts || 0) + 1;
  logWarn('document_upn_mismatch', { token: req.params.token, attempt: doc.failedAttempts });

  if (doc.failedAttempts >= 5) {
    delete documentStore[req.params.token];
    return res.status(403).send(documentUpnFormPage(req.params.token, 'Too many incorrect attempts. This link has been disabled for your security. Please request a new one from WhatsApp.', true));
  }

  res.send(documentUpnFormPage(req.params.token, 'Incorrect UPN. Please try again.', false));
});

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
  if (!isValidWebhookSignature(req)) {
    logWarn('webhook_signature_rejected', {});
    return res.sendStatus(403);
  }

  const message = req.body.entry?.[0]?.changes?.[0]?.value?.messages?.[0];
  if (!message) return res.sendStatus(200);

  // BUG FIX #4: ignore retried/duplicate webhook deliveries for a
  // message we've already handled — see MESSAGE DEDUPLICATION above.
  if (isDuplicateMessage(message.id)) {
    return res.sendStatus(200);
  }

  // BUG FIX #7: acknowledge the webhook to Meta IMMEDIATELY, before
  // doing any reply-sending work. Previously this response waited on
  // the ENTIRE reply chain, including the outbound message queue's
  // enforced spacing (up to a few seconds — see MIN_GAP_MS). If that
  // delay pushed our response past Meta's webhook timeout, Meta would
  // retry delivering the same button tap / message — which, even with
  // ID-based dedup, can still surface as a confusing double-prompt if
  // the retry arrives before the original send has fully completed.
  // Responding first, then doing the work, removes that whole failure
  // mode: Meta always gets its 200 OK right away, regardless of how
  // long sending replies takes.
  res.sendStatus(200);

  try {
    const from = message.from;

    // BUG FIX #10: mark the start of a new "turn" for this recipient so
    // the first reply to THIS incoming message/tap sends immediately,
    // without being held back by the chained-message spacing rule — see
    // OUTBOUND MESSAGE QUEUE below.
    resetSendTurn(from);

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
      return;
    }
    session.lastProcessed = Date.now();

    // ==================== ITEM 7: DATA DELETION REQUEST ====================
    // Works from ANY screen/step, mirroring the trigger-word pattern
    // above — a data-protection request shouldn't require navigating to
    // a specific menu first. Deletes registration, loan history, and
    // session data for this number. NOTE: since storage is still
    // in-memory (see registeredUsers declaration), this deletes the data
    // that currently exists; it isn't yet backed by durable storage that
    // itself needs a formal deletion/retention policy — that comes with
    // the database migration.
    const isDeletionRequest = ['delete my data', 'delete my account'].includes(lowerText);
    if (isDeletionRequest) {
      session.step = 'confirm_data_deletion';
      await sendDataDeletionConfirm(from, session);
      return;
    }
    if (session.step === 'confirm_data_deletion' && text) {
      const response = lowerText;
      if (response === 'yes' || response === 'y') {
        delete registeredUsers[from];
        delete currentLoans[from];
        delete loanApplications[from];
        logInfo('data_deletion_completed', { to: from });
        await sendTextMessage(from, "Your data has been permanently deleted from MyMobi. If you'd like to use the service again, just say Hi.");
        delete userSessions[from];
      } else if (response === 'no' || response === 'n') {
        await sendTextMessage(from, "Data deletion cancelled. Your information has not been changed.");
        await sendWelcome(from);
      } else {
        await sendTextMessage(from, "Please reply with Yes or No.");
      }
      return;
    }

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
    logError('webhook_handler_error', { message: err.message, stack: err.stack });
  }
});
// ==================== SCREENS ====================

// ITEM 7: data deletion confirmation. Plain text Yes/No (not buttons),
// matching the existing convention used for opt_out_confirmation and
// cancel_loan — consistent with how confirmations already work here.
async function sendDataDeletionConfirm(to, session) {
  await sendTextMessage(to, "⚠️ Are you sure you want to permanently delete all your MyMobi data (registration, loans, everything)? This cannot be undone.\n\nReply Yes or No.");
}

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

// NOTE: despite the similar name, this is unrelated to the
// "civil_servants_menu" MENU_BACK_MAP key used by sendMainMenu — that's
// the POST-login main menu (Emergency Loan/Payslip/etc). This function
// is the PRE-login screen (Log In/Register/Forgot PIN/Opt Out), tracked
// via session.step = "civil_servants_choice", a separate mechanism.
async function sendCivilServantsMenu(to, session) {
  if (session) session.step = "civil_servants_choice";

  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "list",
      header: { type: "text", text: "Civil Servants" },
      body: { text: "Select a service:" },
      footer: { text: "MyMobi" },
      action: {
        button: "Select Option",
        sections: [{
          title: "Options",
          rows: [
            { id: "login_menu", title: "Log In", description: "Already registered? Log in here" },
            { id: "register_menu", title: "Register", description: "New to MyMobi? Register here" },
            { id: "forgot_pin", title: "Forgot PIN", description: "Reset your PIN" },
            { id: "opt_out", title: "Opt Out", description: "Opt out of this service" }
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
        // BUG FIX #11 (interim, pending a real database): previously only
        // mobileNumber/pin/status/failedPinAttempts were saved here — the
        // actual KYC fields collected earlier (First Name, Last Name, UPN,
        // National ID) lived only in the temporary session object and
        // were lost once that session ended. A "returning user" was only
        // ever recognized by phone number + PIN, with no memory of who
        // they actually are. Now the full KYC profile is saved alongside
        // the login credentials.
        //
        // NOTE: this is still in-memory only (see registeredUsers
        // declaration) and will not survive a server restart/redeploy —
        // that's the separate, larger persistence gap to be addressed
        // with a real database.
        registeredUsers[to] = {
            firstName: session.firstName,
            lastName: session.lastName,
            upn: session.upn,
            nationalId: session.nationalId,
            mobileNumber: session.mobileNumber,
            pin: await hashPin(session.newPin), // ITEM 2: never store the raw PIN
            status: "active",
            failedPinAttempts: 0
        };
        logInfo('user_registered', { to, firstName: session.firstName, lastName: session.lastName, mobileNumber: session.mobileNumber });
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
  session.isAuthenticated = true;
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
    user.pin = await hashPin(session.newPin); // ITEM 2: never store the raw PIN
    user.failedPinAttempts = 0;
    user.status = "active";
    logInfo('pin_reset', { to });
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
  session.isAuthenticated = true;
  await sendMainMenu(to, session);
}

async function sendMainMenu(to, session) {
    if (session) session.currentMenu = "civil_servants_menu";

    // BUG FIX: description used to always say "Apply for emergency loan"
    // even when the user's real next action there is to approve, cancel,
    // or pay an existing loan — misleading, since tapping "Emergency
    // Loan" no longer leads to Apply Loan in those cases.
    const loan = currentLoans[to];
    let emergencyLoanDescription = "Apply for Emergency Loan";
    if (loan && loan.status === "pending_approval") {
      emergencyLoanDescription = "Approve or Cancel Loan Application";
    } else if (loan && loan.status === "approved") {
      emergencyLoanDescription = "Pay for Emergency Loan";
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
                    rows: [
                        { id: "emergency_loan", title: "Emergency Loan", description: emergencyLoanDescription },
                        { id: "payslip_menu", title: "Payslip", description: "Download your payslip" },
                        { id: "loan_statement_menu", title: "Loan Statement", description: "View your loan details and balance" },
                        { id: "loan_clearance_menu", title: "Loan Clearance Letter", description: "For a fully paid loan" },
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
            { id: "confirm_approve_loan", title: "Enter Approval Code", description: "Confirm and proceed" },
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
  const { remainingBalance: balance } = calculateLoanBalance(
    loan.breakdown.monthlyInstallment,
    loan.tenureMonths,
    loan.installmentsPaid + installmentsToPay
  );

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

// ==================== DOCUMENTS: Payslip / Loan Statement / Loan Clearance ====================
// Shared confirmation screen for all three document types — per spec,
// only 3 options here (Accept/Cancel/Home), so a button-type message
// works fine and doesn't need the list-type conversion.
async function sendDocumentConfirm(to, session, docType, title, cost) {
  session.pendingDocumentType = docType;
  session.pendingDocumentCost = cost;

  const payload = {
    messaging_product: "whatsapp",
    to: to,
    type: "interactive",
    interactive: {
      type: "button",
      body: { text: `You have selected to download your ${title}. Cost KES ${cost.toFixed(2)}.` },
      action: {
        buttons: [
          { type: "reply", reply: { id: "document_accept", title: "Accept" } },
          { type: "reply", reply: { id: "document_cancel", title: "Cancel" } },
          { type: "reply", reply: { id: "home", title: "Home" } }
        ]
      }
    }
  };
  await sendMessage(to, payload);
}

async function sendPayslipMonthsPrompt(to, session) {
  session.step = "enter_payslip_months";
  await sendTextMessage(to, "Enter Number of months (1-12):");
}


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
    // Stay logged in for the rest of the session — only Logout or the
    // inactivity timeout should ever force Log In again. Returning to
    // Welcome after completing an action (loan, payment, document) is
    // fine; re-entering UPN/PIN/Verification Code every time is not.
    if (session.isAuthenticated) {
      await sendMainMenu(to, session);
      return;
    }

    // Multi-channel change: this app is one of several channels (USSD,
    // PWA also exist). We can no longer assume "not in our local store"
    // means "genuinely new" — someone could already be a registered
    // user via another channel who just hasn't used WhatsApp yet. So
    // the old "known user -> PIN screen, unknown -> registration" branch
    // is gone; everyone sees the same Log In / Register choice and
    // decides for themselves.
    await sendCivilServantsMenu(to, session);
  }
  else if (id === "buy_airtime") {
    // BUG FIX #13: this had no handler at all, so tapping it fell through
    // to the generic "Sorry, I didn't understand that option" message —
    // misleading, since the bot understood it fine, the feature just
    // isn't built yet. Now consistent with get_payslip's treatment.
    await sendTextMessage(to, "You selected Buy Airtime. (Feature coming soon)");
    await sendWelcome(to);
  }
  // ==================== CIVIL SERVANTS MENU: Log In / Register / Forgot PIN / Opt Out ====================
  // Replaces the old "Enter PIN" auth menu — multi-channel users (USSD,
  // PWA) shouldn't be forced through registration again just because
  // they're using WhatsApp for the first time, so this now explicitly
  // asks Log In vs Register rather than assuming based on local data.
  else if (id === "login_menu") {
    const lockoutMinutes = getLoginLockoutMinutesRemaining(to);
    if (lockoutMinutes > 0) {
      await sendTextMessage(to, `Too many incorrect attempts. Your account is temporarily locked. Please try again in ${lockoutMinutes} minute(s).`);
      return;
    }
    session.step = "login_enter_upn";
    session.loginAttempts = 0;
    await sendTextMessage(to, "Enter UPN:");
  }
  else if (id === "register_menu") {
    const existingUser = registeredUsers[to];
    if (existingUser && existingUser.status === "active") {
      // Light guard: avoid silently overwriting an existing local
      // registration if someone taps Register by mistake.
      await sendTextMessage(to, "You already have an account registered on this number. Please use Log In instead.");
      await sendCivilServantsMenu(to, session);
      return;
    }
    // If Register for the first time, follow already developed process.
    session.step = "optin";
    await sendOptIn(to);
  }
  else if (id === "forgot_pin") {
    if (session.step !== "civil_servants_choice") return;

    const lockoutMinutes = getLoginLockoutMinutesRemaining(to);
    if (lockoutMinutes > 0) {
      await sendTextMessage(to, `Too many incorrect attempts. Your account is temporarily locked. Please try again in ${lockoutMinutes} minute(s).`);
      return;
    }

    session.step = "forgot_pin";
    session.isPinReset = true; // marks this as a reset flow, not fresh registration
    session.otp = generateFiveDigitCode();
    session.otpAttempts = 0;
    await sendTextMessage(to, "A new OTP has been sent to your registered mobile number.\n\nPlease enter the OTP:");

    // TODO: remove once a real SMS/backend delivers this. Simulated
    // delivery arrives as a separate WhatsApp message 5 seconds later,
    // matching the Approval Code / registration OTP / Verification Code
    // pattern.
    const otpForDelivery = session.otp;
    setTimeout(async () => {
      try {
        // Guard against a stale delivery if the user restarted this
        // flow (and got a new OTP) before this fires.
        if (userSessions[to] && userSessions[to].otp === otpForDelivery) {
          await sendTextMessage(to, `OTP ${otpForDelivery}`);
        }
      } catch (err) {
        // Ignore errors in this simulated delayed delivery
      }
    }, 5000);
  }
  else if (id === "opt_out") {
    if (session.step !== "civil_servants_choice") return;

    const lockoutMinutes = getLoginLockoutMinutesRemaining(to);
    if (lockoutMinutes > 0) {
      await sendTextMessage(to, `Too many incorrect attempts. Your account is temporarily locked. Please try again in ${lockoutMinutes} minute(s).`);
      return;
    }

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
    session.otp = generateFiveDigitCode();
    session.otpAttempts = 0;
    session.step = "enter_otp";

    await sendTextMessage(to, "An OTP has been sent to your M-Pesa number.\n\nPlease enter the OTP:");

    // TODO: remove once a real SMS/backend delivers this. Simulated
    // delivery arrives as a separate WhatsApp message 5 seconds later,
    // matching the Approval Code pattern, so the flow can be tested
    // end-to-end without checking server logs.
    const otpForDelivery = session.otp;
    setTimeout(async () => {
      try {
        // Guard against a stale delivery if the user restarted
        // registration (and got a new OTP) before this fires.
        if (userSessions[to] && userSessions[to].otp === otpForDelivery) {
          await sendTextMessage(to, `OTP ${otpForDelivery}`);
        }
      } catch (err) {
        // Ignore errors in this simulated delayed delivery
      }
    }, 5000);
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
    session.step = "enter_approval_code";
    await sendTextMessage(to, "Enter Approval Code:");
  }
  // ==================== CANCEL LOAN ====================
  else if (id === "cancel_loan") {
    await sendCancelLoanConfirm(to, session);
  }
  else if (id === "cancel_loan_yes") {
    // Mark status BEFORE deleting from currentLoans — since
    // loanApplications now holds a reference to this same object (see
    // fix above), this is what keeps the historical record accurate
    // after the object is no longer the "current" loan.
    if (currentLoans[to]) {
      currentLoans[to].status = "cancelled";
      currentLoans[to].cancelledAt = new Date().toISOString();
    }
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

    // ITEM 8: idempotency guard. Without this, a double-tap on "Proceed"
    // (or a duplicate webhook delivery of the same tap) could trigger
    // the M-Pesa STK Push — and the resulting installmentsPaid update —
    // TWICE for what the user experienced as a single action. This is a
    // well-known class of bug in payment flows generally. The flag is
    // cleared in every exit path below (success, failure, or error) so
    // it can never get stuck "true" forever.
    if (loan.paymentInProgress) {
      await sendTextMessage(to, "Your payment is already being processed. Please wait.");
      return;
    }
    loan.paymentInProgress = true;

    let payAmount;
    try {
      payAmount = loan.breakdown.monthlyInstallment * installments;
      const stkResult = await triggerMpesaStkPush(to, payAmount);

      if (!stkResult.success) {
        await sendTextMessage(to, "Payment could not be processed. Please try again.");
        return;
      }

      loan.installmentsPaid += installments;
      const { remainingBalance } = calculateLoanBalance(loan.breakdown.monthlyInstallment, loan.tenureMonths, loan.installmentsPaid);
      const isFullyPaid = loan.installmentsPaid >= loan.tenureMonths;

      if (isFullyPaid) {
        loan.status = "paid";
      }
      delete session.pendingPaymentInstallments;

      if (isFullyPaid) {
        await sendTextMessage(to, `Your installment of KES ${payAmount.toLocaleString()} Ref: ${loan.refNo} has been paid. Your loan has been fully paid. Thank you for using MyMobi services.`);
        // Loan fully settled — that "session" with this loan is over, so
        // send the user back to Welcome/Home rather than the Main Menu.
        await sendWelcome(to);
      } else {
        await sendTextMessage(to, `Your installment of KES ${payAmount.toLocaleString()} Ref: ${loan.refNo} has been paid. You have a loan balance of KES ${remainingBalance.toLocaleString()}. Thank you for using MyMobi services.`);
        // Balance remains — keep the user in the Main Menu since they may
        // still have more loan-related actions available.
        await sendMainMenu(to, session);
      }
    } finally {
      loan.paymentInProgress = false;
    }
  }
  else if (id === "payslip_menu") {
    await sendPayslipMonthsPrompt(to, session);
  }
  else if (id === "loan_statement_menu") {
    const loan = getRelevantLoanForStatement(to);
    if (!loan) {
      await sendTextMessage(to, "You have no loan on record.");
      await sendMainMenu(to, session);
      return;
    }
    await sendDocumentConfirm(to, session, "loan_statement", "Loan Statement", DOCUMENT_COST_PER_UNIT);
  }
  else if (id === "loan_clearance_menu") {
    const loan = findMostRecentPaidLoan(to);
    if (!loan) {
      // Distinguish "you have a loan but haven't finished paying it"
      // from "you've never had a loan" — the former gets a specific,
      // actionable message pointing at Pay Loan.
      const outstandingLoan = currentLoans[to] && currentLoans[to].status === "approved";
      if (outstandingLoan) {
        await sendTextMessage(to, "You have an outstanding loan balance. Pay Loan to download Loan Clearance Letter.");
      } else {
        await sendTextMessage(to, "You have no loan on record for a clearance letter.");
      }
      await sendMainMenu(to, session);
      return;
    }
    await sendDocumentConfirm(to, session, "loan_clearance", "Loan Clearance Letter", DOCUMENT_COST_PER_UNIT);
  }
  else if (id === "document_cancel") {
    delete session.pendingDocumentType;
    delete session.pendingDocumentCost;
    delete session.pendingDocumentMonths;
    await sendMainMenu(to, session);
  }
  else if (id === "document_accept") {
    const docType = session.pendingDocumentType;
    const cost = session.pendingDocumentCost;
    const user = registeredUsers[to];

    if (!docType || !cost || !user) {
      await sendTextMessage(to, "That request has expired. Let's start again.");
      await sendMainMenu(to, session);
      return;
    }

    // Re-verify eligibility BEFORE taking payment, so we never charge
    // for something we can no longer deliver (e.g. the loan's status
    // changed between the confirmation screen and now).
    let loanForDoc = null;
    if (docType === "loan_statement") {
      loanForDoc = getRelevantLoanForStatement(to);
      if (!loanForDoc) {
        await sendTextMessage(to, "Your loan record is no longer available. No payment has been taken.");
        delete session.pendingDocumentType;
        delete session.pendingDocumentCost;
        delete session.pendingDocumentMonths;
        await sendMainMenu(to, session);
        return;
      }
    } else if (docType === "loan_clearance") {
      loanForDoc = findMostRecentPaidLoan(to);
      if (!loanForDoc) {
        await sendTextMessage(to, "Your fully paid loan record is no longer available. No payment has been taken.");
        delete session.pendingDocumentType;
        delete session.pendingDocumentCost;
        delete session.pendingDocumentMonths;
        await sendMainMenu(to, session);
        return;
      }
    }

    // Informational only — a real STK Push prompts for the M-Pesa PIN
    // natively on the user's own phone, never via WhatsApp chat text.
    await sendTextMessage(to, `You are about to pay KES ${cost.toFixed(2)} to MyMobi account XXXXX. Please enter your Mpesa PIN.`);

    const stkResult = await triggerMpesaStkPush(to, cost);
    if (!stkResult.success) {
      await sendTextMessage(to, "Payment could not be processed. Please try again.");
      return;
    }

    let html, docTitle;
    if (docType === "payslip") {
      const months = session.pendingDocumentMonths || 1;
      html = generatePayslipHtml(user, months);
      docTitle = "Payslip";
    } else if (docType === "loan_statement") {
      html = generateLoanStatementHtml(user, loanForDoc);
      docTitle = "Loan Statement";
    } else if (docType === "loan_clearance") {
      html = generateLoanClearanceHtml(user, loanForDoc);
      docTitle = "Loan Clearance Letter";
    }

    const token = generateDocumentToken();
    documentStore[token] = {
      to,
      docType,
      upn: user.upn,
      html,
      createdAt: Date.now(),
      failedAttempts: 0
    };

    const link = `${BASE_URL}/documents/${token}`;
    logInfo('document_generated', { to, docType, token });
    await sendTextMessage(to, `Please click on this link to access your ${docTitle} ${link}`);

    delete session.pendingDocumentType;
    delete session.pendingDocumentCost;
    delete session.pendingDocumentMonths;

    await sendMainMenu(to, session);
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
    // Clear the pending 60-second inactivity timer right away — otherwise
    // it would still fire later and send a confusing "session timed out"
    // message to a user who already logged out.
    if (userSessions[to] && userSessions[to].timeoutId) {
      clearTimeout(userSessions[to].timeoutId);
    }

    setTimeout(() => {
      delete userSessions[to];
      sendTextMessage(to, "You have successfully logged out.").catch(() => {
        // Ignore errors sending the logout confirmation
      });
    }, 3000);
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
      if (!isValidUpn(cleanText)) {
        await sendTextMessage(to, UPN_ERROR_MESSAGE);
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
      if (!isValidNationalId(cleanText)) {
        await sendTextMessage(to, NATIONAL_ID_ERROR_MESSAGE);
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
      if (!isValidMobileNumber(cleanText)) {
        await sendTextMessage(to, MOBILE_NUMBER_ERROR_MESSAGE);
        return;
      }
      session.mobileNumber = cleanText;
      await sendConfirmation(to, session);
      return;
    }
  }

  // =====================================================
  // DOCUMENTS: PAYSLIP MONTHS ENTRY
  // =====================================================
  if (step === "enter_payslip_months") {
    const months = parseInt(cleanText, 10);

    if (!/^\d+$/.test(cleanText) || months < 1 || months > 12) {
      await sendTextMessage(to, "Please enter a number between 1 and 12.");
      return;
    }

    const cost = DOCUMENT_COST_PER_UNIT * months;
    session.pendingDocumentMonths = months;
    await sendDocumentConfirm(to, session, "payslip", `Payslip for the last ${months} month${months > 1 ? 's' : ''}`, cost);
    return;
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

    if (amount < 1000) {
      await sendTextMessage(to, "Minimum loan amount is KES 1,000. Please enter a higher amount.");
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

    // SECURITY FIX: this used to accept ANY non-empty text here — no
    // format check, and critically, no comparison against anything.
    // Meanwhile PIN is cryptographically verified against a stored hash
    // with attempt limits. Payroll Number is effectively the same
    // identifier as the UPN collected at registration, so it now has to
    // actually MATCH the person's registered UPN — turning this from a
    // no-op text box into a genuine identity check, consistent with how
    // every other sensitive step in this app already works.
    const registeredUser = registeredUsers[to];

    if (!isValidUpn(cleanText)) {
      await sendTextMessage(to, UPN_ERROR_MESSAGE);
      return;
    }

    if (!registeredUser || cleanText !== registeredUser.upn) {
      session.payrollNumberAttempts = (session.payrollNumberAttempts || 0) + 1;

      if (session.payrollNumberAttempts >= 3) {
        await sendTextMessage(to, "Too many incorrect attempts. Your loan application has been cancelled for your security.");
        delete session.payrollNumberAttempts;
        await sendEmergencyLoanMenu(to, session);
        return;
      }

      const attemptsLeft = 3 - session.payrollNumberAttempts;
      await sendTextMessage(to, `That Payroll Number doesn't match our records. You have ${attemptsLeft} attempt(s) remaining.`);
      return;
    }

    delete session.payrollNumberAttempts;
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
    // FIX (found while building Loan Statement): this used to push a
    // point-in-time COPY ({ ...currentLoans[to] }) — meaning any later
    // change (approved, paid, installments, cancellation) never showed
    // up here, silently going stale. Now it stores a reference to the
    // SAME object, so both always agree — critical once this data gets
    // shown to a user in an official-looking Loan Statement.
    if (!loanApplications[to]) loanApplications[to] = [];
    loanApplications[to].push(currentLoans[to]);

    logInfo('loan_application_submitted', { to, loanAmount: session.loanAmount, tenureMonths: session.loanTenureMonths, refNo, approvalCode });

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
    //
    // BUG FIX #12: this used to fire unconditionally 5 seconds later,
    // even if the loan had since been cancelled or replaced — sending a
    // stale approval code for a loan that no longer exists, and
    // re-showing the menu out of context. Now it checks the loan is
    // still the SAME one, still pending, before doing anything.
    setTimeout(async () => {
      try {
        const stillPending = currentLoans[to] && currentLoans[to].refNo === refNo && currentLoans[to].status === "pending_approval";
        if (!stillPending) {
          logInfo('stale_approval_code_delivery_skipped', { to, refNo });
          return;
        }
        await sendTextMessage(to, `Approval Code ${approvalCode}`);
        await sendEmergencyLoanMenu(to, session);
      } catch (err) {
        // Ignore errors in this simulated delayed delivery
      }
    }, 5000);

    return;
  }

  // =====================================================
  // APPROVE LOAN: APPROVAL CODE + PAYROLL NUMBER
  // =====================================================
  // BUG FIX #9: order swapped per user feedback — the approval code is
  // the thing the user JUST received (via the simulated SMS message),
  // so they naturally want to enter that first. Previously Payroll
  // Number was asked first, which meant the code the user was holding
  // onto got typed into the wrong prompt, or the payroll prompt arrived
  // confusingly after they'd already entered the code. Approval Code is
  // now validated first; Payroll Number is asked for only after a
  // correct code, and completing that is what finalizes the approval.
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

    logInfo('approval_code_check', { to, refNo: loan.refNo, received: cleanText, expected: loan.approvalCode, matched: cleanText === loan.approvalCode });

    if (cleanText === loan.approvalCode) {
      session.step = "enter_approval_payroll_number";
      await sendTextMessage(to, "Enter Payroll Number:");
    } else {
      loan.approvalCodeAttempts = (loan.approvalCodeAttempts || 0) + 1;

      if (loan.approvalCodeAttempts >= 3) {
        await sendTextMessage(to, "Too many incorrect attempts. Please try again later.");
        await sendEmergencyLoanMenu(to, session);
      } else {
        const attemptsLeft = 3 - loan.approvalCodeAttempts;
        await sendTextMessage(to, `Incorrect code. You have ${attemptsLeft} attempt(s) remaining.`);
      }
    }
    return;
  }

  if (step === "enter_approval_payroll_number") {
    const loan = currentLoans[to];

    if (!loan || loan.status !== "pending_approval") {
      await sendTextMessage(to, "That loan application is no longer pending. Let's start again.");
      await sendEmergencyLoanMenu(to, session);
      return;
    }

    if (!cleanText) {
      await sendTextMessage(to, "Please enter your Payroll Number.");
      return;
    }

    // SECURITY FIX: same gap as Apply Loan's payroll number step — this
    // used to approve the loan on ANY non-empty text, with no check
    // against anything. Now it must match the registered UPN, with the
    // same 3-attempt limit used everywhere else in this app.
    const registeredUser = registeredUsers[to];

    if (!isValidUpn(cleanText)) {
      await sendTextMessage(to, UPN_ERROR_MESSAGE);
      return;
    }

    if (!registeredUser || cleanText !== registeredUser.upn) {
      loan.approvalPayrollAttempts = (loan.approvalPayrollAttempts || 0) + 1;

      if (loan.approvalPayrollAttempts >= 3) {
        await sendTextMessage(to, "Too many incorrect attempts. Please try approving your loan again later.");
        await sendEmergencyLoanMenu(to, session);
        return;
      }

      const attemptsLeft = 3 - loan.approvalPayrollAttempts;
      await sendTextMessage(to, `That Payroll Number doesn't match our records. You have ${attemptsLeft} attempt(s) remaining.`);
      return;
    }

    session.approvalPayrollNumber = cleanText;
    loan.status = "approved";
    loan.approvedAt = new Date().toISOString();
    delete session.approvalPayrollNumber;

    await sendTextMessage(to, "Your loan approval has been received and is being processed. Please wait for an SMS notification from MyMobi.");
    await sendWelcome(to);
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

    // Same numeric validation as the initial KYC entry — an edit
    // shouldn't be able to introduce a non-numeric value that the
    // first-time entry would have rejected.
    if (field === "upn" && !isValidUpn(cleanText)) {
      await sendTextMessage(to, UPN_ERROR_MESSAGE);
      return;
    }
    if (field === "nationalid" && !isValidNationalId(cleanText)) {
      await sendTextMessage(to, NATIONAL_ID_ERROR_MESSAGE);
      return;
    }
    if (field === "mobilenumber" && !isValidMobileNumber(cleanText)) {
      await sendTextMessage(to, MOBILE_NUMBER_ERROR_MESSAGE);
      return;
    }

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
  // LOG IN: UPN + PIN (multi-channel — no verification code step)
  // =====================================================
  if (step === "login_enter_upn") {
    if (!isValidUpn(cleanText)) {
      await sendTextMessage(to, UPN_ERROR_MESSAGE);
      return;
    }

    const user = registeredUsers[to];

    // "Check if correct with back end" — for now this can only check
    // OUR local WhatsApp-linked record (see the scoping note: a real
    // shared backend across USSD/PWA/WhatsApp is a separate, larger
    // integration). A cross-channel user without a local WhatsApp
    // record will fail here until that integration exists.
    if (!user || cleanText !== user.upn) {
      session.loginAttempts = (session.loginAttempts || 0) + 1;

      if (session.loginAttempts >= MAX_LOGIN_ATTEMPTS) {
        applyLoginLockout(to);
        await sendTextMessage(to, "Too many incorrect attempts. Your account has been temporarily locked for 10 minutes.");
        delete userSessions[to];
        return;
      }

      const attemptsLeft = MAX_LOGIN_ATTEMPTS - session.loginAttempts;
      await sendTextMessage(to, `UPN not recognized. You have ${attemptsLeft} attempt(s) remaining.`);
      return;
    }

    session.step = "login_enter_pin";
    await sendTextMessage(to, "Enter PIN:");
    return;
  }

  if (step === "login_enter_pin") {
    if (!/^\d{5}$/.test(cleanText)) {
      await sendTextMessage(to, "Invalid PIN. Please enter exactly 5 digits.");
      return;
    }

    const user = registeredUsers[to];

    if (!user || !(await verifyPin(cleanText, user.pin))) {
      session.loginAttempts = (session.loginAttempts || 0) + 1;

      if (session.loginAttempts >= MAX_LOGIN_ATTEMPTS) {
        applyLoginLockout(to);
        await sendTextMessage(to, "Too many incorrect attempts. Your account has been temporarily locked for 10 minutes.");
        delete userSessions[to];
        return;
      }

      const attemptsLeft = MAX_LOGIN_ATTEMPTS - session.loginAttempts;
      await sendTextMessage(to, `Incorrect PIN. You have ${attemptsLeft} attempt(s) remaining.`);
      return;
    }

    // Correct UPN + PIN — Verification Code is still required before
    // Main Menu (restored per explicit correction — this step stays).
    session.step = "login_enter_verification_code";
    session.verificationCode = generateFiveDigitCode();
    await sendTextMessage(to, "Enter Verification Code:");

    // TODO: remove once a real SMS/backend delivers this. Simulated
    // delivery arrives as a separate WhatsApp message 5 seconds later,
    // matching the Approval Code / OTP pattern used everywhere else.
    const codeForDelivery = session.verificationCode;
    setTimeout(async () => {
      try {
        if (userSessions[to] && userSessions[to].verificationCode === codeForDelivery) {
          await sendTextMessage(to, `Verification Code ${codeForDelivery}`);
        }
      } catch (err) {
        // Ignore errors in this simulated delayed delivery
      }
    }, 5000);
    return;
  }

  if (step === "login_enter_verification_code") {
    if (!/^\d{5}$/.test(cleanText)) {
      await sendTextMessage(to, "Invalid code. Please enter a 5-digit verification code.");
      return;
    }

    if (cleanText !== session.verificationCode) {
      session.loginAttempts = (session.loginAttempts || 0) + 1;

      if (session.loginAttempts >= MAX_LOGIN_ATTEMPTS) {
        applyLoginLockout(to);
        await sendTextMessage(to, "Too many incorrect attempts. Your account has been temporarily locked for 10 minutes.");
        delete userSessions[to];
        return;
      }

      const attemptsLeft = MAX_LOGIN_ATTEMPTS - session.loginAttempts;
      await sendTextMessage(to, `Incorrect code. You have ${attemptsLeft} attempt(s) remaining.`);
      return;
    }

    // UPN + PIN + Verification Code all correct — now proceed to Main Menu.
    delete session.loginAttempts;
    delete session.verificationCode;
    session.isAuthenticated = true;
    await sendMainMenu(to, session);
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
      await sendCivilServantsMenu(to, session);
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

    if (await verifyPin(cleanText, user.pin)) {
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
//      recipient — BUT ONLY between messages the BOT chains together on
//      its own within a single incoming-message "turn" (e.g. "Registration
//      Complete" immediately followed by "Main Menu"). The FIRST reply to
//      a fresh incoming message/button tap always sends immediately,
//      since real time already passed while the person was reading the
//      previous screen and deciding what to tap — throttling that first
//      reply only adds a perceptible, pointless lag between tap and
//      response (BUG FIX #10).
//   3. Automatically retries with exponential backoff specifically when
//      WhatsApp responds with a rate-limit error (131056 or 130429),
//      instead of silently dropping the message and leaving the session
//      stuck (which is what caused "Registration Complete" to vanish).
//
// This is the single place that governs message pacing — nowhere else
// in the code should call axios directly or add its own delays.
//
// resetSendTurn(to) is called once at the top of the webhook handler,
// for every fresh incoming message, before any reply is sent.

const MIN_GAP_MS = 3000;          // minimum spacing between CHAINED messages in the same turn
const MAX_SEND_RETRIES = 4;
const BASE_RETRY_DELAY_MS = 4000; // doubles each retry: 4s, 8s, 16s, 32s

const recipientQueues = new Map(); // "to" phone number -> { tail: Promise, lastSentAt: number, turnMessageCount: number }

// Prevent recipientQueues from growing forever on a long-running server —
// numbers that haven't messaged in a while are safe to forget, since a
// fresh entry is created automatically the next time they do.
const QUEUE_ENTRY_TTL_MS = 60 * 60 * 1000; // 1 hour
const queueCleanupInterval = setInterval(() => {
  const now = Date.now();
  for (const [to, state] of recipientQueues) {
    if (now - state.lastSentAt > QUEUE_ENTRY_TTL_MS) {
      recipientQueues.delete(to);
    }
  }
}, 15 * 60 * 1000); // sweep every 15 minutes
// .unref() means this background housekeeping timer, on its own, never
// keeps the process alive — the live HTTP server (app.listen) is what
// actually does that during normal operation. This only matters when
// nothing else is holding the event loop open: it lets a test process
// that require()'s this file (without starting a real server) exit
// cleanly, and it's the correct pattern for graceful shutdown generally.
queueCleanupInterval.unref();

function resetSendTurn(to) {
  const state = recipientQueues.get(to) || { tail: Promise.resolve(), lastSentAt: 0, turnMessageCount: 0 };
  state.turnMessageCount = 0;
  recipientQueues.set(to, state);
}

function sendMessage(to, payload) {
  const state = recipientQueues.get(to) || { tail: Promise.resolve(), lastSentAt: 0, turnMessageCount: 0 };

  // Captured synchronously at enqueue time, in call order — the Nth
  // sendMessage() call within the current turn knows its own position
  // even though the actual send is deferred until the queue reaches it.
  const isFirstInTurn = state.turnMessageCount === 0;
  state.turnMessageCount++;

  const task = state.tail
    .catch(() => {}) // never let a prior failure break the chain for this recipient
    .then(async () => {
      if (!isFirstInTurn) {
        const elapsed = Date.now() - state.lastSentAt;
        if (elapsed < MIN_GAP_MS) {
          await sleep(MIN_GAP_MS - elapsed);
        }
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
      headers: { Authorization: `Bearer ${ACCESS_TOKEN}` },
      timeout: 10000 // ITEM 5: don't let a hung WhatsApp API call block indefinitely
    });
  } catch (err) {
    const errorCode = err.response?.data?.error?.code;
    const isRateLimitError = errorCode === 131056 || errorCode === 130429;

    if (isRateLimitError && attempt < MAX_SEND_RETRIES) {
      const delay = BASE_RETRY_DELAY_MS * Math.pow(2, attempt);
      logWarn('rate_limited_retry', { to, errorCode, delayMs: delay, attempt: attempt + 1, maxRetries: MAX_SEND_RETRIES });
      await sleep(delay);
      return sendWithRetry(to, payload, attempt + 1);
    }

    logError('send_failed', { to, error: err.response?.data || err.message });
    // Deliberately not re-thrown: a failed send (after retries) shouldn't
    // crash the webhook handler. Session state for this bot is already
    // updated in memory before messages are sent (see
    // sendRegistrationComplete / sendPinResetComplete), so a delivery
    // failure doesn't strand a user in an inconsistent step.
  }
}

// ==================== ITEM 3: CRASH PROTECTION ====================
// Without these, an error that slips past a try/catch anywhere in the
// app (an "unhandled" rejection or exception) crashes the ENTIRE Node
// process — dropping every user's session at once, not just the one
// request that hit the error. These log the error and keep the server
// running instead.
process.on('unhandledRejection', (reason) => {
  // Deliberately plain console.error, not logInfo/logError: these two
  // handlers catch truly unexpected, catastrophic errors, and calling
  // back into other app code (even a logging helper) from inside a
  // crash handler carries some risk if the crash itself corrupted
  // shared state. Simplicity here is a feature, not an oversight.
  console.error('Unhandled Promise Rejection:', reason);
});

process.on('uncaughtException', (err) => {
  console.error('Uncaught Exception:', err);
  // Deliberately NOT exiting: for this bot, staying up and serving other
  // users' sessions is preferable to a hard crash over one bad request.
});

// ITEM 10: only actually start listening when this file is run directly
// (`node index.js`), not when it's require()'d by a test file — this is
// the standard Node.js pattern for making a server file testable without
// tests fighting over a real port or hanging the test run.
if (require.main === module) {
  app.listen(PORT, () => logInfo('server_started', { port: PORT }));
}

// Exported ONLY for automated tests (see tests/logic.test.js). These are
// the pure, self-contained pieces of business logic — no WhatsApp
// sending, no session state — which is exactly what makes them safe and
// meaningful to unit test without a real WhatsApp connection.
module.exports = {
  isValidUpn,
  isValidNationalId,
  isValidMobileNumber,
  generateLoanRefNo,
  generateApprovalCode,
  generateFiveDigitCode,
  computeDueDate,
  calculateLoanBalance,
  getLoanBreakdown,
  hashPin,
  verifyPin,
  PLATFORM_FEE_PER_MONTH,
  escapeHtml,
  generatePayslipHtml,
  generateLoanStatementHtml,
  generateLoanClearanceHtml,
  DOCUMENT_COST_PER_UNIT,
  getLoginLockoutMinutesRemaining,
  applyLoginLockout,
  MAX_LOGIN_ATTEMPTS
};
