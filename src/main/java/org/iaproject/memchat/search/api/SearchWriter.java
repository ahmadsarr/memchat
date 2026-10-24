package org.iaproject.memchat.search.api;

public interface SearchWriter {
    void index(SearchDocument document) throws SearchException;

    void remove(String documentId) throws SearchException;
}
