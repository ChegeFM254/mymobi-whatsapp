package com.mfstechnologies.mymobi.model;

import jakarta.persistence.Embeddable;

/**
 * WORKSTREAM C (persistence): marked @Embeddable so it can be stored
 * directly on the loans table via Loan's @Embedded field, rather than
 * needing its own separate table. Hibernate has supported records as
 * embeddables since 6.2 (we're on 7.4.1), automatically using the
 * record's own canonical constructor for instantiation - no custom
 * EmbeddableInstantiator needed. Note this is a Hibernate-specific
 * extension, not part of the JPA spec itself, but that's fine since
 * this project isn't targeting portability across JPA providers.
 *
 * WORKSTREAM F (mock service abstraction layer): fields renamed to
 * match MyMobi's actual "Straight Interest Method" terminology
 * (upfrontFee -> totalDeductions, monthlyInstallment ->
 * installmentPerMonth), and totalRepayment is new - the period total
 * including the GoK charge (platformFee), matching the reference
 * spreadsheet's "Total Repayment (MFS + GoK)" row. platformFee
 * deliberately stays its own field/display line rather than being
 * folded into installmentPerMonth.
 */
@Embeddable
public record LoanBreakdown(
        int loanAmount,
        int totalDeductions,
        int disbursement,
        int installmentPerMonth,
        int totalRepayment,
        int platformFee
) {
}
