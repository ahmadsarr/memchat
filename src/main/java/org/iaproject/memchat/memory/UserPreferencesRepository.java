package org.iaproject.memchat.memory;

import java.util.Map;

/** A bounded preference profile for one user. Implementations must return immutable snapshots. */
public interface UserPreferencesRepository {
    Map<String, String> list();
    void set(String key, String value);
    void remove(String key);
}
