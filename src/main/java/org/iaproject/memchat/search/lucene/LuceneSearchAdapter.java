package org.iaproject.memchat.search.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.iaproject.memchat.search.api.SearchDocument;
import org.iaproject.memchat.search.api.SearchException;
import org.iaproject.memchat.search.api.SearchHit;
import org.iaproject.memchat.search.api.SearchEngine;

import java.io.IOException;
import java.util.Optional;
import java.util.Objects;

public final class LuceneSearchAdapter implements SearchEngine {
    private final Directory directory;
    private final Analyzer analyzer;
    private final LuceneIndex index;
    private final LuceneBm25Searcher bm25Searcher;

    /** Takes ownership of the directory and configured analyzer, including on opening failure. */
    public LuceneSearchAdapter(Directory directory, IndexWriterConfig config) throws IOException {
        this.directory = Objects.requireNonNull(directory, "Index directory required");
        this.analyzer = Objects.requireNonNull(config, "Index config required").getAnalyzer();
        try {
            this.index = new LuceneIndex(directory, config);
        } catch (IOException | RuntimeException | Error failure) {
            try (directory; analyzer) {
                throw failure;
            }
        }
        this.bm25Searcher = new LuceneBm25Searcher(index);
    }

    @Override
    public void index(SearchDocument document) throws SearchException {
        index.index(document);
    }

    @Override
    public SearchHit search(String query, int limit) throws SearchException {
        return bm25Searcher.search(query, limit);
    }

    @Override
    public Optional<SearchDocument> findById(String documentId) throws SearchException {
        return bm25Searcher.findById(documentId);
    }

    @Override
    public void remove(String documentId) throws SearchException {
        index.remove(documentId);
    }

    @Override
    public void close() throws IOException {
        try (directory; analyzer) {
            index.close();
        }
    }
}
