package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registered-user storage - direct equivalent of the
 * registeredUsers {} object in the Node.js version. Same durability
 * caveat as SessionStore: not backed by a real database yet, lost on
 * restart. This is the natural place the eventual database migration
 * plugs in.
 */
@Service
public class RegisteredUserStore {

    private final Map<String, RegisteredUser> users = new ConcurrentHashMap<>();

    public Optional<RegisteredUser> findByPhoneNumber(String phoneNumber) {
        return Optional.ofNullable(users.get(phoneNumber));
    }

    public void save(String phoneNumber, RegisteredUser user) {
        users.put(phoneNumber, user);
    }

    public boolean exists(String phoneNumber) {
        return users.containsKey(phoneNumber);
    }

    public void delete(String phoneNumber) {
        users.remove(phoneNumber);
    }
}
