package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.strategy.CompactStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {
    @Test
    void delegatesArchivalOperationsAndAddsMessagesToWorkingContext() {
        var archival = new StubArchivalMemory();
        var recall = new StubRecallStorage();
        MemoryManager manager = new MemoryManager(recall, archival, compactStrategy());

        manager.archive("fact");
        manager.update("id", "updated");
        manager.delete("id");
        assertNull(manager.getById("missing"));
        manager.addMessage(LLmApiClient.ChatMessage.user("hello"));

        assertEquals(1, archival.inserted);
        assertEquals("updated", archival.updatedContent);
        assertEquals(1, archival.deleted);
        assertEquals(1, recall.added);
        assertTrue(manager.context().stream().anyMatch(message ->
                "hello".equals(message.content())));
    }

    @Test
    void delegatesCoreOperations() {
        var working = new RecordingWorkingMemory();
        var manager = new MemoryManager(
                new StubRecallStorage(),
                new StubArchivalMemory(),
                compactStrategy()
        );

        manager.appendCore("facts", "one");
        manager.replaceCore("facts", "two");
        manager.removeCore("facts");

        assertNotNull(working);
    }

    private static CompactStrategy compactStrategy() {
        return (summary, messages) -> "summary";
    }

    private static final class RecordingWorkingMemory implements WorkingMemory {
        public void addMessage(LLmApiClient.ChatMessage message) { }
        public List<LLmApiClient.ChatMessage> getContext() { return List.of(); }
        public List<LLmApiClient.ChatMessage> selectEvictionCandidates() { return List.of(); }
        public String getSummary() { return ""; }
        public void appendCore(String section, String content) { }
        public void replaceCore(String section, String content) { }
        public void removeCore(String section) { }
        public void applyCompact(List<LLmApiClient.ChatMessage> messages, String resume) { }
    }

    private static final class StubRecallStorage implements RecallStorage {
        int added;
        public String add(LLmApiClient.ChatMessage message) { added++; return "id"; }
        public List<Memory.RecallSearchResult> search(String query, int limit) { return List.of(); }
    }

    private static final class StubArchivalMemory implements ArchivalMemory {
        int inserted;
        int deleted;
        String updatedContent;
        public String insert(String content) { inserted++; return "id"; }
        public List<Memory.MemorySearchResult> search(String query, int limit) { return List.of(); }
        public void update(String id, String content) { updatedContent = content; }
        public void delete(String id) { deleted++; }
        public String get(String id) { return null; }
    }
}
