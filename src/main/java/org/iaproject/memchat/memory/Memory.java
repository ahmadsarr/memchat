package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;

public final class Memory {
    public record MemorySearchResult(String id, String content, long score) {
    }

    public record RecallSearchResult(String id, LLmApiClient.ChatMessage content, long score) {
    }
}
