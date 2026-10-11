package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.LLmApiClient.*;
import org.iaproject.memchat.llm.PromptBuilder;

import java.util.ArrayList;
import java.util.List;

public class MemoryManager {

    private final WorkingMemory workingMemory;
    private final RecallStorage recallStorage;
    private final ArchivalMemory archivalStorage;
    private final ContextControl contextControl;
    private final CompactStrategy compactStrategy;

    public MemoryManager(
            WorkingMemory workingMemory,
            RecallStorage recallStorage,
            ArchivalMemory archivalStorage, ContextControl contextControl, CompactStrategy compactStrategy
    ) {
        this.workingMemory = workingMemory;
        this.recallStorage = recallStorage;
        this.archivalStorage = archivalStorage;
        this.contextControl = contextControl;
        this.compactStrategy = compactStrategy;
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
        workingMemory.addMessage(message);
        if (message.content() != null && !message.content().isBlank()) {
            recallStorage.add(message);
        }
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
        List<ChatMessage> context = new ArrayList<>();

        context.add(ChatMessage.system(PromptBuilder.SYSTEM));
        context.add(ChatMessage.system(workingMemory.getSummary()));
        context.addAll(workingMemory.getContext());
        return List.copyOf(context);
    }

    public List<ChatMessage> context() {
        List<ChatMessage> context = new ArrayList<>(contextWithoutControl());
        switch (this.contextControl.pressure(context)) {
            case NORMAL -> {
            }
            case WARNING -> context.add(warningMessage);
            case FLUSH_REQUIRED -> throw new MemoryFullException();
        }
        return List.copyOf(context);
    }

    public void compact() {

        int previousTokens = contextControl.estimateTokens(contextWithoutControl());
        for (int attempt = 0; attempt < 3; attempt++) {

            if (contextControl.pressure(contextWithoutControl()) != ContextControl.Pressure.FLUSH_REQUIRED) {
                return;
            }
            List<ChatMessage> candidates = workingMemory.selectEvictionCandidates();
            if (candidates.isEmpty()) throw new IllegalStateException("No messages available for compaction");
            String newSummary = compactStrategy.compact(workingMemory.getSummary(), candidates);

            validateSummary(newSummary);

            workingMemory.applyCompact(candidates, newSummary);

            int currentTokens = contextControl.estimateTokens(
                    contextWithoutControl()
            );

            if (currentTokens >= previousTokens) {
                throw new IllegalStateException(
                        "Compaction did not reduce context size"
                );
            }

            previousTokens = currentTokens;
        }

        if (contextControl.pressure(contextWithoutControl())
                == ContextControl.Pressure.FLUSH_REQUIRED) {
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
