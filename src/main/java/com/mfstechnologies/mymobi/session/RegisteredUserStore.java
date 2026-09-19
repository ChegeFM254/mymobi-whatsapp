package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Registered-user storage - direct equivalent of the registeredUsers {}
 * object in the Node.js version.
 *
 * WORKSTREAM C (persistence): now backed by Postgres via
 * RegisteredUserRepository, rather than an in-memory ConcurrentHashMap -
 * this is exactly the change the durability caveat below used to warn
 * about needing. The public method signatures are UNCHANGED from the
 * in-memory version on purpose: every flow service, ConversationService,
 * and every existing test calls this class only through
 * findByPhoneNumber/save/exists/delete, so none of them need to change
 * at all - they have no way to know persistence changed underneath them.
 *
 * save(phoneNumber, user) sets phoneNumber onto the entity itself before
 * persisting, since the in-memory version's callers never needed to set
 * that field themselves (it was only ever the external Map key before).
 */
@Service
public class RegisteredUserStore {

    private final RegisteredUserRepository repository;

    public RegisteredUserStore(RegisteredUserRepository repository) {
        this.repository = repository;
    }

    public Optional<RegisteredUser> findByPhoneNumber(String phoneNumber) {
        return repository.findById(phoneNumber);
    }

    public void save(String phoneNumber, RegisteredUser user) {
        user.setPhoneNumber(phoneNumber);
        repository.save(user);
    }

    public boolean exists(String phoneNumber) {
        return repository.existsById(phoneNumber);
    }

    public void delete(String phoneNumber) {
        repository.deleteById(phoneNumber);
    }
}
