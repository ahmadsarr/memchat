package org.iaproject.memchat.search.lucene;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.iaproject.memchat.search.api.SearchDocument;
import org.iaproject.memchat.search.api.SearchException;
import org.iaproject.memchat.search.api.SearchWriter;

import java.io.IOException;
import java.util.Objects;

final class LuceneIndex implements SearchWriter, AutoCloseable {
    static final String ID = "id";
    static final String BODY = "body";

    private final IndexWriter indexWriter;
    private final SearcherManager searcherManager;

    LuceneIndex(Directory directory, IndexWriterConfig config) throws IOException {
        Objects.requireNonNull(directory, "Index directory required");
        Objects.requireNonNull(config, "Index config required");

        this.indexWriter = new IndexWriter(directory, config);
        try {
            this.searcherManager = new SearcherManager(indexWriter, null);
        } catch (IOException | RuntimeException e) {
            indexWriter.close();
            throw e;
        }
    }

    @Override
    public void index(SearchDocument searchDocument) throws SearchException {
        Objects.requireNonNull(searchDocument, "searchDocument");
        String id = requireNonBlank(searchDocument.id(), "Document ID");
        String content = requireNonBlank(searchDocument.content(), "Document content");

        Document document = new Document();
        document.add(new StringField(ID, id, Field.Store.YES));
        document.add(new TextField(BODY, content, Field.Store.YES));

        if (searchDocument.metadata() != null) {
            searchDocument.metadata().forEach((key, value) -> {
                requireNonBlank(key, "Metadata key");
                Objects.requireNonNull(value, "Metadata value");
                if (ID.equals(key) || BODY.equals(key)) {
                    throw new IllegalArgumentException("Reserved metadata key: " + key);
                }
                document.add(new StringField(key, value, Field.Store.YES));
            });
        }

        try {
            indexWriter.updateDocument(new Term(ID, id), document);
        } catch (IOException e) {
            throw new SearchException("Unable to index document: " + id, e);
        }
    }

    @Override
    public void remove(String documentId) throws SearchException {
        String id = requireNonBlank(documentId, "Document ID");
        try {
            indexWriter.deleteDocuments(new Term(ID, id));
        } catch (IOException e) {
            throw new SearchException("Unable to remove document: " + id, e);
        }
    }

    IndexSearcher acquireSearcher() throws IOException {
        searcherManager.maybeRefreshBlocking();
        return searcherManager.acquire();
    }

    void releaseSearcher(IndexSearcher searcher) throws IOException {
        searcherManager.release(searcher);
    }

    Analyzer analyzer() {
        return indexWriter.getConfig().getAnalyzer();
    }

    @Override
    public void close() throws IOException {
        try {
            searcherManager.close();
        } finally {
            indexWriter.close();
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
