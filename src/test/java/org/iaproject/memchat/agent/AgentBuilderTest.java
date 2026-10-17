package org.iaproject.memchat.agent;

import org.iaproject.memchat.UserInteraction;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.memory.impl.ArchivalMemoryImpl;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;
import org.iaproject.memchat.memory.impl.RecallStorageImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsWithCustomClientAndRepositories() {
        LLmApiClient client = (messages, tools) ->
                new LLmApiClient.ChatResponse("answer", List.of());
        UserInteraction interaction = new UserInteraction() {
            public String read() { return "input"; }
            public String ask(String message) { return "answer"; }
            public void display(String answer) { }
        };

        AgentLoop agent = AgentBuilder.builder()
                .client(client)
                .userInteraction(interaction)
                .maxSteps(2)
                .recallStorageRepository(new FileRecallStorageRepository(
                        temporaryDirectory.resolve("recall.json")))
                .archivalMemoryRepository(new FileArchivalMemoryRepository(
                        temporaryDirectory.resolve("memory.json")))
                .build();

        assertNotNull(agent);
        agent.close();
    }

    @Test
    void acceptsAnOverriddenMemoryManager() {
        MemoryManager manager = new MemoryManager(
                new RecallStorageImpl(new FileRecallStorageRepository(
                        temporaryDirectory.resolve("recall.json"))),
                new ArchivalMemoryImpl(new FileArchivalMemoryRepository(
                        temporaryDirectory.resolve("memory.json"))),
                (summary, messages) -> "summary"
        );

        AgentLoop agent = AgentBuilder.builder()
                .client((messages, tools) -> new LLmApiClient.ChatResponse("answer", List.of()))
                .memoryManager(manager)
                .build();

        assertNotNull(agent);
        agent.close();
    }
}
