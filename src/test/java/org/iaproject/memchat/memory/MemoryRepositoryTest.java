package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;
import org.iaproject.memchat.memory.impl.LuceneArchivalMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void archivalMemorySurvivesReopening() {
        Path file = temporaryDirectory.resolve("memory.json");
        var repository = new FileArchivalMemoryRepository(file);
        String id = repository.insert("The user prefers Java and Maven");

        var results = repository.search("Java", 10);
        assertEquals(1, results.size());
        assertEquals(id, results.getFirst().id());

        var reopened = new FileArchivalMemoryRepository(file);
        assertEquals("The user prefers Java and Maven", reopened.get(id));

        reopened.update(id, "The user prefers Java, Maven and JUnit");
        assertTrue(reopened.search("JUnit", 10).stream()
                .anyMatch(result -> result.id().equals(id)));

        reopened.delete(id);
        assertNull(reopened.get(id));
    }

    @Test
    void recallMemoryPersistsToolCallDetails() {
        Path file = temporaryDirectory.resolve("recall.json");
        var repository = new FileRecallStorageRepository(file);
        var message = LLmApiClient.ChatMessage.assistant(
                "",
                List.of(new LLmApiClient.ToolCall("call-1", "remember", "{\"value\":\"Java\"}"))
        );

        String id = repository.add(message);
        var reopened = new FileRecallStorageRepository(file);

        var results = reopened.search("Java", 10);
        assertEquals(1, results.size());
        assertEquals(id, results.getFirst().id());
        assertEquals("call-1", results.getFirst().content().toolCalls().getFirst().id());
    }

    @Test
    void luceneArchivalMemoryIndexesUpdatesDeletesAndReopens() throws Exception {
        Path directory = temporaryDirectory.resolve("memory-index");
        String id;

        try (var repository = new LuceneArchivalMemoryRepository(directory)) {
            id = repository.insert("The user prefers Java and Maven");

            var results = repository.search("Java", 10);
            assertEquals(1, results.size());
            assertEquals(id, results.getFirst().id());
            assertTrue(results.getFirst().score() > 0);

            repository.update(id, "The user prefers Kotlin and Gradle");
            assertTrue(repository.search("Kotlin", 10).stream()
                    .anyMatch(result -> result.id().equals(id)));
            assertTrue(repository.search("Java", 10).isEmpty());
        }

        try (var reopened = new LuceneArchivalMemoryRepository(directory)) {
            assertEquals("The user prefers Kotlin and Gradle", reopened.get(id));
            reopened.delete(id);
            assertNull(reopened.get(id));
            assertTrue(reopened.search("Kotlin", 10).isEmpty());
        }
    }

    @Test
    void repositoriesRejectInvalidSearchLimits() {
        var repository = new FileArchivalMemoryRepository(
                temporaryDirectory.resolve("memory.json")
        );

        assertThrows(IllegalArgumentException.class,
                () -> repository.search("query", 0));
        assertThrows(IllegalArgumentException.class,
                () -> repository.search("query", 21));
    }
}
