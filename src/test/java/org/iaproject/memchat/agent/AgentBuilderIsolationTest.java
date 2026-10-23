package org.iaproject.memchat.agent;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.memory.impl.ArchivalMemoryImpl;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;
import org.iaproject.memchat.memory.impl.RecallStorageImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentBuilderIsolationTest {
    @TempDir Path directory;

    @Test
    void reusingABuilderDoesNotShareGeneratedWorkingMemory() throws Exception {
        List<List<LLmApiClient.ChatMessage>> requests = new ArrayList<>();
        var builder = builder(directory).client((messages, tools) -> {
            requests.add(List.copyOf(messages));
            return new LLmApiClient.ChatResponse("answer", List.of());
        });
        var first = builder.build();
        var second = builder.build();
        try {
            first.run("first conversation");
            second.run("second conversation");
            assertFalse(requests.get(1).stream().anyMatch(message ->
                    "first conversation".equals(message.content())));
            assertTrue(requests.get(1).stream().anyMatch(message ->
                    "second conversation".equals(message.content())));
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void explicitlySuppliedMemoryCanStillBeShared() throws Exception {
        List<List<LLmApiClient.ChatMessage>> requests = new ArrayList<>();
        var manager = manager(directory);
        var builder = AgentBuilder.builder().memoryManager(manager).client((messages, tools) -> {
            requests.add(List.copyOf(messages));
            return new LLmApiClient.ChatResponse("answer", List.of());
        });
        var first = builder.build();
        var second = builder.build();
        try {
            first.run("shared conversation");
            second.run("next turn");
            assertTrue(requests.get(1).stream().anyMatch(message ->
                    "shared conversation".equals(message.content())));
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void aNewBuildUsesItsSelectedClientForCompaction() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var builder = builder(directory).client((messages, tools) -> {
            fail("The previous client's compactor must not be reused");
            return null;
        });
        var first = builder.build();
        var second = builder.client((messages, tools) -> {
            calls.incrementAndGet();
            return new LLmApiClient.ChatResponse("summary or answer", List.of());
        }).build();
        try {
            second.run("x".repeat(30_000));
            assertEquals(2, calls.get());
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void overriddenRepositoriesDoNotReadCorruptedDefaultFiles() throws Exception {
        Files.writeString(directory.resolve("recall.json"), "invalid default recall");
        Files.writeString(directory.resolve("memory.json"), "invalid default archive");
        Path log = directory.resolve("child.log");
        String classpath = java.util.Arrays.stream(System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(path -> Path.of(path).toAbsolutePath().toString())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, CustomRepositoryProbe.class.getName())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
        }
    }

    public static class CustomRepositoryProbe {
        public static void main(String[] args) {
            var client = (LLmApiClient) (messages, tools) -> new LLmApiClient.ChatResponse("answer", List.of());
            builder(Path.of(".")).client(client).build().close();
            AgentBuilder.builder().client(client).memoryManager(manager(Path.of("."))).build().close();
        }
    }

    private static AgentBuilder builder(Path directory) {
        return AgentBuilder.builder()
                .recallStorageRepository(new FileRecallStorageRepository(directory.resolve("custom-recall.json")))
                .archivalMemoryRepository(new FileArchivalMemoryRepository(directory.resolve("custom-archive.json")));
    }

    private static MemoryManager manager(Path directory) {
        return new MemoryManager(
                new RecallStorageImpl(new FileRecallStorageRepository(directory.resolve("custom-recall.json"))),
                new ArchivalMemoryImpl(new FileArchivalMemoryRepository(directory.resolve("custom-archive.json"))),
                (summary, messages) -> "summary");
    }
}
