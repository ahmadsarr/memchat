package org.iaproject.memchat.search.semantic;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.NIOFSDirectory;
import org.iaproject.memchat.search.api.SearchDocument;
import org.iaproject.memchat.search.api.SearchException;
import org.iaproject.memchat.search.lucene.LuceneSearchAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LuceneSemanticSearchTest {
    @TempDir Path directory;

    @Test
    void ranksByMeaningWithoutSharedKeywords() throws Exception {
        Map<String, float[]> vectors = Map.of(
                "The automobile needs repairs", new float[]{1, 0},
                "Mix butter with flour", new float[]{0, 1},
                "fix my car", new float[]{0.99f, 0.01f});
        try (var search = new LuceneSemanticSearch(directory, model("test-v1", 2, vectors::get))) {
            search.index(new SearchDocument("transport", "The automobile needs repairs", Map.of("topic", "travel")));
            search.index(new SearchDocument("baking", "Mix butter with flour", Map.of()));
            var result = search.search("fix my car", 2);
            assertEquals(2, result.totalHits());
            assertEquals("transport", result.results().getFirst().document().id());
            assertEquals("travel", result.results().getFirst().document().metadata().get("topic"));
            assertTrue(result.results().getFirst().score() > result.results().getLast().score());
            assertTrue(result.results().getFirst().score() > 0.99f);
            assertEquals(1, search.search("fix my car", 1).results().size());
        }
    }

    @Test
    void updatesDeletesAndPersistsDocumentsAndMetadata() throws Exception {
        EmbeddingModel model = model("test-v1", 2, text -> text.equals("old")
                ? new float[]{0, 1} : new float[]{1, 0});
        try (var search = new LuceneSemanticSearch(directory, model)) {
            assertTrue(search.search("query", 3).results().isEmpty());
            search.index(new SearchDocument("one", "old", Map.of()));
            search.index(new SearchDocument("one", "updated", Map.of("id", "custom", "embedding", "metadata")));
            assertEquals(1, search.search("query", 10).results().size());
        }
        try (var reopened = new LuceneSemanticSearch(directory, model)) {
            var document = reopened.findById("one").orElseThrow();
            assertEquals("updated", document.content());
            assertEquals(Map.of("id", "custom", "embedding", "metadata"), document.metadata());
            assertEquals(1.0f, reopened.search("query", 1).results().getFirst().score(), 0.0001f);
            reopened.remove("one");
            assertTrue(reopened.findById("one").isEmpty());
            assertTrue(reopened.search("query", 10).results().isEmpty());
        }
        try (var reopened = new LuceneSemanticSearch(directory, model)) {
            assertTrue(reopened.findById("one").isEmpty());
        }
    }

    static Stream<Arguments> invalidVectors() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(new float[]{1}),
                Arguments.of(new float[]{0, 0}), Arguments.of(new float[]{Float.NaN, 1}),
                Arguments.of(new float[]{Float.POSITIVE_INFINITY, 1}));
    }

    @ParameterizedTest
    @MethodSource("invalidVectors")
    void invalidEmbeddingsNeverReplaceAnExistingDocument(float[] invalid) throws Exception {
        var vector = new AtomicReference<>(new float[]{1, 0});
        EmbeddingModel model = model("test", 2, text -> vector.get());
        try (var search = new LuceneSemanticSearch(directory, model)) {
            search.index(new SearchDocument("one", "original", Map.of()));
            vector.set(invalid);
            assertThrows(SearchException.class, () -> search.index(new SearchDocument("one", "replacement", Map.of())));
            assertThrows(SearchException.class, () -> search.search("query", 1));
            assertEquals("original", search.findById("one").orElseThrow().content());
        }
        try (var reopened = new LuceneSemanticSearch(directory, model)) {
            assertEquals("original", reopened.findById("one").orElseThrow().content());
        }
    }

    @Test
    void embeddingServiceFailureDoesNotChangeTheIndex() throws Exception {
        EmbeddingModel model = model("test", 2, text -> {
            if (text.equals("unavailable")) throw new SearchException("Service unavailable", null);
            return new float[]{1, 0};
        });
        try (var search = new LuceneSemanticSearch(directory, model)) {
            search.index(new SearchDocument("one", "original", Map.of()));
            assertThrows(SearchException.class,
                    () -> search.index(new SearchDocument("one", "unavailable", Map.of())));
            assertEquals("original", search.findById("one").orElseThrow().content());
        }
    }

    @Test
    void rejectsDifferentModelsAndDimensionsAndReleasesTheLock() throws Exception {
        EmbeddingModel original = model("test-v1", 2, text -> new float[]{1, 0});
        try (var search = new LuceneSemanticSearch(directory, original)) {
            search.index(new SearchDocument("one", "preserved", Map.of()));
        }
        assertThrows(SearchException.class, () -> new LuceneSemanticSearch(directory,
                model("test-v2", 2, text -> new float[]{1, 0})));
        assertThrows(SearchException.class, () -> new LuceneSemanticSearch(directory,
                model("test-v1", 3, text -> new float[]{1, 0, 0})));
        try (var reopened = new LuceneSemanticSearch(directory, original)) {
            assertEquals("preserved", reopened.findById("one").orElseThrow().content());
        }
    }

    @Test
    void bindsEvenAnEmptyIndexToItsEmbeddingModel() throws Exception {
        try (var ignored = new LuceneSemanticSearch(directory, model("first", 2, text -> new float[]{1, 0}))) {
            // Persist the schema before the first document.
        }
        assertThrows(SearchException.class, () -> new LuceneSemanticSearch(directory,
                model("second", 2, text -> new float[]{1, 0})));
    }

    @Test
    void refusesToModifyALexicalIndex() throws Exception {
        try (var lexical = new LuceneSearchAdapter(new NIOFSDirectory(directory),
                new IndexWriterConfig(new StandardAnalyzer()))) {
            lexical.index(new SearchDocument("one", "Java preference", Map.of()));
        }
        assertThrows(SearchException.class, () -> new LuceneSemanticSearch(directory,
                model("test", 2, text -> new float[]{1, 0})));
        try (var lexical = new LuceneSearchAdapter(new NIOFSDirectory(directory),
                new IndexWriterConfig(new StandardAnalyzer()))) {
            assertEquals("one", lexical.search("Java", 10).results().getFirst().document().id());
        }
    }

    @Test
    void validatesRequestsBeforeCallingTheModelAndRejectsUseAfterClose() throws Exception {
        EmbeddingModel noCalls = model("test", 2, text -> { throw new AssertionError("Unexpected embedding call"); });
        var search = new LuceneSemanticSearch(directory, noCalls);
        try {
            assertThrows(IllegalArgumentException.class, () -> search.search("query", 0));
            assertThrows(IllegalArgumentException.class, () -> search.search("query", 101));
            assertThrows(IllegalArgumentException.class, () -> search.search(" ", 1));
            assertThrows(IllegalArgumentException.class, () -> search.index(new SearchDocument(" ", "text", Map.of())));
        } finally {
            search.close();
        }
        search.close();
        assertThrows(IllegalStateException.class, () -> search.search("query", 1));
        assertThrows(IllegalStateException.class, () -> search.remove("one"));
        assertThrows(IllegalStateException.class, () -> search.findById("one"));
        assertThrows(IllegalStateException.class, () -> search.index(new SearchDocument("one", "text", Map.of())));
    }

    @Test
    void rejectsUnsupportedDimensionsBeforeCreatingADirectory() {
        Path path = directory.resolve("unsupported");
        assertThrows(IllegalArgumentException.class,
                () -> new LuceneSemanticSearch(path, model("test", 1536, text -> new float[1536])));
        assertFalse(Files.exists(path));
    }

    @Test
    void normalizesLargeAndSmallFiniteVectorsWithoutOverflowOrMutation() throws Exception {
        float[] huge = {Float.MAX_VALUE, Float.MAX_VALUE};
        EmbeddingModel model = model("test", 2, text -> text.equals("tiny")
                ? new float[]{Float.MIN_VALUE, Float.MIN_VALUE} : huge);
        try (var search = new LuceneSemanticSearch(directory, model)) {
            search.index(new SearchDocument("one", "huge", Map.of()));
            assertEquals(1.0f, search.search("tiny", 1).results().getFirst().score(), 0.0001f);
            assertArrayEquals(new float[]{Float.MAX_VALUE, Float.MAX_VALUE}, huge);
        }
    }

    private static EmbeddingModel model(String id, int dimensions, java.util.function.Function<String, float[]> embed) {
        return new EmbeddingModel() {
            public String modelId() { return id; }
            public int dimensions() { return dimensions; }
            public float[] embed(String text) { return embed.apply(text); }
        };
    }
}
