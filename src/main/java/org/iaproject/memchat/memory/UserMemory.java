package org.iaproject.memchat.memory;

import java.util.Map;

/** Durable preferences belonging to one user, shared explicitly across sessions. */
public interface UserMemory {
    Map<String, String> list();
    void set(String key, String value);
    void remove(String key);
}
