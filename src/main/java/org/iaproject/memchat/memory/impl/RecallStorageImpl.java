package org.iaproject.memchat.memory.impl;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.Memory;
import org.iaproject.memchat.memory.RecallStorage;
import org.iaproject.memchat.memory.RecallStorageRepository;

import java.util.List;

public class RecallStorageImpl implements RecallStorage {
    private final RecallStorageRepository repository;

    public RecallStorageImpl(RecallStorageRepository repository) {
        this.repository = repository;
    }

    @Override
    public String add(LLmApiClient.ChatMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Recall message cannot be null");
        }
        return repository.add(message);
    }

    @Override
    public List<Memory.RecallSearchResult> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query cannot be empty");
        }
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("Limit must be between 1 and 20");
        }
        return repository.search(query, limit);
    }
}
