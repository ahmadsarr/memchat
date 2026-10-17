package org.iaproject.memchat.search.lucene;

import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.iaproject.memchat.search.api.SearchDocument;
import org.iaproject.memchat.search.api.SearchException;
import org.iaproject.memchat.search.api.SearchHit;
import org.iaproject.memchat.search.api.SearchReader;
import org.iaproject.memchat.search.api.SearchWriter;

import java.io.IOException;

public final class LuceneSearchAdapter implements SearchReader, SearchWriter, AutoCloseable {
    private final LuceneIndex index;
    private final LuceneBm25Searcher bm25Searcher;

    public LuceneSearchAdapter(Directory directory, IndexWriterConfig config) throws IOException {
        this.index = new LuceneIndex(directory, config);
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
    public void remove(String documentId) throws SearchException {
        index.remove(documentId);
    }

    @Override
    public void close() throws IOException {
        index.close();
    }
}
