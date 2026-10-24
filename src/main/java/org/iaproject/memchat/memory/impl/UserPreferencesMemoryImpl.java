package org.iaproject.memchat.memory.impl;

import org.iaproject.memchat.memory.UserPreferencesMemory;
import org.iaproject.memchat.memory.UserPreferencesRepository;

import java.util.Map;
import java.util.Objects;

public final class UserPreferencesMemoryImpl implements UserPreferencesMemory {
    private final UserPreferencesRepository repository;

    public UserPreferencesMemoryImpl(UserPreferencesRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public Map<String, String> list() {
        return Map.copyOf(repository.list());
    }

    @Override
    public void set(String key, String value) {
        validateKey(key);
        if (value == null || value.isBlank() || value.length() > 500) {
            throw new IllegalArgumentException("Preference value must contain 1 to 500 characters");
        }
        repository.set(key, value);
    }

    @Override
    public void remove(String key) {
        validateKey(key);
        repository.remove(key);
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Preference key must be a lowercase identifier of 1 to 64 characters");
        }
    }
}
