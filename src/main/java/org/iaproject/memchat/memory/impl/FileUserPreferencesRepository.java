package org.iaproject.memchat.memory.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.memory.UserPreferencesRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** JSON snapshot storage. One owning memory instance per file; no multi-process writers. */
public final class FileUserPreferencesRepository implements UserPreferencesRepository {
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileUserPreferencesRepository(Path file) {
        this.file = Objects.requireNonNull(file);
    }

    @Override
    public Map<String, String> load() {
        if (Files.notExists(file)) return Map.of();
        try {
            return mapper.readValue(file.toFile(), new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load user preferences", e);
        }
    }

    @Override
    public void save(Map<String, String> profile) {
        Objects.requireNonNull(profile);
        try {
            AtomicJsonFile.write(mapper, file, profile);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save user preferences", e);
        }
    }
}
