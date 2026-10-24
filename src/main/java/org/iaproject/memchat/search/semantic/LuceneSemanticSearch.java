package org.iaproject.memchat.search.semantic;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.iaproject.memchat.search.api.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Standalone cosine/HNSW index. Never opens or updates an existing lexical index. */
public final class LuceneSemanticSearch implements SearchReader, SearchWriter, AutoCloseable {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    private static final String VECTOR = "embedding";
    private static final String METADATA_PREFIX = "metadata.";
    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private final EmbeddingModel model;
    private final int dimensions;
    private boolean closed;

    /** The caller retains ownership of the embedding model, which may be shared between indexes. */
    public LuceneSemanticSearch(Path path, EmbeddingModel model) throws IOException {
        this.model = Objects.requireNonNull(model, "Embedding model is required");
        String modelId = requireText(model.modelId(), "Embedding model ID");
        this.dimensions = model.dimensions();
        if (dimensions < 1 || dimensions > KnnVectorsFormat.DEFAULT_MAX_DIMENSIONS) {
            throw new IllegalArgumentException("Vector dimensions must be between 1 and "
                    + KnnVectorsFormat.DEFAULT_MAX_DIMENSIONS);
        }
        Map<String, String> identity = Map.of("memchat.semantic.schema", "1",
                "memchat.semantic.model", modelId,
                "memchat.semantic.dimensions", Integer.toString(dimensions),
                "memchat.semantic.similarity", "cosine");
        directory = new NIOFSDirectory(Objects.requireNonNull(path, "Index path is required"));
        analyzer = new StandardAnalyzer();
        IndexWriter openedWriter = null;
        SearcherManager openedSearchers = null;
        try {
            openedWriter = new IndexWriter(directory, new IndexWriterConfig(analyzer).setCommitOnClose(false));
            if (DirectoryReader.indexExists(directory)) {
                Map<String, String> actual = new HashMap<>();
                openedWriter.getLiveCommitData().forEach(entry -> actual.put(entry.getKey(), entry.getValue()));
                if (!actual.equals(identity)) {
                    throw new SearchException("Index is not a semantic index for this model and dimensions; "
                            + "use a separate directory or rebuild it", null);
                }
            } else {
                openedWriter.setLiveCommitData(identity.entrySet());
                openedWriter.commit();
            }
            openedSearchers = new SearcherManager(openedWriter, null);
        } catch (IOException | RuntimeException | Error failure) {
            try (directory; analyzer; IndexWriter failedWriter = openedWriter;
                 SearcherManager failedSearchers = openedSearchers) {
                throw failure;
            }
        }
        writer = openedWriter;
        searchers = openedSearchers;
    }

    @Override
    public synchronized void index(SearchDocument source) {
        requireOpen();
        Objects.requireNonNull(source, "Document is required");
        String id = requireText(source.id(), "Document ID");
        String text = requireText(source.content(), "Document content");
        Document document = new Document();
        document.add(new StringField(ID, id, Field.Store.YES));
        document.add(new StoredField(CONTENT, text));
        if (source.metadata() != null) {
            source.metadata().forEach((key, value) -> document.add(new StoredField(
                    METADATA_PREFIX + requireText(key, "Metadata key"),
                    Objects.requireNonNull(value, "Metadata value is required"))));
        }
        // Validate and obtain the embedding before replacing any existing document.
        document.add(new KnnFloatVectorField(VECTOR,
                EmbeddingVectors.normalizedCopy(model.embed(text), dimensions), VectorSimilarityFunction.COSINE));
        try {
            writer.updateDocument(new Term(ID, id), document);
            writer.commit();
        } catch (IOException exception) {
            throw new SearchException("Unable to index semantic document: " + id, exception);
        }
    }

    /** Returns up to limit nearest candidates; totalHits is the candidate count, not a corpus match count. */
    @Override
    public synchronized SearchHit search(String query, int limit) {
        requireOpen();
        requireText(query, "Search query");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be between 1 and 100");
        float[] vector = EmbeddingVectors.normalizedCopy(model.embed(query), dimensions);
        IndexSearcher searcher = null;
        try {
            searchers.maybeRefreshBlocking();
            searcher = searchers.acquire();
            var hits = searcher.search(new KnnFloatVectorQuery(VECTOR, vector, limit), limit);
            var results = new ArrayList<SearchResult>();
            for (var hit : hits.scoreDocs) {
                results.add(new SearchResult(readDocument(searcher.storedFields().document(hit.doc)), hit.score));
            }
            return new SearchHit(results.size(), java.util.List.copyOf(results));
        } catch (IOException exception) {
            throw new SearchException("Unable to search semantic index", exception);
        } finally {
            release(searcher);
        }
    }

    @Override
    public synchronized Optional<SearchDocument> findById(String id) {
        requireOpen();
        requireText(id, "Document ID");
        IndexSearcher searcher = null;
        try {
            searchers.maybeRefreshBlocking();
            searcher = searchers.acquire();
            var hits = searcher.search(new TermQuery(new Term(ID, id)), 1);
            return hits.scoreDocs.length == 0 ? Optional.empty()
                    : Optional.of(readDocument(searcher.storedFields().document(hits.scoreDocs[0].doc)));
        } catch (IOException exception) {
            throw new SearchException("Unable to retrieve semantic document: " + id, exception);
        } finally {
            release(searcher);
        }
    }

    @Override
    public synchronized void remove(String id) {
        requireOpen();
        requireText(id, "Document ID");
        try {
            writer.deleteDocuments(new Term(ID, id));
            writer.commit();
        } catch (IOException exception) {
            throw new SearchException("Unable to remove semantic document: " + id, exception);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try (directory; analyzer; writer; searchers) {
            // Every successful write is already committed; closing does not publish pending writes.
        }
    }

    private static SearchDocument readDocument(Document stored) {
        Map<String, String> metadata = new HashMap<>();
        for (var field : stored.getFields()) {
            if (field.name().startsWith(METADATA_PREFIX)) {
                metadata.put(field.name().substring(METADATA_PREFIX.length()), field.stringValue());
            }
        }
        return new SearchDocument(stored.get(ID), stored.get(CONTENT), Map.copyOf(metadata));
    }

    private void release(IndexSearcher searcher) {
        if (searcher == null) return;
        try {
            searchers.release(searcher);
        } catch (IOException exception) {
            throw new SearchException("Unable to release semantic searcher", exception);
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Semantic index is closed");
    }

    private static String requireText(String text, String field) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException(field + " is required");
        return text;
    }
}
