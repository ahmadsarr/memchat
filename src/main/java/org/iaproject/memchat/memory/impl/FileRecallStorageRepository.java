package org.iaproject.memchat.memory.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.Memory;
import org.iaproject.memchat.memory.RecallStorageRepository;
import org.iaproject.memchat.memory.Utils;

public class FileRecallStorageRepository implements RecallStorageRepository {
    private record Entry(String id, LLmApiClient.ChatMessage message) {}

    private final List<Entry> entries = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;

    public FileRecallStorageRepository() {
        this(Path.of("recall.json"));
    }

    public FileRecallStorageRepository(Path file) {
        this.file = file;
        load();
    }

    @Override
    public synchronized String add(LLmApiClient.ChatMessage message) {
        if (message==null) {
            throw new IllegalArgumentException("Recall content cannot be empty");
        }
        String id = UUID.randomUUID().toString();
        List<Entry> updated = new ArrayList<>(entries);
        updated.add(new Entry(id, message));
        save(updated);
        entries.add(new Entry(id, message));
        return id;
    }

    @Override
    public synchronized List<Memory.RecallSearchResult> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query cannot be empty");
        }
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("Limit must be between 1 and 20");
        }
        var keywords = Utils.tokenize(query);
        return entries.stream()
                .map(entry -> new Memory.RecallSearchResult(
                        entry.id(), entry.message(),
                        keywords.stream().filter(Utils.tokenize(searchableText(entry.message()))::contains).count()))
                .filter(result -> result.score() > 0)
                .sorted(Comparator.comparingLong(Memory.RecallSearchResult::score).reversed())
                .limit(limit)
                .toList();
    }

    private static String searchableText(LLmApiClient.ChatMessage message) {
        StringBuilder text = new StringBuilder(
                message.content() == null ? "" : message.content()
        );
        if (message.toolCallId() != null) {
            text.append(' ').append(message.toolCallId());
        }
        if (message.toolCalls() != null) {
            for (LLmApiClient.ToolCall call : message.toolCalls()) {
                text.append(' ').append(call.id())
                        .append(' ').append(call.name())
                        .append(' ').append(call.arguments());
            }
        }
        return text.toString();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<Entry> restored = mapper.readValue(file.toFile(), new TypeReference<List<Entry>>() {});
            if (restored == null || restored.stream().anyMatch(entry -> entry == null || entry.message() == null)) {
                throw new IllegalArgumentException("Recall entries and messages must not be null");
            }
            entries.addAll(restored);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Unable to load recall memory", e);
        }
    }

    private void save(List<Entry> snapshot) {
        try {
            AtomicJsonFile.write(mapper, file, snapshot);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save recall memory", e);
        }
    }
}
