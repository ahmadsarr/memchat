package org.iaproject.memchat.memory;

import org.iaproject.memchat.search.api.SearchException;

import java.util.List;

public interface ArchivalMemoryRepository {
    String insert(String content) throws SearchException;

    List<Memory.MemorySearchResult> search(String query, int limit);

    void update(String id, String content);

    void delete(String id);

    String get(String id);
}
