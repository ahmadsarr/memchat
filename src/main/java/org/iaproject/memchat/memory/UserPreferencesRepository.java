package org.iaproject.memchat.memory;

import java.util.Map;

/** Persistence of one user's complete profile. No business rules belong here. */
public interface UserPreferencesRepository {
    /** Returns the stored profile, or an empty profile if none exists. */
    Map<String, String> load();

    /** Atomically replaces the profile; failure must leave the previous snapshot intact. */
    void save(Map<String, String> profile);
}
