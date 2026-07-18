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
  PLATFORM_FEE_PER_MONTH
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
