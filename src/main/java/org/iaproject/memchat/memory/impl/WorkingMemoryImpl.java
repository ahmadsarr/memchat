package org.iaproject.memchat.memory.impl;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.WorkingMemory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WorkingMemoryImpl implements WorkingMemory {

    private final List<LLmApiClient.ChatMessage> messages = new ArrayList<>();
    private final Map<String, String> core = new LinkedHashMap<>();

    private String summary = "";

    public WorkingMemoryImpl() {
    }


    @Override
    public synchronized void addMessage(LLmApiClient.ChatMessage message) {
        messages.add(message);
    }

    @Override
    public synchronized List<LLmApiClient.ChatMessage> getContext() {
        return List.copyOf(messages);
    }

    @Override
    public synchronized List<LLmApiClient.ChatMessage> selectEvictionCandidates() {
        Set<String> pendingToolCalls = new HashSet<>();
        int safeEnd = 0;
        for (int i = 0; i < messages.size(); i++) {
            LLmApiClient.ChatMessage message = messages.get(i);
            if (message.role() == LLmApiClient.Role.ASSISTANT && message.toolCalls() != null) {
                message.toolCalls().forEach(call -> pendingToolCalls.add(call.id()));
            }
            if (message.role() == LLmApiClient.Role.TOOL) {
                pendingToolCalls.remove(message.toolCallId());
            }
            // A tool invocation and all its results must be evicted together.
            if (pendingToolCalls.isEmpty()) {
                safeEnd = i + 1;
                if (safeEnd >= 5) {
                    break;
                }
            }
        }
        return List.copyOf(messages.subList(0, safeEnd));
    }

    @Override
    public synchronized String getSummary() {
        return summary;
    }

    @Override
    public synchronized void appendCore(String section, String content) {
        requireText(section, "Core section");
        requireText(content, "Core content");
        core.merge(section.trim(), content.trim(), (oldValue, newValue) -> oldValue + "\n" + newValue);
    }

    @Override
    public synchronized void replaceCore(String section, String content) {
        requireText(section, "Core section");
        requireText(content, "Core content");
        core.put(section.trim(), content.trim());
    }

    @Override
    public synchronized void removeCore(String section) {
        requireText(section, "Core section");
        core.remove(section.trim());
    }

    @Override
    public synchronized void applyCompact(List<LLmApiClient.ChatMessage> oldMessages, String resume) {
        if (oldMessages == null || oldMessages.isEmpty()) {
            throw new IllegalArgumentException("No messages to evict");
        }

        if (resume == null || resume.isBlank()) {
            throw new IllegalArgumentException("Empty summary");
        }

        int count = oldMessages.size();

        if (count > messages.size() || !messages.subList(0, count).equals(oldMessages)) {
            throw new IllegalStateException("Eviction candidates no longer match FIFO");
        }
        this.summary = resume;
        messages.subList(0, count).clear();
    }


    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be empty");
        }
    }
}
