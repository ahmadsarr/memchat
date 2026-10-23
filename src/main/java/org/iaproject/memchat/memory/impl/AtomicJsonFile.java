package org.iaproject.memchat.memory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Publishes a complete JSON snapshot without truncating the last committed file. */
final class AtomicJsonFile {
    private AtomicJsonFile() { }

    static void write(ObjectMapper mapper, Path file, Object snapshot) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".memchat-", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshot);
            // Fail safely if the filesystem cannot replace the snapshot atomically.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
