package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.LoanBreakdown;
import org.springframework.stereotype.Service;

@Service
public class LoanCalculationService {

    private static final int PLATFORM_FEE_PER_MONTH = 150;

    public LoanBreakdown calculateBreakdown(int loanAmount, int tenureMonths) {
        return new LoanBreakdown(
                loanAmount,
                2943,
                32057,
                14442,
                PLATFORM_FEE_PER_MONTH * tenureMonths
        );
    }
}