package org.iaproject.memchat.search.api;

public record SearchResult(SearchDocument document, float score) {
}
