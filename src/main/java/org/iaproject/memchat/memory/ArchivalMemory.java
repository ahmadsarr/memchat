package org.iaproject.memchat.memory;

import java.util.List;

public interface ArchivalMemory {
    String insert(String content);

    List<Memory.MemorySearchResult> search(String query, int limit);

    void update(String id, String content);

    void delete(String id);

    String get(String id);

}
