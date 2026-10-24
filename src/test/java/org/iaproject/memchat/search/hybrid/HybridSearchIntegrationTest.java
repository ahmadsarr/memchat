package org.iaproject.memchat.search.hybrid;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.NIOFSDirectory;
import org.iaproject.memchat.search.api.SearchDocument;
import org.iaproject.memchat.search.lucene.LuceneSearchAdapter;
import org.iaproject.memchat.search.semantic.EmbeddingModel;
import org.iaproject.memchat.search.semantic.LuceneSemanticSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class HybridSearchIntegrationTest {
    @TempDir Path directory;

    @Test
    void composesRealLexicalAndVectorIndexesAndKeepsWritesInBoth() throws Exception {
        EmbeddingModel model = new EmbeddingModel() {
            public String modelId() { return "test-hybrid-v1"; }
            public int dimensions() { return 2; }
            public float[] embed(String text) {
                return switch (text) {
                    case "car specifications" -> new float[]{0, 1};
                    case "car repair maintenance" -> new float[]{0.99f, 0.01f};
                    default -> new float[]{1, 0};
                };
            }
        };
        Path lexicalPath = directory.resolve("lexical");
        Path semanticPath = directory.resolve("semantic");
        try (var lexical = new LuceneSearchAdapter(new NIOFSDirectory(lexicalPath),
                     new IndexWriterConfig(new StandardAnalyzer()));
             var semantic = new LuceneSemanticSearch(semanticPath, model)) {
            // The composite owns and closes both resources; explicit resources also cover setup failures.
            try (var hybrid = new HybridSearch(List.of(lexical, semantic))) {
                hybrid.index(new SearchDocument("lexical", "car specifications", Map.of()));
                hybrid.index(new SearchDocument("semantic", "automobile maintenance", Map.of()));
                hybrid.index(new SearchDocument("shared", "car repair maintenance", Map.of("kind", "guide")));
                assertEquals(2, lexical.search("car repair", 10).results().size());
                assertEquals("semantic", semantic.search("car repair", 1).results().getFirst().document().id());
                var result = hybrid.search("car repair", 10);
                assertEquals("shared", result.results().getFirst().document().id());
                assertEquals(3, result.totalHits());
                assertEquals(Set.of("lexical", "semantic", "shared"), result.results().stream()
                        .map(hit -> hit.document().id()).collect(Collectors.toSet()));
                assertEquals("shared", hybrid.search("car repair", 1).results().getFirst().document().id());

                var updated = new SearchDocument("shared", "updated repair guide", Map.of("kind", "updated"));
                hybrid.index(updated);
                assertEquals(updated, lexical.findById("shared").orElseThrow());
                assertEquals(updated, semantic.findById("shared").orElseThrow());
                hybrid.remove("lexical");
                assertTrue(lexical.findById("lexical").isEmpty());
                assertTrue(semantic.findById("lexical").isEmpty());
            }
        }
        try (var hybrid = new HybridSearch(List.of(
                new LuceneSearchAdapter(new NIOFSDirectory(lexicalPath), new IndexWriterConfig(new StandardAnalyzer())),
                new LuceneSemanticSearch(semanticPath, model)))) {
            assertEquals("updated repair guide", hybrid.findById("shared").orElseThrow().content());
            assertTrue(hybrid.findById("lexical").isEmpty());
        }
    }
}
