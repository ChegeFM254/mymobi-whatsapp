package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.Loan;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Loan storage.
 *
 * WORKSTREAM C (persistence): now backed by Postgres via LoanRepository,
 * rather than an in-memory ConcurrentHashMap. The public method
 * signatures are UNCHANGED from the in-memory version on purpose: every
 * flow service, ConversationService, and every existing test calls this
 * class only through findByPhoneNumber/save/delete, so none of them
 * need to change at all.
 *
 * save(phoneNumber, loan) sets phoneNumber onto the entity itself before
 * persisting, since the in-memory version's callers never needed to set
 * that field themselves (it was only ever the external Map key before).
 */
@Service
public class LoanStore {

    private final LoanRepository repository;

    public LoanStore(LoanRepository repository) {
        this.repository = repository;
    }

    public Optional<Loan> findByPhoneNumber(String phoneNumber) {
        return repository.findById(phoneNumber);
    }

    public void save(String phoneNumber, Loan loan) {
        loan.setPhoneNumber(phoneNumber);
        repository.save(loan);
    }

    public void delete(String phoneNumber) {
        repository.deleteById(phoneNumber);
    }
}
