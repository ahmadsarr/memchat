package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;

import java.util.List;

public interface RecallStorageRepository {
    String add(LLmApiClient.ChatMessage message);

    List<Memory.RecallSearchResult> search(String query, int limit);
}
