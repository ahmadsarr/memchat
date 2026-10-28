package org.iaproject.memchat.memory.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.memory.UserMemoryEntry;
import org.iaproject.memchat.memory.UserMemoryRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class FileUserMemoryRepository implements UserMemoryRepository {
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileUserMemoryRepository(Path file) { this.file = Objects.requireNonNull(file); }

    @Override
    public Map<String, UserMemoryEntry> load() {
        if (Files.notExists(file)) return Map.of();
        try {
            var root = mapper.readTree(file.toFile());
            if (root == null || !root.isObject()) throw new IOException("Expected a JSON object");
            if (root.has("schemaVersion")) {
                if (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt()
                        || root.path("schemaVersion").intValue() != 1
                        || !root.path("entries").isObject()) throw new IOException("Unsupported user memory schema");
                return mapper.convertValue(root.get("entries"), new TypeReference<Map<String, UserMemoryEntry>>() {});
            }
            // Read legacy preferences without rewriting the original file on load.
            Map<String, UserMemoryEntry> migrated = new LinkedHashMap<>();
            String date = Files.getLastModifiedTime(file).toInstant().toString();
            var fields = root.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getValue().isTextual()) throw new IOException("Invalid legacy preference value");
                migrated.put(field.getKey(), new UserMemoryEntry(UserMemoryEntry.Type.PREFERENCE,
                        field.getValue().textValue(), "Legacy preference import; original source unavailable", date, date));
            }
            return migrated;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Unable to load user memory", e);
        }
    }

    @Override
    public void save(Map<String, UserMemoryEntry> entries) {
        try {
            AtomicJsonFile.write(mapper, file, Map.of("schemaVersion", 1, "entries", entries));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save user memory", e);
        }
    }
}
