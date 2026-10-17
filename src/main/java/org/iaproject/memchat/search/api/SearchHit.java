package org.iaproject.memchat.search.api;

import java.util.List;

public record SearchHit(long totalHits, List<SearchResult> results) {
}
