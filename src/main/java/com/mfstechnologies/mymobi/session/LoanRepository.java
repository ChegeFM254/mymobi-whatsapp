package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.Loan;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * WORKSTREAM C (persistence): Spring Data JPA repository backing
 * LoanStore. Loan's @Id is phoneNumber, so this is a
 * JpaRepository<Loan, String> - findById/deleteById key on the phone
 * number directly, matching exactly how the in-memory
 * ConcurrentHashMap version was keyed.
 */
public interface LoanRepository extends JpaRepository<Loan, String> {
}
