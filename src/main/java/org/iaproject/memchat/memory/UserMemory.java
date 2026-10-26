package org.iaproject.memchat.memory;

import java.util.List;
import java.util.Map;

/** One user's memory; share the same instance across their agents. */
public interface UserMemory {
    Map<String, UserMemoryEntry> entries();
    void store(String key, UserMemoryEntry.Type type, String content, String source);
    void confirm(String key, UserMemoryEntry.Type confirmedType, String source);
    void remove(String key);
    List<Match> search(String query, int limit);
    Map<String, UserMemoryEntry> profile();

    record Match(String key, UserMemoryEntry entry, int score) {}

    /** Compatibility views for the existing preference tools. */
    default Map<String, String> list() {
        var result = new java.util.LinkedHashMap<String, String>();
        entries().forEach((key, entry) -> {
            if (entry.type() == UserMemoryEntry.Type.PREFERENCE) result.put(key, entry.content());
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    default void set(String key, String value) {
        store(key, UserMemoryEntry.Type.PREFERENCE, value, "Explicit user preference; saved through preferenceSet");
    }
}
