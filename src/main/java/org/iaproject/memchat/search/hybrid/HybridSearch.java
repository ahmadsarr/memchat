package org.iaproject.memchat.search.hybrid;

import org.iaproject.memchat.search.api.SearchDocument;
import org.iaproject.memchat.search.api.SearchEngine;
import org.iaproject.memchat.search.api.SearchException;
import org.iaproject.memchat.search.api.SearchHit;
import org.iaproject.memchat.search.api.SearchResult;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Composite search engine with weighted reciprocal rank fusion and delegated writes. */
public final class HybridSearch implements SearchEngine {
    public static final int DEFAULT_RANK_CONSTANT = 60;
    public static final int DEFAULT_CANDIDATE_LIMIT = 100;

    public record Child(SearchEngine engine, double weight) {
        public Child {
            Objects.requireNonNull(engine, "Child engine is required");
            if (!Double.isFinite(weight) || weight <= 0) {
                throw new IllegalArgumentException("Child weight must be finite and positive");
            }
        }
    }

    private final List<Child> children;
    private final double[] normalizedWeights;
    private final int rankConstant;
    private final int candidateLimit;
    private boolean closed;
    private Throwable writeFailure;

    /** Takes ownership of a tree of engines, with equal weights and up to 100 candidates per child. */
    public HybridSearch(List<? extends SearchEngine> engines) {
        this(Objects.requireNonNull(engines, "Children are required").stream()
                .map(engine -> new Child(engine, 1)).toList(), DEFAULT_RANK_CONSTANT, DEFAULT_CANDIDATE_LIMIT);
    }

    /** Constructor validation failures leave ownership of all children with the caller. */
    public HybridSearch(List<Child> children, int rankConstant, int candidateLimit) {
        this.children = List.copyOf(children);
        if (this.children.isEmpty()) throw new IllegalArgumentException("At least one child engine is required");
        if (rankConstant < 0) throw new IllegalArgumentException("Rank constant must not be negative");
        if (candidateLimit < 1 || candidateLimit > 100) {
            throw new IllegalArgumentException("Candidate limit must be between 1 and 100");
        }
        Set<SearchEngine> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Child child : this.children) assertUnique(child.engine(), seen);
        this.rankConstant = rankConstant;
        this.candidateLimit = candidateLimit;
        // Scale before summing to support large finite weights without overflowing.
        double maximumWeight = this.children.stream().mapToDouble(Child::weight).max().orElseThrow();
        double total = this.children.stream().mapToDouble(child -> child.weight() / maximumWeight).sum();
        normalizedWeights = this.children.stream().mapToDouble(child -> child.weight() / maximumWeight / total).toArray();
    }

    @Override
    public synchronized SearchHit search(String query, int limit) {
        requireUsable();
        requireText(query, "Search query");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be between 1 and 100");
        Map<String, Candidate> candidates = new HashMap<>();
        int requested = Math.max(limit, candidateLimit);
        for (int childIndex = 0; childIndex < children.size(); childIndex++) {
            SearchHit hit = children.get(childIndex).engine().search(query, requested);
            if (hit == null || hit.results() == null) throw new SearchException("Child returned no search results", null);
            Set<String> contributed = new HashSet<>();
            for (int i = 0; i < hit.results().size(); i++) {
                SearchResult result = hit.results().get(i);
                if (result == null || result.document() == null) {
                    throw new SearchException("Child returned a result without a document", null);
                }
                SearchDocument document = result.document();
                if (document.id() == null || document.id().isBlank()) {
                    throw new SearchException("Child returned a document without an ID", null);
                }
                Candidate candidate = candidates.computeIfAbsent(document.id(), ignored -> new Candidate(document));
                requireSameDocument(candidate.document, document);
                if (contributed.add(document.id())) {
                    candidate.score += normalizedWeights[childIndex] / ((double) rankConstant + i + 1);
                }
            }
        }
        List<SearchResult> results = candidates.values().stream()
                .sorted(Comparator.comparingDouble((Candidate candidate) -> candidate.score).reversed()
                        .thenComparing(candidate -> candidate.document.id()))
                .limit(limit)
                .map(candidate -> new SearchResult(candidate.document, (float) candidate.score)).toList();
        return new SearchHit(candidates.size(), results);
    }

    @Override
    public synchronized Optional<SearchDocument> findById(String id) {
        requireUsable();
        requireText(id, "Document ID");
        SearchDocument found = null;
        for (Child child : children) {
            Optional<SearchDocument> result = child.engine().findById(id);
            if (result.isPresent()) {
                SearchDocument document = result.get();
                if (!id.equals(document.id())) throw new SearchException("Child returned a different document ID", null);
                if (found != null) requireSameDocument(found, document);
                found = document;
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public synchronized void index(SearchDocument document) {
        requireUsable();
        Objects.requireNonNull(document, "Document is required");
        requireText(document.id(), "Document ID");
        requireText(document.content(), "Document content");
        writeToChildren("index", engine -> engine.index(document));
    }

    @Override
    public synchronized void remove(String id) {
        requireUsable();
        requireText(id, "Document ID");
        writeToChildren("remove", engine -> engine.remove(id));
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        for (int i = children.size() - 1; i >= 0; i--) {
            try {
                children.get(i).engine().close();
            } catch (IOException | RuntimeException | Error exception) {
                if (failure == null) failure = exception;
                else if (failure != exception) failure.addSuppressed(exception);
            }
        }
        if (failure instanceof IOException exception) throw exception;
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    private void writeToChildren(String operation, Consumer<SearchEngine> write) {
        int completed = 0;
        try {
            for (Child child : children) {
                write.accept(child.engine());
                completed++;
            }
        } catch (RuntimeException exception) {
            writeFailure = exception;
            throw new SearchException("Hybrid " + operation + " failed after " + completed
                    + " successful child writes. Reconcile the child indexes before constructing a new composite.", exception);
        } catch (Error error) {
            writeFailure = error;
            throw error;
        }
    }

    private void requireUsable() {
        if (closed) throw new IllegalStateException("Hybrid search is closed");
        if (writeFailure != null) {
            throw new SearchException("Hybrid search is unavailable after a failed write; reconcile its child indexes", writeFailure);
        }
    }

    private static void assertUnique(SearchEngine engine, Set<SearchEngine> seen) {
        if (!seen.add(engine)) throw new IllegalArgumentException("An engine cannot be shared within a composite tree");
        if (engine instanceof HybridSearch composite) {
            for (Child child : composite.children) assertUnique(child.engine(), seen);
        }
    }

    private static void requireSameDocument(SearchDocument first, SearchDocument second) {
        if (!first.equals(second)) {
            throw new SearchException("Conflicting versions of document " + first.id() + "; reconcile the child indexes", null);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private static final class Candidate {
        final SearchDocument document;
        double score;

        Candidate(SearchDocument document) { this.document = document; }
    }
}
