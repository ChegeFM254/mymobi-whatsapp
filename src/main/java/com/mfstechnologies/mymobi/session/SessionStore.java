package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.UserSession;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session storage — direct equivalent of the userSessions {}
 * object in the Node.js version, including the SAME caveat that was
 * flagged repeatedly throughout that project: this is not durable. Every
 * session is lost on an application restart. ConcurrentHashMap makes
 * this safe for Spring MVC's multi-threaded request handling (unlike
 * the plain object in Node, which never needed thread-safety since Node
 * is single-threaded) — but it does NOT make the data durable.
 *
 * This is the natural place to swap in a real database (Spring Data JPA)
 * per the earlier discussion about bundling that migration into this
 * rewrite — deliberately not done yet, so that decision gets made
 * explicitly rather than by default.
 */
@Service
public class SessionStore {

    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();

    /** Returns the existing session for this phone number, or creates and stores a fresh one. */
    public UserSession getOrCreate(String phoneNumber) {
        return sessions.computeIfAbsent(phoneNumber, key -> new UserSession());
    }

    public void delete(String phoneNumber) {
        sessions.remove(phoneNumber);
    }

    public boolean exists(String phoneNumber) {
        return sessions.containsKey(phoneNumber);
    }
}