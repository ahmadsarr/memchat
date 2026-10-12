package org.iaproject.memchat.memory.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.iaproject.memchat.memory.ArchivalMemoryRepository;
import org.iaproject.memchat.memory.Memory;

import static org.iaproject.memchat.memory.Utils.STOP_WORDS;

public class FileArchivalMemoryRepository implements ArchivalMemoryRepository {
    private final Map<String, String> memories = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;

    public FileArchivalMemoryRepository() {
        this(Path.of("memory.json"));
    }

    public FileArchivalMemoryRepository(Path file) {
        this.file = file;
        load();
    }

    @Override
    public synchronized String insert(String content) {
        requireText(content, "Memory content");
        String id = UUID.randomUUID().toString();
        memories.put(id, content.trim());
        save();
        return id;
    }

    @Override
    public synchronized List<Memory.MemorySearchResult> search(String query, int limit) {
        requireText(query, "Search query");
        validateLimit(limit);
        var keywords = tokens(query);

        return memories.entrySet().stream()
                .map(entry -> new Memory.MemorySearchResult(
                        entry.getKey(), entry.getValue(),
                        keywords.stream().filter(tokens(entry.getValue())::contains).count()))
                .filter(result -> result.score() > 0)
                .sorted(Comparator.comparingLong(Memory.MemorySearchResult::score).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void update(String id, String content) {
        requireText(id, "Memory ID");
        requireText(content, "Memory content");
        if (!memories.containsKey(id)) {
            throw new IllegalArgumentException("Memory not found: " + id);
        }
        memories.put(id, content.trim());
        save();
    }

    @Override
    public synchronized void delete(String id) {
        requireText(id, "Memory ID");
        if (memories.remove(id) == null) {
            throw new IllegalArgumentException("Memory not found: " + id);
        }
        save();
    }

    @Override
    public synchronized String get(String id) {
        requireText(id, "Memory ID");
        return memories.get(id);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            memories.putAll(mapper.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, String>>() {}));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Unable to load archival memory", e);
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), memories);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save archival memory", e);
        }
    }

    private static java.util.Set<String> tokens(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(token -> !token.isBlank() && !STOP_WORDS.contains(token))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("Limit must be between 1 and 20");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be empty");
        }
    }
}
