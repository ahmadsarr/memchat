package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.LLmApiClient.*;
import org.iaproject.memchat.llm.PromptBuilder;
import org.iaproject.memchat.memory.impl.WorkingMemoryImpl;
import org.iaproject.memchat.memory.strategy.CompactStrategy;
import org.iaproject.memchat.memory.strategy.ContextControlStrategy;
import org.iaproject.memchat.memory.strategy.ContextControlStrategyImpl;

import java.util.ArrayList;
import java.util.List;

public class MemoryManager {

    private final WorkingMemory workingMemory;
    private final RecallStorage recallStorage;
    private final ArchivalMemory archivalStorage;
    private final ContextControlStrategy contextControlStrategy;
    private final CompactStrategy compactStrategy;

    private MemoryManager(
            WorkingMemory workingMemory,
            RecallStorage recallStorage,
            ArchivalMemory archivalStorage,
            ContextControlStrategy contextControlStrategy,
            CompactStrategy compactStrategy
    ) {
        this.workingMemory = workingMemory;
        this.recallStorage = recallStorage;
        this.archivalStorage = archivalStorage;
        this.contextControlStrategy = contextControlStrategy;
        this.compactStrategy = compactStrategy;
    }

    public MemoryManager(
            RecallStorage recallStorage,
            ArchivalMemory archivalStorage,
            CompactStrategy compactStrategy
    ) {
        this(new WorkingMemoryImpl(), recallStorage, archivalStorage, new ContextControlStrategyImpl(), compactStrategy);
    }


    public String archive(String content) {
        return archivalStorage.insert(content);
    }

    public List<Memory.MemorySearchResult> searchArchive(
            String query, int limit
    ) {
        return archivalStorage.search(query, limit);
    }

    public List<Memory.RecallSearchResult> searchRecall(
            String query, int limit
    ) {
        return recallStorage.search(query, limit);
    }

    public void update(String id, String content) {
        archivalStorage.update(id, content);
    }

    public void delete(String id) {
        archivalStorage.delete(id);
    }

    public String getById(String id) {
        return archivalStorage.get(id);
    }


    public void addMessage(LLmApiClient.ChatMessage message) {
        java.util.Objects.requireNonNull(message, "message");
        if ((message.content() != null && !message.content().isBlank())
                || (message.toolCalls() != null && !message.toolCalls().isEmpty())
                || (message.toolCallId() != null && !message.toolCallId().isBlank())) {
            recallStorage.add(message);
        }
        workingMemory.addMessage(message);
    }

    public void appendCore(String section, String content) {
        workingMemory.appendCore(section, content);
    }

    public void replaceCore(String section, String content) {
        workingMemory.replaceCore(section, content);
    }

    public void removeCore(String section) {
        workingMemory.removeCore(section);
    }

    private List<ChatMessage> contextWithoutControl() {
        return contextWithoutControl(workingMemory.getSummary(), workingMemory.getContext());
    }

    private List<ChatMessage> contextWithoutControl(String summary, List<ChatMessage> messages) {
        List<ChatMessage> context = new ArrayList<>();

        context.add(ChatMessage.system(PromptBuilder.SYSTEM));
        context.add(ChatMessage.system(summary));
        context.addAll(messages);
        return List.copyOf(context);
    }

    public List<ChatMessage> context() {
        List<ChatMessage> context = new ArrayList<>(contextWithoutControl());
        switch (this.contextControlStrategy.pressure(context)) {
            case NORMAL -> {
            }
            case WARNING -> context.add(warningMessage);
            case FLUSH_REQUIRED -> throw new MemoryFullException();
        }
        return List.copyOf(context);
    }

    public void compact() {

        int previousTokens = contextControlStrategy.estimateTokens(contextWithoutControl());
        for (int attempt = 0; attempt < 3; attempt++) {

            if (contextControlStrategy.pressure(contextWithoutControl()) != ContextControlStrategy.Pressure.FLUSH_REQUIRED) {
                return;
            }
            List<ChatMessage> candidates = workingMemory.selectEvictionCandidates();
            if (candidates.isEmpty()) throw new IllegalStateException("No messages available for compaction");
            String newSummary = compactStrategy.compact(workingMemory.getSummary(), candidates);

            validateSummary(newSummary);

            List<ChatMessage> messages = workingMemory.getContext();
            int currentTokens = contextControlStrategy.estimateTokens(
                    contextWithoutControl(newSummary, messages.subList(candidates.size(), messages.size()))
            );

            if (currentTokens >= previousTokens) {
                throw new IllegalStateException(
                        "Compaction did not reduce context size"
                );
            }

            // Commit only after the replacement has passed every validation.
            workingMemory.applyCompact(candidates, newSummary);
            previousTokens = currentTokens;
        }

        if (contextControlStrategy.pressure(contextWithoutControl())
                == ContextControlStrategy.Pressure.FLUSH_REQUIRED) {
            throw new IllegalStateException(
                    "Context remains full after compaction"
            );
        }
    }

    private static void validateSummary(String newSummary) {
        if (newSummary == null || newSummary.isBlank()) {
            throw new IllegalStateException(
                    "Compaction returned an empty summary"
            );
        }
    }


    private static final ChatMessage warningMessage = ChatMessage.system("""
            MEMORY PRESSURE WARNING
            
            The conversation context is approaching its limit.
            
            Review the current conversation.
            If important reusable information may be lost,
            preserve it using archivalStore or coreAppend.
            
            Do not store duplicate or temporary information.
            """);
}
