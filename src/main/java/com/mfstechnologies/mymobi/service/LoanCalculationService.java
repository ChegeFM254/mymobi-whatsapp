package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.LoanBreakdown;

/**
 * WORKSTREAM F (mock service abstraction layer): a clean seam for loan
 * breakdown calculation - everywhere in the app that needs to compute a
 * loan's fees/disbursement/installment calls this interface, rather
 * than embedding the calculation inline. Swapping in a real HRIS/
 * underwriting integration later means implementing this interface
 * once, not touching every call site again.
 */
public interface LoanCalculationService {

    /**
     * Computes the full fee/disbursement/installment breakdown for a
     * requested loan amount and tenure.
     *
     * @param loanAmount   the requested loan amount in KES
     * @param tenureMonths the repayment period in months
     */
    LoanBreakdown calculateBreakdown(int loanAmount, int tenureMonths);
}
