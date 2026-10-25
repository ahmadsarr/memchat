package org.iaproject.memchat.session;

import org.iaproject.memchat.UserInteraction;
import org.iaproject.memchat.agent.AgentBuilder;
import org.iaproject.memchat.agent.AgentLoop;
import org.iaproject.memchat.agent.AgentRunState;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void delegatesRunsAndTracksActivity() {
        try (Session session = new Session(agent())) {
            assertNotNull(session.id());
            assertFalse(session.lastUpdate().isBefore(session.createdAt()));

            var result = session.run("hello");

            assertEquals(AgentRunState.SUCCEEDED, result.status());
            assertEquals("answer", result.finalResponse());
            assertFalse(session.lastUpdate().isBefore(session.createdAt()));
        }
    }

    @Test
    void managerCreatesFindsAndClosesSessions() {
        try (SessionManager manager = new SessionManager(temporaryDirectory)) {
            Session first = manager.create(config());
            Session second = manager.create(config());

            assertEquals(2, manager.size());
            assertNotEquals(first.id(), second.id());
            assertSame(first, manager.require(first.id()));
            assertTrue(manager.close(first.id()));
            assertEquals(1, manager.size());
            assertEquals(AgentLoop.State.CLOSED, first.state());
            assertFalse(manager.close(first.id()));
        }
    }

    private AgentLoop agent() {
        return AgentBuilder.builder()
                .client((messages, tools) -> new org.iaproject.memchat.llm.LLmApiClient.ChatResponse("answer", List.of()))
                .recallStorageRepository(new FileRecallStorageRepository(temporaryDirectory.resolve("recall.json")))
                .archivalMemoryRepository(new FileArchivalMemoryRepository(temporaryDirectory.resolve("memory.json")))
                .userInteraction(interaction())
                .build();
    }

    private static UserInteraction interaction() {
        return new UserInteraction() {
            @Override public String read() { return "input"; }
            @Override public String ask(String message) { return "answer"; }
            @Override public void display(String answer) { }
        };
    }

    private static AgentConfig config() {
        return new AgentConfig("http://localhost", "test", "key", 1, null, null, interaction());
    }
}
