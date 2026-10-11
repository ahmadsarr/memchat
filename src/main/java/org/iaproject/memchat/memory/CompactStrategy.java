package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;

import java.util.List;

public interface CompactStrategy {
    String compact(
            String previousSummary,
            List<LLmApiClient.ChatMessage> messages
    );

}
