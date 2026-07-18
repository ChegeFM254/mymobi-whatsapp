// Automated tests for MyMobi's pure business logic — the parts of the
// code with no WhatsApp sending or session-state side effects, which
// makes them safe and meaningful to test in isolation.
//
// Run with: npm test
// Uses Node's built-in test runner (Node 18+) — no extra dependencies
// needed just to run tests.

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const {
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
  MAX_LOGIN_ATTEMPTS,
  verifyLoginCredentials,
  registeredUsers
} = require(path.join(__dirname, '..', 'index.js'));

// ==================== UPN VALIDATION ====================
test('isValidUpn: accepts valid UPNs starting with 1 or 2', () => {
  assert.equal(isValidUpn('12345'), true);
  assert.equal(isValidUpn('22345678901'), true); // 11 digits, starts 2
  assert.equal(isValidUpn('1'), true); // 1 digit is technically valid (up to 11)
});

test('isValidUpn: rejects wrong starting digit', () => {
  assert.equal(isValidUpn('32345'), false);
  assert.equal(isValidUpn('02345'), false);
  assert.equal(isValidUpn('92345678'), false);
});

test('isValidUpn: rejects more than 11 digits', () => {
  assert.equal(isValidUpn('123456789012'), false); // 12 digits
});

test('isValidUpn: rejects non-numeric input', () => {
  assert.equal(isValidUpn('1abcd'), false);
  assert.equal(isValidUpn(''), false);
});

// ==================== NATIONAL ID VALIDATION ====================
test('isValidNationalId: accepts exactly 8 digits not starting with 0', () => {
  assert.equal(isValidNationalId('12345678'), true);
  assert.equal(isValidNationalId('99999999'), true);
});

test('isValidNationalId: rejects starting with 0', () => {
  assert.equal(isValidNationalId('02345678'), false);
});

test('isValidNationalId: rejects wrong length', () => {
  assert.equal(isValidNationalId('1234567'), false);   // 7 digits
  assert.equal(isValidNationalId('123456789'), false); // 9 digits
});

// ==================== MOBILE NUMBER VALIDATION ====================
test('isValidMobileNumber: accepts 10 digits starting with 0', () => {
  assert.equal(isValidMobileNumber('0722730336'), true);
});

test('isValidMobileNumber: accepts 12 digits starting with 254', () => {
  assert.equal(isValidMobileNumber('254722730336'), true);
});

test('isValidMobileNumber: rejects missing prefix', () => {
  assert.equal(isValidMobileNumber('722730336'), false);
});

test('isValidMobileNumber: rejects wrong length', () => {
  assert.equal(isValidMobileNumber('0712345'), false);
  assert.equal(isValidMobileNumber('07123456789'), false);
});

// ==================== CODE GENERATORS ====================
test('generateApprovalCode: always produces exactly 6 digits', () => {
  for (let i = 0; i < 50; i++) {
    const code = generateApprovalCode();
    assert.match(code, /^\d{6}$/, `Expected 6 digits, got "${code}"`);
  }
});

test('generateFiveDigitCode: always produces exactly 5 digits', () => {
  for (let i = 0; i < 50; i++) {
    const code = generateFiveDigitCode();
    assert.match(code, /^\d{5}$/, `Expected 5 digits, got "${code}"`);
  }
});

test('generateLoanRefNo: produces a non-empty reference code', () => {
  const ref = generateLoanRefNo();
  assert.ok(ref.length > 0);
});

// ==================== DUE DATE ====================
test('computeDueDate: adds the correct number of months', () => {
  const now = new Date();
  const due = new Date(computeDueDate(3));
  const monthDiff = (due.getFullYear() - now.getFullYear()) * 12 + (due.getMonth() - now.getMonth());
  assert.equal(monthDiff, 3);
});

// ==================== LOAN BALANCE MATH ====================
// These figures were explicitly confirmed against a real conversation
// with the product owner: a 3-month loan with a monthly installment of
// KES 14,442 should produce a balance of 28,884 after 1 payment, 14,442
// after 2, and 0 after all 3 are paid.
test('calculateLoanBalance: matches confirmed figures for a 3-month loan', () => {
  const monthlyInstallment = 14442;
  const tenureMonths = 3;

  assert.equal(calculateLoanBalance(monthlyInstallment, tenureMonths, 1).remainingBalance, 28884);
  assert.equal(calculateLoanBalance(monthlyInstallment, tenureMonths, 2).remainingBalance, 14442);
  assert.equal(calculateLoanBalance(monthlyInstallment, tenureMonths, 3).remainingBalance, 0);
});

test('calculateLoanBalance: total obligation is monthly installment times tenure', () => {
  const result = calculateLoanBalance(1000, 3, 0);
  assert.equal(result.totalObligation, 3000);
  assert.equal(result.remainingBalance, 3000);
});

// ==================== PLATFORM FEE ====================
// Confirmed rule: KES 150 per month of tenure (1mo=150, 2mo=300, 3mo=450).
test('getLoanBreakdown: platform fee matches confirmed per-month rate', async () => {
  assert.equal(PLATFORM_FEE_PER_MONTH, 150);

  const oneMonth = await getLoanBreakdown(35000, 1);
  const twoMonth = await getLoanBreakdown(35000, 2);
  const threeMonth = await getLoanBreakdown(35000, 3);

  assert.equal(oneMonth.platformFee, 150);
  assert.equal(twoMonth.platformFee, 300);
  assert.equal(threeMonth.platformFee, 450);
});

// ==================== PIN HASHING ====================
test('hashPin + verifyPin: correct PIN verifies successfully', async () => {
  const hash = await hashPin('12345');
  const result = await verifyPin('12345', hash);
  assert.equal(result, true);
});

test('hashPin + verifyPin: wrong PIN is rejected', async () => {
  const hash = await hashPin('12345');
  const result = await verifyPin('99999', hash);
  assert.equal(result, false);
});

test('hashPin: never stores the PIN in plain text', async () => {
  const hash = await hashPin('12345');
  assert.notEqual(hash, '12345');
  assert.ok(hash.length > 20); // bcrypt hashes are always much longer than a 5-digit PIN
});

test('verifyPin: returns false (not a crash) for a missing/undefined stored hash', async () => {
  const result = await verifyPin('12345', undefined);
  assert.equal(result, false);
});

// ==================== DOCUMENT GENERATION (Payslip / Loan Statement / Loan Clearance) ====================
test('escapeHtml: neutralizes script tags (XSS protection)', () => {
  const malicious = '<script>alert("hacked")</script>';
  const escaped = escapeHtml(malicious);
  assert.ok(!escaped.includes('<script>'));
  assert.ok(escaped.includes('&lt;script&gt;'));
});

test('escapeHtml: handles undefined/null without crashing', () => {
  assert.equal(escapeHtml(undefined), '');
  assert.equal(escapeHtml(null), '');
});

test('generatePayslipHtml: a malicious first name cannot inject a script tag', () => {
  const user = { firstName: '<script>alert(1)</script>', lastName: 'Doe', upn: '12345' };
  const html = generatePayslipHtml(user, 3);
  assert.ok(!html.includes('<script>alert(1)</script>'), 'Raw script tag must not appear in output');
  assert.ok(html.includes('&lt;script&gt;'), 'Should appear escaped instead');
});

test('generatePayslipHtml: produces one section per requested month', () => {
  const user = { firstName: 'Jane', lastName: 'Doe', upn: '12345' };
  const html3 = generatePayslipHtml(user, 3);
  const html1 = generatePayslipHtml(user, 1);
  // 3 months should produce a longer document than 1 month
  assert.ok(html3.length > html1.length);
});

test('generateLoanStatementHtml: reflects the loan\'s actual balance and required fields', () => {
  const user = { firstName: 'Jane', lastName: 'Doe', upn: '12345' };
  const loan = {
    refNo: 'TEST1234',
    loanAmount: 35000,
    tenureMonths: 3,
    status: 'approved',
    installmentsPaid: 1,
    dueDate: '2026-10-18',
    breakdown: { monthlyInstallment: 14442 }
  };
  const html = generateLoanStatementHtml(user, loan);
  assert.ok(html.includes('28,884') || html.includes('28884'), 'Should show correct remaining balance after 1 installment');
  assert.ok(html.includes('MFS Technologies Limited'), 'Should show the lender name');
  assert.ok(html.includes('Jane') && html.includes('Doe'));
  assert.ok(html.includes('Statement Date'));
});

test('generateLoanClearanceHtml: confirms fully paid status and required fields', () => {
  const user = { firstName: 'Jane', lastName: 'Doe', upn: '12345', nationalId: '87654321' };
  const loan = {
    refNo: 'TEST5678',
    loanAmount: 20000,
    tenureMonths: 1,
    installmentsPaid: 1,
    dueDate: '2026-02-01',
    approvedAt: '2026-01-01T00:00:00.000Z',
    breakdown: { monthlyInstallment: 20000 }
  };
  const html = generateLoanClearanceHtml(user, loan);
  assert.ok(html.includes('MFS Technologies Limited'), 'Should show the lender name');
  assert.ok(html.includes('Loan Status') && html.includes('Paid'));
  assert.ok(html.includes('Letter Date'));
  assert.ok(html.includes('20,000'), 'Fully repaid loan should show the repayment amount');
});

test('DOCUMENT_COST_PER_UNIT: matches confirmed rate of KES 23.20', () => {
  assert.equal(DOCUMENT_COST_PER_UNIT, 23.20);
});

test('Payslip cost calculation: matches confirmed example (3 months = KES 69.60)', () => {
  const cost = DOCUMENT_COST_PER_UNIT * 3;
  assert.equal(cost.toFixed(2), '69.60');
});

// ==================== LOGIN LOCKOUT ====================
test('getLoginLockoutMinutesRemaining: returns 0 when no lockout is active', () => {
  assert.equal(getLoginLockoutMinutesRemaining('254700000001_test_unused'), 0);
});

test('applyLoginLockout + getLoginLockoutMinutesRemaining: reports approximately 10 minutes immediately after locking', () => {
  const testNumber = '254700000002_test';
  applyLoginLockout(testNumber);
  const remaining = getLoginLockoutMinutesRemaining(testNumber);
  assert.ok(remaining >= 9 && remaining <= 10, `Expected ~10 minutes remaining, got ${remaining}`);
});

test('MAX_LOGIN_ATTEMPTS: matches confirmed rule of 3 attempts', () => {
  assert.equal(MAX_LOGIN_ATTEMPTS, 3);
});

// ==================== LOGIN VERIFICATION (backend placeholder) ====================
test('verifyLoginCredentials: no existing record -> testing-mode bypass succeeds and creates a synthetic account', async () => {
  const testNumber = '254700000010_test';
  const result = await verifyLoginCredentials(testNumber, '12345', '54321');
  assert.equal(result.success, true);
  assert.equal(result.user.upn, '12345');
  assert.equal(result.user.isTestingBypassAccount, true);
  assert.equal(registeredUsers[testNumber].upn, '12345', 'Synthetic account should be persisted so downstream features (Payslip, etc.) find it');
});

test('verifyLoginCredentials: existing record with correct UPN+PIN succeeds using real data (no bypass)', async () => {
  const testNumber = '254700000011_test';
  registeredUsers[testNumber] = {
    firstName: 'Real', lastName: 'User', upn: '19999999',
    pin: await hashPin('11111'), status: 'active', failedPinAttempts: 0
  };
  const result = await verifyLoginCredentials(testNumber, '19999999', '11111');
  assert.equal(result.success, true);
  assert.equal(result.user.firstName, 'Real');
  assert.equal(result.user.isTestingBypassAccount, undefined, 'Should use the real record, not create a synthetic one');
});

test('verifyLoginCredentials: existing record with WRONG UPN or PIN fails (bypass does not apply once a real record exists)', async () => {
  const testNumber = '254700000012_test';
  registeredUsers[testNumber] = {
    firstName: 'Real', lastName: 'User', upn: '19999999',
    pin: await hashPin('11111'), status: 'active', failedPinAttempts: 0
  };
  const wrongPin = await verifyLoginCredentials(testNumber, '19999999', '99999');
  assert.equal(wrongPin.success, false);

  const wrongUpn = await verifyLoginCredentials(testNumber, '10000000', '11111');
  assert.equal(wrongUpn.success, false);
});
