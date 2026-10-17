package org.iaproject.memchat.memory.impl;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.NIOFSDirectory;
import org.iaproject.memchat.memory.ArchivalMemoryRepository;
import org.iaproject.memchat.memory.Memory;
import org.iaproject.memchat.search.api.SearchDocument;
import org.iaproject.memchat.search.api.SearchException;
import org.iaproject.memchat.search.lucene.LuceneSearchAdapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class LuceneArchivalMemoryRepository implements ArchivalMemoryRepository, AutoCloseable {
    private final LuceneSearchAdapter adapter;

    public LuceneArchivalMemoryRepository() throws IOException {
        this(Path.of("memory-index"));
    }

    public LuceneArchivalMemoryRepository(Path directory) throws IOException {
        this.adapter = new LuceneSearchAdapter(
                new NIOFSDirectory(directory),
                new IndexWriterConfig(new StandardAnalyzer())
        );
    }

    @Override
    public synchronized String insert(String content) throws SearchException {
        requireText(content, "Memory content");
        String id = UUID.randomUUID().toString();
        adapter.index(new SearchDocument(id, content.trim(), java.util.Map.of()));
        return id;
    }

    @Override
    public synchronized List<Memory.MemorySearchResult> search(String query, int limit)
            throws SearchException {
        requireText(query, "Search query");
        validateLimit(limit);
        return adapter.search(query, limit).results().stream()
                .map(result -> new Memory.MemorySearchResult(
                        result.document().id(),
                        result.document().content(),
                        result.score()
                ))
                .toList();
    }

    @Override
    public synchronized void update(String id, String content) throws SearchException {
        requireText(id, "Memory ID");
        requireText(content, "Memory content");
        requireExisting(id);
        adapter.index(new SearchDocument(id, content.trim(), java.util.Map.of()));
    }

    @Override
    public synchronized void delete(String id) throws SearchException {
        requireText(id, "Memory ID");
        requireExisting(id);
        adapter.remove(id);
    }

    @Override
    public synchronized String get(String id) throws SearchException {
        requireText(id, "Memory ID");
        return adapter.findById(id)
                .map(SearchDocument::content)
                .orElse(null);
    }

    private void requireExisting(String id) throws SearchException {
        if (adapter.findById(id).isEmpty()) {
            throw new IllegalArgumentException("Memory not found: " + id);
        }
    }

    @Override
    public void close() throws IOException {
        adapter.close();
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
