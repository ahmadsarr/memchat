package org.iaproject.memchat.memory.impl;

import org.iaproject.memchat.memory.UserMemory;
import org.iaproject.memchat.memory.UserPreferencesRepository;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Owns a bounded profile. Share this instance across the user's sessions. */
public final class UserMemoryImpl implements UserMemory {
    public static final int MAX_PREFERENCES = 50;
    public static final int MAX_KEY_LENGTH = 64;
    public static final int MAX_VALUE_LENGTH = 500;
    public static final int MAX_TOTAL_CHARACTERS = 4_000;

    private final UserPreferencesRepository repository;
    private Map<String, String> profile;

    public UserMemoryImpl(UserPreferencesRepository repository) {
        this.repository = Objects.requireNonNull(repository);
        Map<String, String> loaded = repository.load();
        try {
            validate(loaded);
            this.profile = snapshot(loaded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid stored user preferences", e);
        }
    }

    @Override
    public synchronized Map<String, String> list() {
        return profile;
    }

    @Override
    public synchronized void set(String key, String value) {
        var updated = new LinkedHashMap<>(profile);
        updated.put(key, value);
        commit(updated);
    }

    @Override
    public synchronized void remove(String key) {
        validateKey(key);
        if (!profile.containsKey(key)) return;
        var updated = new LinkedHashMap<>(profile);
        updated.remove(key);
        commit(updated);
    }

    private void commit(Map<String, String> updated) {
        validate(updated);
        Map<String, String> committed = snapshot(updated);
        repository.save(committed);
        profile = committed;
    }

    private static Map<String, String> snapshot(Map<String, String> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static void validate(Map<String, String> values) {
        if (values == null || values.size() > MAX_PREFERENCES)
            throw new IllegalArgumentException("At most 50 preferences are allowed");
        int characters = 0;
        for (var entry : values.entrySet()) {
            validateKey(entry.getKey());
            String value = entry.getValue();
            if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH)
                throw new IllegalArgumentException("Preference value must contain 1 to 500 characters");
            characters += entry.getKey().length() + value.length();
        }
        if (characters > MAX_TOTAL_CHARACTERS)
            throw new IllegalArgumentException("Preference profile must not exceed 4000 characters");
    }

    private static void validateKey(String key) {
        if (key == null || key.length() > MAX_KEY_LENGTH || !key.matches("[a-z][a-z0-9_.-]*"))
            throw new IllegalArgumentException("Preference key must be a lowercase identifier of 1 to 64 characters");
    }
}
