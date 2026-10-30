package org.iaproject.memchat.memory;

import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ArchivalCorruptionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"{\"entry\":null}", "{\"entry\":\"\"}",
            "{\"entry\":\"   \"}", "{\"\":\"Java\"}", "{\"   \":\"Java\"}"})
    void rejectsInvalidPersistedEntriesWithoutRewriting(String json) throws Exception {
        Path file = directory.resolve("memory.json");
        Files.writeString(file, json);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new FileArchivalMemoryRepository(file));
        assertEquals("Unable to load archival memory", failure.getMessage());
        assertEquals(json, Files.readString(file));
    }

    @Test
    void restoresSearchableEntriesAndAcceptsEmptyStore() throws Exception {
        Path file = directory.resolve("memory.json");
        Files.writeString(file, "{}");
        var repository = new FileArchivalMemoryRepository(file);
        String id = repository.insert("Java archives");
        var reopened = new FileArchivalMemoryRepository(file);
        assertEquals("Java archives", reopened.get(id));
        assertEquals(id, reopened.search("Java", 1).getFirst().id());
    }
}
