package org.iaproject.memchat.search.hybrid;

import org.iaproject.memchat.search.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HybridSearchTest {
    @Test
    void fusesRanksAndOversamplesToFindAgreementBelowTheFirstPosition() throws Exception {
        var lexical = engine("lexical", "shared");
        var semantic = engine("semantic", "shared");
        try (var hybrid = new HybridSearch(List.of(lexical, semantic))) {
            var result = hybrid.search("query", 1);
            assertEquals("shared", result.results().getFirst().document().id());
            assertEquals(1.0 / 62, result.results().getFirst().score(), 0.000001);
            assertEquals(3, result.totalHits());
            assertEquals(100, lexical.requestedLimit);
            assertEquals(100, semantic.requestedLimit);
        }
    }

    @Test
    void usesRelativeWeightsWithoutComparingNativeScores() throws Exception {
        var lexical = engine("lexical");
        lexical.ranking = List.of(new SearchResult(document("lexical"), 1_000_000));
        var semantic = engine("semantic");
        semantic.ranking = List.of(new SearchResult(document("semantic"), 0.001f));
        try (var hybrid = new HybridSearch(List.of(new HybridSearch.Child(lexical, 1),
                new HybridSearch.Child(semantic, 3)), 60, 10)) {
            var result = hybrid.search("query", 2);
            assertEquals("semantic", result.results().getFirst().document().id());
            assertEquals(0.75 / 61, result.results().getFirst().score(), 0.000001);
            assertEquals(0.25 / 61, result.results().getLast().score(), 0.000001);
        }
    }

    @Test
    void duplicateHitsDoNotBoostASingleChildAndTiesAreDeterministic() throws Exception {
        try (var hybrid = new HybridSearch(List.of(engine("z", "z"), engine("a")))) {
            var result = hybrid.search("query", 10);
            assertEquals(List.of("a", "z"), result.results().stream().map(hit -> hit.document().id()).toList());
            assertEquals(result.results().getFirst().score(), result.results().getLast().score());
            assertEquals(2, result.totalHits());
        }
    }

    @Test
    void handlesAnEmptyChildAndDoesNotSumBackendHitCounts() throws Exception {
        try (var hybrid = new HybridSearch(List.of(engine(), engine("one", "two")))) {
            var result = hybrid.search("query", 5);
            assertEquals(2, result.totalHits());
            assertEquals(2, result.results().size());
        }
    }

    @Test
    void doesNotSilentlyDegradeWhenAChildIsUnavailableAndAllowsReadRetries() throws Exception {
        var lexical = engine("one");
        var semantic = engine("one");
        semantic.readFailure = new SearchException("Embedding endpoint unavailable", null);
        try (var hybrid = new HybridSearch(List.of(lexical, semantic))) {
            assertSame(semantic.readFailure, assertThrows(SearchException.class, () -> hybrid.search("query", 1)));
            semantic.readFailure = null;
            assertEquals("one", hybrid.search("query", 1).results().getFirst().document().id());
        }
    }

    @Test
    void compositeAndLeafEnginesHaveTheSameContractAndCanBeNested() throws Exception {
        SearchEngine subtree = new HybridSearch(List.of(engine("a", "b"), engine("a", "b")));
        SearchEngine root = new HybridSearch(List.of(subtree, engine("b")));
        try (root) {
            assertEquals("b", root.search("query", 1).results().getFirst().document().id());
            root.index(document("new"));
            assertEquals(document("new"), root.findById("new").orElseThrow());
            root.remove("new");
            assertTrue(root.findById("new").isEmpty());
        }
    }

    @Test
    void delegatesUpsertsAndRemovalsToEveryChild() throws Exception {
        var first = engine();
        var second = engine();
        try (var hybrid = new HybridSearch(List.of(first, second))) {
            hybrid.index(document("one"));
            var updated = new SearchDocument("one", "updated", Map.of("type", "preference"));
            hybrid.index(updated);
            assertEquals(updated, first.documents.get("one"));
            assertEquals(updated, second.documents.get("one"));
            assertEquals(updated, hybrid.findById("one").orElseThrow());
            hybrid.remove("one");
            assertTrue(first.documents.isEmpty());
            assertTrue(second.documents.isEmpty());
        }
        assertEquals(1, first.closes);
        assertEquals(1, second.closes);
    }

    @Test
    void exposesPartialWritesAndBlocksOperationsUntilReconciliation() throws Exception {
        var first = engine();
        var second = engine();
        second.writeFailure = new SearchException("Embeddings unavailable", null);
        try (var hybrid = new HybridSearch(List.of(first, second))) {
            var failure = assertThrows(SearchException.class, () -> hybrid.index(document("one")));
            assertTrue(failure.getMessage().contains("1 successful child writes"));
            assertSame(second.writeFailure, failure.getCause());
            assertTrue(first.documents.containsKey("one"));
            assertTrue(second.documents.isEmpty());
            assertThrows(SearchException.class, () -> hybrid.search("query", 1));
            assertThrows(SearchException.class, () -> hybrid.findById("one"));
            assertThrows(SearchException.class, () -> hybrid.index(document("two")));
            assertThrows(SearchException.class, () -> hybrid.remove("one"));
        }
    }

    @Test
    void deletionFailuresAlsoBlockTheComposite() throws Exception {
        var first = engine("one");
        var second = engine("one");
        second.writeFailure = new IllegalStateException("disk failure");
        try (var hybrid = new HybridSearch(List.of(first, second))) {
            assertThrows(SearchException.class, () -> hybrid.remove("one"));
            assertFalse(first.documents.containsKey("one"));
            assertTrue(second.documents.containsKey("one"));
            assertThrows(SearchException.class, () -> hybrid.search("query", 1));
        }
    }

    @Test
    void refusesConflictingDocumentVersionsInsteadOfSilentlyPickingOne() throws Exception {
        var first = engine("one");
        var second = engine("one");
        var stale = new SearchDocument("one", "stale content", Map.of());
        second.ranking = List.of(new SearchResult(stale, 1));
        second.documents.put("one", stale);
        try (var hybrid = new HybridSearch(List.of(first, second))) {
            assertThrows(SearchException.class, () -> hybrid.search("query", 1));
            assertThrows(SearchException.class, () -> hybrid.findById("one"));
        }
    }

    @Test
    void lookupCanFindADocumentInAnyChild() throws Exception {
        try (var hybrid = new HybridSearch(List.of(engine(), engine("one")))) {
            assertEquals(document("one"), hybrid.findById("one").orElseThrow());
            assertTrue(hybrid.findById("missing").isEmpty());
        }
    }

    @Test
    void closesEveryChildEvenWhenClosingFailsAndDoesNotCloseTwice() throws Exception {
        var first = engine();
        var second = engine();
        first.closeFailure = new IOException("first");
        second.closeFailure = new IOException("second");
        var hybrid = new HybridSearch(List.of(first, second));
        var failure = assertThrows(IOException.class, hybrid::close);
        assertSame(second.closeFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(first.closeFailure, failure.getSuppressed()[0]);
        assertEquals(1, first.closes);
        assertEquals(1, second.closes);
        hybrid.close();
        assertEquals(1, first.closes);
        assertThrows(IllegalStateException.class, () -> hybrid.search("query", 1));
    }

    @Test
    void rejectsSharedLeavesAcrossNestedCompositesWithoutTakingOwnership() throws Exception {
        var shared = engine();
        try (var subtree = new HybridSearch(List.of(shared))) {
            assertThrows(IllegalArgumentException.class, () -> new HybridSearch(List.of(subtree, shared)));
            assertEquals(0, shared.closes);
        }
        assertEquals(1, shared.closes);
    }

    @Test
    void handlesLargeFiniteWeightsWithoutOverflow() throws Exception {
        try (var hybrid = new HybridSearch(List.of(new HybridSearch.Child(engine("a"), Double.MAX_VALUE),
                new HybridSearch.Child(engine("b"), Double.MAX_VALUE)), Integer.MAX_VALUE, 10)) {
            var result = hybrid.search("query", 2);
            assertTrue(result.results().stream().allMatch(hit -> Float.isFinite(hit.score()) && hit.score() > 0));
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidWeights(double weight) {
        assertThrows(IllegalArgumentException.class, () -> new HybridSearch.Child(engine(), weight));
    }

    @Test
    void validatesInputsBeforeCallingChildrenAndExpandsSmallCandidateBudgets() throws Exception {
        var child = engine("one");
        try (var hybrid = new HybridSearch(List.of(new HybridSearch.Child(child, 1)), 60, 5)) {
            assertThrows(IllegalArgumentException.class, () -> hybrid.search(" ", 1));
            assertThrows(IllegalArgumentException.class, () -> hybrid.search("query", 0));
            assertThrows(IllegalArgumentException.class, () -> hybrid.search("query", 101));
            assertThrows(IllegalArgumentException.class, () -> hybrid.index(new SearchDocument(" ", "text", Map.of())));
            assertEquals(0, child.searches);
            hybrid.search("query", 20);
            assertEquals(20, child.requestedLimit);
        }
        assertThrows(IllegalArgumentException.class, () -> new HybridSearch(List.of()));
    }

    private static SearchDocument document(String id) {
        return new SearchDocument(id, "content " + id, Map.of());
    }

    private static StubEngine engine(String... ids) {
        return new StubEngine(java.util.Arrays.stream(ids).map(id -> new SearchResult(document(id), 1)).toList());
    }

    private static final class StubEngine implements SearchEngine {
        List<SearchResult> ranking;
        final Map<String, SearchDocument> documents = new HashMap<>();
        int requestedLimit;
        int searches;
        int closes;
        RuntimeException writeFailure;
        RuntimeException readFailure;
        IOException closeFailure;

        StubEngine(List<SearchResult> ranking) {
            this.ranking = ranking;
            ranking.forEach(result -> documents.put(result.document().id(), result.document()));
        }

        public SearchHit search(String query, int limit) {
            if (readFailure != null) throw readFailure;
            searches++;
            requestedLimit = limit;
            return new SearchHit(9_999, ranking.stream().limit(limit).toList());
        }

        public Optional<SearchDocument> findById(String id) { return Optional.ofNullable(documents.get(id)); }
        public void index(SearchDocument document) {
            if (writeFailure != null) throw writeFailure;
            documents.put(document.id(), document);
        }
        public void remove(String id) {
            if (writeFailure != null) throw writeFailure;
            documents.remove(id);
        }
        public void close() throws IOException {
            closes++;
            if (closeFailure != null) throw closeFailure;
        }
    }
}
