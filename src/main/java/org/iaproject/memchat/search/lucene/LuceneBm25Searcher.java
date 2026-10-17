package org.iaproject.memchat.search.lucene;

import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.iaproject.memchat.search.api.SearchDocument;
import org.iaproject.memchat.search.api.SearchException;
import org.iaproject.memchat.search.api.SearchHit;
import org.iaproject.memchat.search.api.SearchReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class LuceneBm25Searcher implements SearchReader {
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

        IndexSearcher searcher = null;
        try {
            searcher = index.acquireSearcher();
            Query parsedQuery = new QueryParser(
                    LuceneIndex.BODY,
                    index.analyzer()
            ).parse(query);
            TopDocs topDocs = searcher.search(parsedQuery, limit);
            List<SearchDocument> documents = new ArrayList<>();

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document stored = searcher.storedFields().document(scoreDoc.doc);
                Map<String, String> metadata = new HashMap<>();
                for (var field : stored.getFields()) {
                    if (!LuceneIndex.ID.equals(field.name()) && !LuceneIndex.BODY.equals(field.name())) {
                        metadata.put(field.name(), field.stringValue());
                    }
                }
                documents.add(new SearchDocument(
                        stored.get(LuceneIndex.ID),
                        stored.get(LuceneIndex.BODY),
                        metadata
                ));
            }
            return new SearchHit(topDocs.totalHits.value(), documents);
        } catch (IOException | ParseException e) {
            throw new SearchException("Unable to search Lucene index", e);
        } finally {
            if (searcher != null) {
                try {
                    index.releaseSearcher(searcher);
                } catch (IOException e) {
                    throw new SearchException("Unable to release Lucene searcher", e);
                }
            }
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }
}
