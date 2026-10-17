package org.iaproject.memchat.search.api;

public interface SearchReader {
    SearchHit search(String query, int limit) throws SearchException;
}
