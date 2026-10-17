package org.iaproject.memchat.search.api;

import java.util.Optional;

public interface SearchReader {
    SearchHit search(String query, int limit) throws SearchException;

    Optional<SearchDocument> findById(String documentId) throws SearchException;
}
