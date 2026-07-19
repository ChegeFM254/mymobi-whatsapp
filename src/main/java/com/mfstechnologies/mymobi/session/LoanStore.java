package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.Loan;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoanStore {

    private final Map<String, Loan> loans = new ConcurrentHashMap<>();

    public Optional<Loan> findByPhoneNumber(String phoneNumber) {
        return Optional.ofNullable(loans.get(phoneNumber));
    }

    public void save(String phoneNumber, Loan loan) {
        loans.put(phoneNumber, loan);
    }

    public void delete(String phoneNumber) {
        loans.remove(phoneNumber);
    }
}