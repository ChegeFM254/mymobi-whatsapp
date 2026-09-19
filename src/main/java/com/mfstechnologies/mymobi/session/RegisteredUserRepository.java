package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * WORKSTREAM C (persistence): Spring Data JPA repository backing
 * RegisteredUserStore. RegisteredUser's @Id is phoneNumber, so this is
 * a JpaRepository<RegisteredUser, String> - findById/existsById/
 * deleteById all key on the phone number directly, matching exactly how
 * the in-memory ConcurrentHashMap version was keyed.
 */
public interface RegisteredUserRepository extends JpaRepository<RegisteredUser, String> {
}
