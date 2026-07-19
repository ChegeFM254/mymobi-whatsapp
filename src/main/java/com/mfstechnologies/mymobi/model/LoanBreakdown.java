package com.mfstechnologies.mymobi.model;

public record LoanBreakdown(
        int loanAmount,
        int upfrontFee,
        int disbursement,
        int monthlyInstallment,
        int platformFee
) {
}