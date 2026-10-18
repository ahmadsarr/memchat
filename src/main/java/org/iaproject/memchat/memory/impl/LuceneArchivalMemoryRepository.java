package org.iaproject.memchat.memory.impl;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.NIOFSDirectory;
import org.iaproject.memchat.memory.ArchivalMemoryRepository;
import org.iaproject.memchat.memory.Memory;
import org.iaproject.memchat.search.api.SearchDocument;
import org.iaproject.memchat.search.api.SearchException;
import org.iaproject.memchat.search.lucene.LuceneSearchAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class LuceneArchivalMemoryRepository implements ArchivalMemoryRepository, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LuceneArchivalMemoryRepository.class);
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
        long startedAt = System.nanoTime();
        LOGGER.info("Archival memory insert started (contentLength={})", content.length());
        String id = UUID.randomUUID().toString();
        adapter.index(new SearchDocument(id, content.trim(), java.util.Map.of()));
        LOGGER.info("Archival memory insert completed (id={}, durationMs={})",
                id, elapsedMillis(startedAt));
        return id;
    }

    @Override
    public synchronized List<Memory.MemorySearchResult> search(String query, int limit)
            throws SearchException {
        requireText(query, "Search query");
        validateLimit(limit);
        long startedAt = System.nanoTime();
        LOGGER.info("Archival memory search started (queryLength={}, limit={})",
                query.length(), limit);
        List<Memory.MemorySearchResult> results = adapter.search(query, limit).results().stream()
                .map(result -> new Memory.MemorySearchResult(
                        result.document().id(),
                        result.document().content(),
                        result.score()
                ))
                .toList();
        LOGGER.info("Archival memory search completed (results={}, durationMs={})",
                results.size(), elapsedMillis(startedAt));
        return results;
    }

    @Override
    public synchronized void update(String id, String content) throws SearchException {
        requireText(id, "Memory ID");
        requireText(content, "Memory content");
        long startedAt = System.nanoTime();
        LOGGER.info("Archival memory update started (id={}, contentLength={})",
                id, content.length());
        requireExisting(id);
        adapter.index(new SearchDocument(id, content.trim(), java.util.Map.of()));
        LOGGER.info("Archival memory update completed (id={}, durationMs={})",
                id, elapsedMillis(startedAt));
    }

    @Override
    public synchronized void delete(String id) throws SearchException {
        requireText(id, "Memory ID");
        long startedAt = System.nanoTime();
        LOGGER.info("Archival memory delete started (id={})", id);
        requireExisting(id);
        adapter.remove(id);
        LOGGER.info("Archival memory delete completed (id={}, durationMs={})",
                id, elapsedMillis(startedAt));
    }

    @Override
    public synchronized String get(String id) throws SearchException {
        requireText(id, "Memory ID");
        long startedAt = System.nanoTime();
        LOGGER.info("Archival memory get started (id={})", id);
        String content = adapter.findById(id)
                .map(SearchDocument::content)
                .orElse(null);
        LOGGER.info("Archival memory get completed (id={}, found={}, durationMs={})",
                id, content != null, elapsedMillis(startedAt));
        return content;
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

    private static long elapsedMillis(long startedAt) {
        return java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
