package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.LoanBreakdown;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WORKSTREAM F (mock service abstraction layer): rewritten for
 * MockLoanCalculationService's real "Straight Interest Method"
 * calculation. The three main tests below assert the exact figures from
 * the business-provided reference spreadsheet
 * (WhatsApp_Loan_Calculations.xlsx) for all three of its worked
 * examples - this is a genuine correctness check against real business
 * data, not an arbitrary fixture. totalRepayment is new - verified
 * against the spreadsheet's "Total Repayment (MFS + GoK)" row.
 */
class LoanCalculationServiceTest {

    private final LoanCalculationService service = new MockLoanCalculationService();

    @Test
    void oneMonthTenureMatchesTheReferenceSpreadsheetExactly() {
        // Reference: 18,000 loan, 1 month tenure.
        LoanBreakdown breakdown = service.calculateBreakdown(18000, 1);

        assertThat(breakdown.loanAmount()).isEqualTo(18000);
        assertThat(breakdown.totalDeductions()).isEqualTo(1513); // excise 270 + processing 1044 + insurance 199
        assertThat(breakdown.disbursement()).isEqualTo(16487);
        assertThat(breakdown.installmentPerMonth()).isEqualTo(19350); // principal 18000 + interest 1350
        assertThat(breakdown.platformFee()).isEqualTo(150);
        assertThat(breakdown.totalRepayment()).isEqualTo(19500); // 19350 + 150
    }

    @Test
    void twoMonthTenureMatchesTheReferenceSpreadsheetExactly() {
        // Reference: 20,000 loan, 2 month tenure.
        LoanBreakdown breakdown = service.calculateBreakdown(20000, 2);

        assertThat(breakdown.loanAmount()).isEqualTo(20000);
        assertThat(breakdown.totalDeductions()).isEqualTo(1981); // excise 600 + processing 1160 + insurance 221
        assertThat(breakdown.disbursement()).isEqualTo(18019);
        assertThat(breakdown.installmentPerMonth()).isEqualTo(11500); // principal 10000 + interest 1500
        assertThat(breakdown.platformFee()).isEqualTo(300);
        assertThat(breakdown.totalRepayment()).isEqualTo(23300); // (11500 * 2) + 300
    }

    @Test
    void threeMonthTenureMatchesTheReferenceSpreadsheetExactly() {
        // Reference: 60,000 loan, 3 month tenure.
        LoanBreakdown breakdown = service.calculateBreakdown(60000, 3);

        assertThat(breakdown.loanAmount()).isEqualTo(60000);
        assertThat(breakdown.totalDeductions()).isEqualTo(6842); // excise 2700 + processing 3480 + insurance 662
        assertThat(breakdown.disbursement()).isEqualTo(53158);
        assertThat(breakdown.installmentPerMonth()).isEqualTo(24500); // principal 20000 + interest 4500
        assertThat(breakdown.platformFee()).isEqualTo(450);
        assertThat(breakdown.totalRepayment()).isEqualTo(73950); // (24500 * 3) + 450
    }

    @Test
    void insuranceFeeRoundsUpNotToNearest() {
        // 18,000 * 1.102% = 198.36 - the reference spreadsheet shows 199,
        // confirming ceiling rounding, not standard round-to-nearest
        // (which would give 198). This is embedded in totalDeductions
        // above, but called out explicitly here since it's easy to get
        // wrong.
        LoanBreakdown breakdown = service.calculateBreakdown(18000, 1);

        int exciseDuty = 270;
        int processingFee = 1044;
        int insuranceFeeIfStandardRounding = 198;
        int insuranceFeeIfCeiling = 199;

        assertThat(breakdown.totalDeductions()).isNotEqualTo(exciseDuty + processingFee + insuranceFeeIfStandardRounding);
        assertThat(breakdown.totalDeductions()).isEqualTo(exciseDuty + processingFee + insuranceFeeIfCeiling);
    }

    @Test
    void disbursementPlusTotalDeductionsAlwaysEqualsTheLoanAmount() {
        LoanBreakdown breakdown = service.calculateBreakdown(23457, 2); // deliberately not a round number

        assertThat(breakdown.totalDeductions() + breakdown.disbursement()).isEqualTo(23457);
    }

    @Test
    void totalRepaymentAlwaysEqualsInstallmentTimesTenurePlusPlatformFee() {
        LoanBreakdown breakdown = service.calculateBreakdown(23457, 2); // deliberately not a round number

        int expected = breakdown.installmentPerMonth() * 2 + breakdown.platformFee();
        assertThat(breakdown.totalRepayment()).isEqualTo(expected);
    }

    @Test
    void differentCallsProduceIndependentBreakdownInstances() {
        LoanBreakdown first = service.calculateBreakdown(18000, 1);
        LoanBreakdown second = service.calculateBreakdown(20000, 2);

        assertThat(first).isNotEqualTo(second);
    }
}
