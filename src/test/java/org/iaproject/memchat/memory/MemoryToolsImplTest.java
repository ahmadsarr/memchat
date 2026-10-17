package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.impl.ArchivalMemoryImpl;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;
import org.iaproject.memchat.memory.impl.RecallStorageImpl;
import org.iaproject.memchat.memory.strategy.CompactStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryToolsImplTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void archivalToolOperationsReturnJsonResults() {
        MemoryManager manager = new MemoryManager(
                new RecallStorageImpl(new FileRecallStorageRepository(
                        temporaryDirectory.resolve("recall.json"))),
                new ArchivalMemoryImpl(new FileArchivalMemoryRepository(
                        temporaryDirectory.resolve("memory.json"))),
                compactStrategy()
        );
        var tools = new MemoryToolsImpl(manager);

        String stored = tools.archivalStore("Java is used");
        String id = stored.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        assertTrue(stored.contains("\"success\":true"));
        assertTrue(tools.archivalSearch("Java", 10).contains(id));
        assertTrue(tools.getArchive(id).contains("Java is used"));
        assertTrue(tools.archivalUpdate(id, "Java is updated").contains("updated"));
        assertTrue(tools.archivalDelete(id).contains("deleted"));
    }

    @Test
    void recallAndCoreToolsReturnSuccessfulResults() {
        MemoryManager manager = new MemoryManager(
                new RecallStorageImpl(new FileRecallStorageRepository(
                        temporaryDirectory.resolve("recall.json"))),
                new ArchivalMemoryImpl(new FileArchivalMemoryRepository(
                        temporaryDirectory.resolve("memory.json"))),
                compactStrategy()
        );
        var tools = new MemoryToolsImpl(manager);
        manager.addMessage(LLmApiClient.ChatMessage.user("project Java"));

        assertTrue(tools.recallSearch("Java", 10).contains("success"));
        assertTrue(tools.coreAppend("facts", "Java").contains("core_append"));
        assertTrue(tools.coreReplace("facts", "Java 21").contains("core_replace"));
        assertTrue(tools.coreRemove("facts").contains("core_remove"));
    }

    private static CompactStrategy compactStrategy() {
        return (summary, messages) -> "summary";
    }
}
