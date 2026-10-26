package org.iaproject.memchat.memory;

import java.time.Instant;
import java.util.Objects;

/** Dates use ISO-8601 UTC strings for a portable JSON representation. */
public record UserMemoryEntry(Type type, String content, String source,
                              String createdAt, String updatedAt) {
    public enum Type { PREFERENCE, FACT, HYPOTHESIS }

    public UserMemoryEntry {
        Objects.requireNonNull(type, "type");
        requireText(content, "content", 500);
        requireText(source, "source", 500);
        Instant created = Instant.parse(createdAt);
        Instant updated = Instant.parse(updatedAt);
        if (updated.isBefore(created)) throw new IllegalArgumentException("updatedAt precedes createdAt");
    }

    private static void requireText(String text, String name, int limit) {
        if (text == null || text.isBlank() || text.length() > limit)
            throw new IllegalArgumentException(name + " must contain 1 to " + limit + " characters");
    }
}
