package com.mfstechnologies.mymobi.session;

import com.mfstechnologies.mymobi.model.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Session storage - direct equivalent of the userSessions {} object in
 * the Node.js version.
 *
 * WORKSTREAM C (persistence): now backed by Redis (via StringRedisTemplate,
 * storing each UserSession as a JSON string) rather than an in-memory
 * ConcurrentHashMap - the change that actually fixes the durability
 * caveat this class carried for the entire project so far.
 *
 * IMPORTANT BEHAVIORAL CHANGE from the in-memory version: getOrCreate()
 * used to return the exact same object reference on every call for a
 * given phone number, so every flow service could mutate it directly via
 * setters with no explicit "save" ever needed anywhere. Redis can't work
 * that way - getOrCreate() here deserializes a fresh UserSession object
 * from whatever JSON is currently stored (or returns a brand new,
 * NOT-YET-PERSISTED one if nothing is stored yet). This means mutations
 * are silently lost unless something explicitly calls save() afterward.
 *
 * ConversationService.handleIncomingMessage() is the one place that
 * calls getOrCreate() and threads the resulting object through every
 * flow-service call for that message - it now wraps that entire body in
 * try/finally and calls save() in the finally block, guaranteeing
 * whatever the object's final field values are get persisted back,
 * regardless of which branch executed or whether an exception was
 * thrown partway through.
 *
 * getOrCreate() deliberately does NOT persist a newly-created session
 * immediately - it returns the fresh object and trusts the caller's
 * finally-block save() to persist it once the full request has actually
 * been processed, avoiding a redundant extra Redis write.
 */
@Service
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final String KEY_PREFIX = "session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SessionStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** Returns the existing session for this phone number (deserialized fresh from Redis), or a new, not-yet-persisted one. */
    public UserSession getOrCreate(String phoneNumber) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + phoneNumber);
        if (json == null) {
            return new UserSession();
        }
        try {
            return objectMapper.readValue(json, UserSession.class);
        } catch (Exception e) {
            // Defensive: if a prior deploy changed UserSession's shape,
            // stale JSON from before that change could fail to
            // deserialize. Treating it as "no session" (fresh start,
            // e.g. re-login) is far safer than crashing the request.
            log.warn("Failed to deserialize session for {}, starting fresh: {}", phoneNumber, e.getMessage());
            return new UserSession();
        }
    }

    public void save(String phoneNumber, UserSession session) {
        String json = objectMapper.writeValueAsString(session);
        redisTemplate.opsForValue().set(KEY_PREFIX + phoneNumber, json);
    }

    public void delete(String phoneNumber) {
        redisTemplate.delete(KEY_PREFIX + phoneNumber);
    }

    public boolean exists(String phoneNumber) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + phoneNumber));
    }
}
