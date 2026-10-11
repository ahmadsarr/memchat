package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;

import java.util.List;

public interface WorkingMemory {

    void addMessage(LLmApiClient.ChatMessage message);

    List<LLmApiClient.ChatMessage> getContext();
    List<LLmApiClient.ChatMessage> selectEvictionCandidates();

    String getSummary();

    void appendCore(String section, String content);

    void replaceCore(String section, String content);

    void removeCore(String section);

    void applyCompact(List<LLmApiClient.ChatMessage> messages,String resume);

}
