package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.LoanBreakdown;
import org.springframework.stereotype.Service;

/**
 * WORKSTREAM F (mock service abstraction layer): stands in for a real
 * HRIS/underwriting integration that would determine the actual
 * fee/interest terms for a given person and loan.
 *
 * Implements MyMobi's "Straight Interest Method", reverse-engineered
 * and verified exactly against the business-provided reference
 * calculation (WhatsApp_Loan_Calculations.xlsx) across all three of its
 * worked examples (1/2/3-month tenures) - every intermediate and final
 * figure (excise duty, processing fee, insurance fee, total deductions,
 * disbursement, monthly installment) matches the spreadsheet exactly.
 *
 * FIXED BUG: the previous (pre-Workstream-F) implementation returned
 * the exact same hardcoded upfrontFee/disbursement/monthlyInstallment
 * (2943 / 32057 / 14442) for every loan regardless of the amount
 * actually requested. This real formula naturally produces a genuinely
 * different value for every loanAmount/tenureMonths combination.
 *
 * Rate constants (INTEREST_RATE 7.5%/month, EXCISE_DUTY_RATE 20% of
 * interest, PROCESSING_FEE_RATE 5.8%, INSURANCE_RATE 1.102%,
 * GOK_CHARGE_PER_MONTH KES 150) all come directly from the reference
 * spreadsheet, not assumed.
 *
 * Rounding: excise duty, processing fee, and insurance fee all round UP
 * (ceiling) - confirmed by the insurance fee figures in the reference
 * data (e.g. 18000 * 1.102% = 198.36, but the spreadsheet shows 199).
 * The principal-per-month split uses standard rounding, since no
 * reference example distinguishes ceiling from standard rounding there.
 *
 * LoanBreakdown field mapping: totalDeductions is the sum of all three
 * deductions (excise + processing + insurance) - these are deducted
 * BEFORE disbursement. installmentPerMonth is principal + interest per
 * month (the spreadsheet's "Total Monthly Repayment to MFS") -
 * deliberately excluding the GoK charge, which is tracked separately as
 * platformFee, matching how the two are shown as distinct line items
 * (see ScreenMessageService). totalRepayment is the full period total
 * INCLUDING the GoK charge (the spreadsheet's "Total Repayment (MFS +
 * GoK)" row) - installmentPerMonth * tenureMonths + platformFee.
 */
@Service
public class MockLoanCalculationService implements LoanCalculationService {

    private static final double INTEREST_RATE = 0.075;
    private static final double EXCISE_DUTY_RATE = 0.2;
    private static final double PROCESSING_FEE_RATE = 0.058;
    private static final double INSURANCE_RATE = 0.01102;
    private static final int GOK_CHARGE_PER_MONTH = 150;

    @Override
    public LoanBreakdown calculateBreakdown(int loanAmount, int tenureMonths) {
        double interestPerMonth = loanAmount * INTEREST_RATE;
        double interestForPeriod = interestPerMonth * tenureMonths;

        int exciseDuty = (int) Math.ceil(interestForPeriod * EXCISE_DUTY_RATE);
        int processingFee = (int) Math.ceil(loanAmount * PROCESSING_FEE_RATE);
        int insuranceFee = (int) Math.ceil(loanAmount * INSURANCE_RATE);
        int totalDeductions = exciseDuty + processingFee + insuranceFee;

        int disbursement = loanAmount - totalDeductions;

        int principalPerMonth = (int) Math.round((double) loanAmount / tenureMonths);
        int interestPerMonthRounded = (int) Math.round(interestPerMonth);
        int installmentPerMonth = principalPerMonth + interestPerMonthRounded;

        int platformFee = GOK_CHARGE_PER_MONTH * tenureMonths;
        int totalRepayment = installmentPerMonth * tenureMonths + platformFee;

        return new LoanBreakdown(loanAmount, totalDeductions, disbursement, installmentPerMonth, totalRepayment, platformFee);
    }
}
