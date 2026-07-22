package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.Loan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoanStoreTest {

    private Loan sampleLoan() {
        Loan loan = new Loan();
        loan.setLoanAmount(15000);
        loan.setTenureMonths(1);
        loan.setStatus("pending_approval");
        return loan;
    }

    @Test
    void freshlySavedLoanIsFindable() {
        LoanStore store = new LoanStore();
        store.save("254700000001", sampleLoan());

        assertThat(store.findByPhoneNumber("254700000001")).isPresent();
        assertThat(store.findByPhoneNumber("254700000001").get().getLoanAmount()).isEqualTo(15000);
    }

    @Test
    void unknownPhoneNumberIsNotFound() {
        LoanStore store = new LoanStore();

        assertThat(store.findByPhoneNumber("254700000099")).isEmpty();
    }

    @Test
    void savingAgainForTheSamePhoneNumberReplacesThePreviousLoan() {
        LoanStore store = new LoanStore();
        store.save("254700000001", sampleLoan());

        Loan replacement = sampleLoan();
        replacement.setLoanAmount(30000);
        store.save("254700000001", replacement);

        assertThat(store.findByPhoneNumber("254700000001").get().getLoanAmount()).isEqualTo(30000);
    }

    @Test
    void deletingRemovesTheLoan() {
        LoanStore store = new LoanStore();
        store.save("254700000001", sampleLoan());

        store.delete("254700000001");

        assertThat(store.findByPhoneNumber("254700000001")).isEmpty();
    }

    @Test
    void deletingAPhoneNumberWithNoLoanIsHarmless() {
        LoanStore store = new LoanStore();

        store.delete("254700000099"); // should not throw
    }

    @Test
    void loansArePerPhoneNumber() {
        LoanStore store = new LoanStore();
        store.save("254700000001", sampleLoan());

        assertThat(store.findByPhoneNumber("254700000002")).isEmpty();
    }
}
