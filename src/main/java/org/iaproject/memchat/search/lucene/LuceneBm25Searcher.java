package org.iaproject.memchat.search.lucene;

import org.apache.lucene.document.Document;
import org.apache.lucene.util.QueryBuilder;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.iaproject.memchat.search.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class LuceneBm25Searcher implements SearchReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(LuceneBm25Searcher.class);
    private static final int MAX_RESULTS = 100;

    private final LuceneIndex index;

    LuceneBm25Searcher(LuceneIndex index) {
        this.index = index;
    }

    @Override
    public SearchHit search(String query, int limit) throws SearchException {
        requireNonBlank(query, "Search query");
        if (limit <= 0 || limit > MAX_RESULTS) {
            throw new IllegalArgumentException("Limit must be between 1 and " + MAX_RESULTS);
        }
        long startedAt = System.nanoTime();
        LOGGER.info("Lucene BM25 search started (queryLength={}, limit={})",
                query.length(), limit);

        IndexSearcher searcher = null;
        try {
            searcher = index.acquireSearcher();
            // Agent queries are ordinary text, not Lucene expressions or field selectors.
            Query parsedQuery = new QueryBuilder(index.analyzer())
                    .createBooleanQuery(LuceneIndex.BODY, query);
            if (parsedQuery == null) parsedQuery = new MatchNoDocsQuery();
            TopDocs topDocs = searcher.search(parsedQuery, limit);
            List<SearchResult> documents = new ArrayList<>();

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document stored = searcher.storedFields().document(scoreDoc.doc);
                documents.add(new SearchResult(toSearchDocument(stored), scoreDoc.score));
            }
            double maxScore = documents.stream()
                    .mapToDouble(SearchResult::score)
                    .max()
                    .orElse(0);
            SearchHit result = new SearchHit(topDocs.totalHits.value(), documents);
            LOGGER.info("Lucene BM25 search completed (totalHits={}, returned={}, maxScore={}, durationMs={})",
                    result.totalHits(), result.results().size(), maxScore, elapsedMillis(startedAt));
            return result;
        } catch (IOException e) {
            LOGGER.warn("Lucene BM25 search failed (queryLength={}, durationMs={}, error={})",
                    query.length(), elapsedMillis(startedAt), e.getClass().getSimpleName(), e);
            throw new SearchException("Unable to search Lucene index", e);
        } finally {
            release(searcher);
        }
    }

    @Override
    public Optional<SearchDocument> findById(String documentId) throws SearchException {
        requireNonBlank(documentId, "Document ID");
        long startedAt = System.nanoTime();
        LOGGER.info("Lucene document lookup started (id={})", documentId);
        IndexSearcher searcher = null;
        try {
            searcher = index.acquireSearcher();
            TopDocs topDocs = searcher.search(
                    new TermQuery(new org.apache.lucene.index.Term(LuceneIndex.ID, documentId)),
                    1
            );
            if (topDocs.scoreDocs.length == 0) {
                LOGGER.info("Lucene document lookup completed (id={}, found=false, durationMs={})",
                        documentId, elapsedMillis(startedAt));
                return Optional.empty();
            }
            Document stored = searcher.storedFields().document(topDocs.scoreDocs[0].doc);
            LOGGER.info("Lucene document lookup completed (id={}, found=true, durationMs={})",
                    documentId, elapsedMillis(startedAt));
            return Optional.of(toSearchDocument(stored));
        } catch (IOException e) {
            LOGGER.warn("Lucene document lookup failed (id={}, durationMs={}, error={})",
                    documentId, elapsedMillis(startedAt), e.getClass().getSimpleName(), e);
            throw new SearchException("Unable to retrieve document: " + documentId, e);
        } finally {
            release(searcher);
        }
    }

    private static SearchDocument toSearchDocument(Document stored) {
        Map<String, String> metadata = new HashMap<>();
        for (var field : stored.getFields()) {
            if (!LuceneIndex.ID.equals(field.name()) && !LuceneIndex.BODY.equals(field.name())) {
                metadata.put(field.name(), field.stringValue());
            }
        }
        return new SearchDocument(stored.get(LuceneIndex.ID), stored.get(LuceneIndex.BODY), metadata);
    }

    private void release(IndexSearcher searcher) throws SearchException {
        if (searcher == null) {
            return;
        }
        try {
            index.releaseSearcher(searcher);
        } catch (IOException e) {
            throw new SearchException("Unable to release Lucene searcher", e);
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static long elapsedMillis(long startedAt) {
        return java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
