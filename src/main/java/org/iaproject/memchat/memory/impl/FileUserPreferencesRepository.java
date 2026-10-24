package org.iaproject.memchat.memory.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.memory.UserPreferencesRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One file per user; share one instance across that user's sessions. Single-process ownership. */
public final class FileUserPreferencesRepository implements UserPreferencesRepository {
    public static final int MAX_PREFERENCES = 50;
    public static final int MAX_TOTAL_CHARACTERS = 4_000;
    public static final int MAX_KEY_LENGTH = 64;
    public static final int MAX_VALUE_LENGTH = 500;

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, String> preferences = new LinkedHashMap<>();

    public FileUserPreferencesRepository(Path file) {
        this.file = Objects.requireNonNull(file);
        if (Files.exists(file)) {
            try {
                Map<String, String> loaded = mapper.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, String>>() {});
                validate(loaded);
                preferences = new LinkedHashMap<>(loaded);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Unable to load user preferences", e);
            }
        }
    }

    @Override
    public synchronized Map<String, String> list() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(preferences));
    }

    @Override
    public synchronized void set(String key, String value) {
        Map<String, String> updated = new LinkedHashMap<>(preferences);
        updated.put(key, value);
        commit(updated);
    }

    @Override
    public synchronized void remove(String key) {
        validateKey(key);
        if (!preferences.containsKey(key)) return;
        Map<String, String> updated = new LinkedHashMap<>(preferences);
        updated.remove(key);
        commit(updated);
    }

    private void commit(Map<String, String> updated) {
        validate(updated);
        try {
            AtomicJsonFile.write(mapper, file, updated);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save user preferences", e);
        }
        preferences = updated;
    }

    private static void validate(Map<String, String> values) {
        if (values == null || values.size() > MAX_PREFERENCES)
            throw new IllegalArgumentException("At most 50 preferences are allowed");
        values.forEach((key, value) -> {
            validateKey(key);
            if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH)
                throw new IllegalArgumentException("Preference value must contain 1 to 500 characters");
        });
        if (values.entrySet().stream().mapToInt(e -> e.getKey().length() + e.getValue().length()).sum()
                > MAX_TOTAL_CHARACTERS)
            throw new IllegalArgumentException("Preference profile must not exceed 4000 characters");
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[a-z][a-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("Preference key must be a lowercase identifier of 1 to 64 characters");
    }
}
