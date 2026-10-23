package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.impl.ArchivalMemoryImpl;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;
import org.iaproject.memchat.memory.impl.RecallStorageImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecallHistoryIntegrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void toolInvocationsSurviveReopeningEvenWithoutText(String content) {
        var recallFile = directory.resolve("recall.json");
        var manager = manager(recallFile);
        var call = new LLmApiClient.ToolCall("lookup-123", "lookup", "{\"topic\":\"Java\"}");
        var invocation = LLmApiClient.ChatMessage.assistant(content, List.of(call));
        var result = LLmApiClient.ChatMessage.tool(call.id(), "Found documentation");

        manager.addMessage(invocation);
        manager.addMessage(result);

        var reopened = new FileRecallStorageRepository(recallFile);
        assertEquals(invocation, reopened.search("Java", 10).getFirst().content());
        var history = reopened.search("lookup", 10).stream()
                .map(Memory.RecallSearchResult::content).toList();
        assertTrue(history.contains(invocation));
        assertTrue(history.contains(result));
        assertTrue(manager.context().contains(invocation));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void toolResultsSurviveReopeningEvenWithoutText(String content) {
        var recallFile = directory.resolve("recall.json");
        var manager = manager(recallFile);
        var result = LLmApiClient.ChatMessage.tool("lookup-123", content);

        manager.addMessage(result);

        var reopened = new FileRecallStorageRepository(recallFile);
        assertEquals(result, reopened.search("lookup", 10).getFirst().content());
    }

    @Test
    void emptyUnstructuredMessagesRemainExcludedFromRecall() throws Exception {
        var recallFile = directory.resolve("recall.json");
        var manager = manager(recallFile);
        manager.addMessage(LLmApiClient.ChatMessage.assistant(null));
        manager.addMessage(LLmApiClient.ChatMessage.assistant("", List.of()));
        manager.addMessage(LLmApiClient.ChatMessage.user("   "));
        var visible = LLmApiClient.ChatMessage.user("Java");
        manager.addMessage(visible);

        var reopened = new FileRecallStorageRepository(recallFile);
        assertEquals(List.of(visible), reopened.search("Java", 10).stream()
                .map(Memory.RecallSearchResult::content).toList());
        assertEquals(1, new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(recallFile.toFile()).size());
        assertEquals(4, manager.context().stream()
                .filter(message -> message.role() != LLmApiClient.Role.SYSTEM).count());
    }

    private MemoryManager manager(Path recallFile) {
        return new MemoryManager(
                new RecallStorageImpl(new FileRecallStorageRepository(recallFile)),
                new ArchivalMemoryImpl(new FileArchivalMemoryRepository(directory.resolve("archive.json"))),
                (summary, messages) -> "summary");
    }
}
