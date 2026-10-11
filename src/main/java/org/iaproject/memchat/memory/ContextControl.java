package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;

import java.util.List;

public interface ContextControl {

    int estimateTokens(List<LLmApiClient.ChatMessage> messages);

    public enum Pressure {
        NORMAL,
        WARNING,
        FLUSH_REQUIRED
    }
     Pressure pressure(List<LLmApiClient.ChatMessage> messages) ;
}
