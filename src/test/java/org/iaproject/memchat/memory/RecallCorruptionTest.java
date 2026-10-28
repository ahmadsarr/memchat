package org.iaproject.memchat.memory;

import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RecallCorruptionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"[null]", "[{\"id\":\"entry-1\",\"message\":null}]"})
    void rejectsUnsearchableEntriesAtLoadWithoutChangingFile(String json) throws Exception {
        Path file = directory.resolve("recall.json");
        Files.writeString(file, json);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new FileRecallStorageRepository(file));
        assertEquals("Unable to load recall memory", failure.getMessage());
        assertEquals(json, Files.readString(file));
    }
}
