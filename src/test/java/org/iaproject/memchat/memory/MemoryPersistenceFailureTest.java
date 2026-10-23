package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;
import org.iaproject.memchat.memory.impl.ArchivalMemoryImpl;
import org.iaproject.memchat.memory.impl.RecallStorageImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryPersistenceFailureTest {
    @TempDir Path directory;

    @Test
    void failedArchiveInsertDoesNotLeaveAGhostEntry() throws Exception {
        Path file = directory.resolve("archive.json");
        var repository = new FileArchivalMemoryRepository(file);
        blockWrites(file);

        assertThrows(IllegalStateException.class, () -> repository.insert("phantom"));
        assertTrue(repository.search("phantom", 10).isEmpty());
        unblockWrites(file);
        String id = repository.insert("saved");
        var reopened = new FileArchivalMemoryRepository(file);
        assertEquals("saved", reopened.get(id));
        assertTrue(reopened.search("phantom", 10).isEmpty());
    }

    @Test
    void failedArchiveUpdateAndDeletePreserveTheLastCommittedValue() throws Exception {
        Path file = directory.resolve("archive.json");
        var repository = new FileArchivalMemoryRepository(file);
        String id = repository.insert("original");
        byte[] committed = Files.readAllBytes(file);
        Files.delete(file);
        blockWrites(file);

        assertThrows(IllegalStateException.class, () -> repository.update(id, "modified"));
        assertEquals("original", repository.get(id));
        assertThrows(IllegalStateException.class, () -> repository.delete(id));
        assertEquals("original", repository.get(id));

        unblockWrites(file);
        Files.write(file, committed);
        repository.update(id, "retried");
        assertEquals("retried", new FileArchivalMemoryRepository(file).get(id));
        repository.delete(id);
        assertNull(new FileArchivalMemoryRepository(file).get(id));
    }

    @Test
    void failedRecallAddDoesNotLeakIntoTheNextSave() throws Exception {
        Path file = directory.resolve("recall.json");
        var repository = new FileRecallStorageRepository(file);
        blockWrites(file);

        assertThrows(IllegalStateException.class,
                () -> repository.add(LLmApiClient.ChatMessage.user("phantom")));
        assertTrue(repository.search("phantom", 10).isEmpty());
        unblockWrites(file);
        repository.add(LLmApiClient.ChatMessage.user("saved"));
        var reopened = new FileRecallStorageRepository(file);
        assertEquals(1, reopened.search("saved", 10).size());
        assertTrue(reopened.search("phantom", 10).isEmpty());
    }

    @Test
    void createsParentDirectoriesAndLeavesNoTemporaryFiles() throws Exception {
        Path nested = directory.resolve("nested/session");
        Path archive = nested.resolve("archive.json");
        Path recall = nested.resolve("recall.json");
        String id = new FileArchivalMemoryRepository(archive).insert("durable");
        new FileRecallStorageRepository(recall).add(LLmApiClient.ChatMessage.user("history"));

        assertEquals("durable", new FileArchivalMemoryRepository(archive).get(id));
        assertEquals(1, new FileRecallStorageRepository(recall).search("history", 10).size());
        try (var files = Files.list(nested)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void failedRecallSaveDoesNotEnterTheWorkingContext() throws Exception {
        Path recall = directory.resolve("recall.json");
        var manager = new MemoryManager(
                new RecallStorageImpl(new FileRecallStorageRepository(recall)),
                new ArchivalMemoryImpl(new FileArchivalMemoryRepository(directory.resolve("archive.json"))),
                (summary, messages) -> "summary");
        var message = LLmApiClient.ChatMessage.user("retry me");
        blockWrites(recall);

        assertThrows(IllegalStateException.class, () -> manager.addMessage(message));
        assertFalse(manager.context().contains(message));
        unblockWrites(recall);
        manager.addMessage(message);
        assertEquals(1, manager.context().stream().filter(message::equals).count());
        assertEquals(1, manager.searchRecall("retry", 10).size());
    }

    private void blockWrites(Path file) throws Exception {
        Files.createDirectory(file);
        Files.writeString(file.resolve("blocker"), "keep");
    }

    private void unblockWrites(Path file) throws Exception {
        Files.delete(file.resolve("blocker"));
        Files.delete(file);
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
