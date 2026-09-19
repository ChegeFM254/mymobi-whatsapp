package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.testsupport.FakeRepositories;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WORKSTREAM C (persistence): rewritten for the now Postgres-backed
 * LoanStore. freshLoanStore() creates a fresh mock repository per call,
 * wired to a fake, in-memory-backed implementation (see
 * FakeRepositories), so these tests keep exercising real, stateful
 * round-trip behavior (save then find, save then delete then find)
 * exactly as they did against the old ConcurrentHashMap, rather than
 * switching to pure delegation-verification.
 */
class LoanStoreTest {

    private LoanStore freshLoanStore() {
        LoanRepository repository = mock(LoanRepository.class);
        FakeRepositories.wireAsInMemoryStore(repository, Loan::getPhoneNumber);
        return new LoanStore(repository);
    }

    private Loan sampleLoan() {
        Loan loan = new Loan();
        loan.setLoanAmount(15000);
        loan.setTenureMonths(1);
        loan.setStatus("pending_approval");
        return loan;
    }

    @Test
    void freshlySavedLoanIsFindable() {
        LoanStore store = freshLoanStore();
        store.save("254700000001", sampleLoan());

        assertThat(store.findByPhoneNumber("254700000001")).isPresent();
        assertThat(store.findByPhoneNumber("254700000001").get().getLoanAmount()).isEqualTo(15000);
    }

    @Test
    void unknownPhoneNumberIsNotFound() {
        LoanStore store = freshLoanStore();

        assertThat(store.findByPhoneNumber("254700000099")).isEmpty();
    }

    @Test
    void savingAgainForTheSamePhoneNumberReplacesThePreviousLoan() {
        LoanStore store = freshLoanStore();
        store.save("254700000001", sampleLoan());

        Loan replacement = sampleLoan();
        replacement.setLoanAmount(30000);
        store.save("254700000001", replacement);

        assertThat(store.findByPhoneNumber("254700000001").get().getLoanAmount()).isEqualTo(30000);
    }

    @Test
    void deletingRemovesTheLoan() {
        LoanStore store = freshLoanStore();
        store.save("254700000001", sampleLoan());

        store.delete("254700000001");

        assertThat(store.findByPhoneNumber("254700000001")).isEmpty();
    }

    @Test
    void deletingAPhoneNumberWithNoLoanIsHarmless() {
        LoanStore store = freshLoanStore();

        store.delete("254700000099"); // should not throw
    }

    @Test
    void loansArePerPhoneNumber() {
        LoanStore store = freshLoanStore();
        store.save("254700000001", sampleLoan());

        assertThat(store.findByPhoneNumber("254700000002")).isEmpty();
    }
}
