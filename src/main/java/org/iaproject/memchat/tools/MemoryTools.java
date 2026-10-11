package org.iaproject.memchat.tools;

public interface MemoryTools {

    String archivalSearch(String query, int limit);


    String archivalStore(String content);


    String archivalUpdate(String id, String content);


    String archivalDelete(String id);


    String getArchive(String id);


    String recallSearch(String query, int limit);

    // Core Memory
    String coreAppend(String section, String content);

    String coreReplace(String section, String content);

    String coreRemove(String section);
}