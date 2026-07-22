package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.LoanBreakdown;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoanCalculationServiceTest {

    private final LoanCalculationService service = new LoanCalculationService();

    @Test
    void breakdownEchoesBackTheRequestedLoanAmount() {
        LoanBreakdown breakdown = service.calculateBreakdown(15000, 1);

        assertThat(breakdown.loanAmount()).isEqualTo(15000);
    }

    @Test
    void platformFeeScalesWithTenureAtKes150PerMonth() {
        assertThat(service.calculateBreakdown(15000, 1).platformFee()).isEqualTo(150);
        assertThat(service.calculateBreakdown(15000, 2).platformFee()).isEqualTo(300);
        assertThat(service.calculateBreakdown(15000, 3).platformFee()).isEqualTo(450);
    }

    @Test
    void upfrontFeeDisbursementAndInstallmentAreCurrentlyFixedPlaceholders() {
        LoanBreakdown small = service.calculateBreakdown(1000, 1);
        LoanBreakdown large = service.calculateBreakdown(60000, 3);

        assertThat(small.upfrontFee()).isEqualTo(2943);
        assertThat(large.upfrontFee()).isEqualTo(2943);

        assertThat(small.disbursement()).isEqualTo(32057);
        assertThat(large.disbursement()).isEqualTo(32057);

        assertThat(small.monthlyInstallment()).isEqualTo(14442);
        assertThat(large.monthlyInstallment()).isEqualTo(14442);
    }

    @Test
    void differentCallsProduceIndependentBreakdownInstances() {
        LoanBreakdown first = service.calculateBreakdown(15000, 1);
        LoanBreakdown second = service.calculateBreakdown(20000, 2);

        assertThat(first).isNotEqualTo(second);
    }
}
