package org.iaproject.memchat.search.lucene;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.iaproject.memchat.search.api.SearchDocument;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuceneSearchAdapterTest {
    @Test
    void indexesSearchesAndRemovesDocuments() throws Exception {
        try (var adapter = new LuceneSearchAdapter(
                new ByteBuffersDirectory(),
                new IndexWriterConfig(new StandardAnalyzer())
        )) {
            adapter.index(new SearchDocument(
                    "memory-1",
                    "The user prefers Java and Maven",
                    Map.of("type", "preference")
            ));

            var result = adapter.search("Java", 10);

            assertEquals(1, result.totalHits());
            assertEquals("memory-1", result.results().getFirst().document().id());
            assertEquals("preference", result.results().getFirst().document().metadata().get("type"));
            assertTrue(result.results().getFirst().score() > 0);

            adapter.remove("memory-1");

            assertEquals(0, adapter.search("Java", 10).totalHits());
        }
    }
}
