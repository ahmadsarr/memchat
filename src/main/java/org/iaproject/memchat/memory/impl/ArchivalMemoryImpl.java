package org.iaproject.memchat.memory.impl;

import org.iaproject.memchat.memory.ArchivalMemory;
import org.iaproject.memchat.memory.ArchivalMemoryRepository;
import org.iaproject.memchat.memory.Memory;

import java.util.List;

public class ArchivalMemoryImpl implements ArchivalMemory {
    private final ArchivalMemoryRepository repository;

    public ArchivalMemoryImpl(ArchivalMemoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public String insert(String content) {
        requireText(content, "Memory content");
        return repository.insert(content.trim());
    }

    @Override
    public List<Memory.MemorySearchResult> search(String query, int limit) {
        requireText(query, "Search query");
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("Limit must be between 1 and 20");
        }
        return repository.search(query, limit);
    }

    @Override
    public void update(String id, String content) {
        requireText(id, "Memory ID");
        requireText(content, "Memory content");
        repository.update(id, content.trim());
    }

    @Override
    public void delete(String id) {
        requireText(id, "Memory ID");
        repository.delete(id);
    }

    @Override
    public String get(String id) {
        requireText(id, "Memory ID");
        return repository.get(id);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be empty");
        }
    }
}
