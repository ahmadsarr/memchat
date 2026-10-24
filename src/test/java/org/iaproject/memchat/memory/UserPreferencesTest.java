package org.iaproject.memchat.memory;

import org.iaproject.memchat.agent.AgentBuilder;
import org.iaproject.memchat.agent.AgentRunState;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.impl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UserPreferencesTest {
    @TempDir Path directory;

    @Test
    void persistsReplacementsAndDeletionWithoutLeakingBetweenUsers() {
        Path file = directory.resolve("alice/preferences.json");
        var alice = new FileUserPreferencesRepository(file);
        var bob = new FileUserPreferencesRepository(directory.resolve("bob/preferences.json"));
        alice.set("response.language", "French");
        Map<String, String> snapshot = alice.list();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", "y"));
        alice.set("response.language", "English");
        assertEquals("French", snapshot.get("response.language"));
        assertEquals(Map.of("response.language", "English"), new FileUserPreferencesRepository(file).list());
        assertTrue(bob.list().isEmpty());
        alice.remove("response.language");
        alice.remove("response.language");
        assertTrue(new FileUserPreferencesRepository(file).list().isEmpty());
    }

    @Test
    void boundsProfileAndRejectsInvalidDataWithoutMutatingCommittedState() throws Exception {
        Path file = directory.resolve("preferences.json");
        var repository = new FileUserPreferencesRepository(file);
        repository.set("language", "French");
        String committed = Files.readString(file);
        assertThrows(IllegalArgumentException.class, () -> repository.set("Bad key", "x"));
        assertThrows(IllegalArgumentException.class, () -> repository.set("style", " "));
        assertThrows(IllegalArgumentException.class, () -> repository.set("style", "x".repeat(501)));
        assertEquals(committed, Files.readString(file));
        assertEquals(Map.of("language", "French"), repository.list());
        for (int i = 0; i < 49; i++) repository.set("key" + i, "v");
        assertThrows(IllegalArgumentException.class, () -> repository.set("overflow", "v"));
        var large = new FileUserPreferencesRepository(directory.resolve("large.json"));
        for (int i = 0; i < 7; i++) large.set("key" + i, "x".repeat(500));
        assertThrows(IllegalArgumentException.class, () -> large.set("overflow", "x".repeat(500)));
    }

    @Test
    void refusesCorruptProfilesAndKeepsMemoryUnchangedOnWriteFailure() throws Exception {
        Path corrupt = directory.resolve("corrupt.json");
        Files.writeString(corrupt, "{broken");
        assertThrows(IllegalStateException.class, () -> new FileUserPreferencesRepository(corrupt));
        Files.writeString(corrupt, "{\"language\":null}");
        assertThrows(IllegalStateException.class, () -> new FileUserPreferencesRepository(corrupt));
        Path file = directory.resolve("blocked/preferences.json");
        var repository = new FileUserPreferencesRepository(file);
        Files.writeString(file.getParent(), "not a directory");
        assertThrows(IllegalStateException.class, () -> repository.set("language", "French"));
        assertTrue(repository.list().isEmpty());
    }

    @Test
    void forgettingThroughToolRemovesPreferenceFromFollowingContext() {
        var preferences = new FileUserPreferencesRepository(directory.resolve("preferences.json"));
        preferences.set("response.style", "short");
        AtomicInteger calls = new AtomicInteger();
        try (var agent = AgentBuilder.builder()
                .userPreferencesRepository(preferences)
                .recallStorageRepository(new FileRecallStorageRepository(directory.resolve("recall.json")))
                .archivalMemoryRepository(new FileArchivalMemoryRepository(directory.resolve("archive.json")))
                .client((messages, tools) -> {
                    if (calls.getAndIncrement() == 0) {
                        assertTrue(messages.stream().anyMatch(m -> m.content() != null && m.content().contains("response.style")));
                        return new LLmApiClient.ChatResponse(null, List.of(new LLmApiClient.ToolCall(
                                "forget-1", "preferenceRemove", "{\"key\":\"response.style\"}")));
                    }
                    assertTrue(messages.stream().filter(m -> m.role() == LLmApiClient.Role.SYSTEM)
                            .noneMatch(m -> m.content() != null && m.content().contains("response.style")));
                    return new LLmApiClient.ChatResponse("Forgotten", List.of());
                }).build()) {
            assertEquals(AgentRunState.SUCCEEDED, agent.run("Forget my response style preference").status());
        }
        assertTrue(new FileUserPreferencesRepository(directory.resolve("preferences.json")).list().isEmpty());
    }

    @Test
    void toolUpdatesAppearInNextContextAndSurviveNewAgentWithoutSharingConversation() {
        var preferences = new FileUserPreferencesRepository(directory.resolve("preferences.json"));
        AtomicInteger calls = new AtomicInteger();
        List<List<LLmApiClient.ChatMessage>> requests = new ArrayList<>();
        var builder = AgentBuilder.builder()
                .userPreferencesRepository(preferences)
                .recallStorageRepository(new FileRecallStorageRepository(directory.resolve("recall.json")))
                .archivalMemoryRepository(new FileArchivalMemoryRepository(directory.resolve("archive.json")))
                .client((messages, tools) -> {
                    requests.add(List.copyOf(messages));
                    if (calls.getAndIncrement() == 0) {
                        return new LLmApiClient.ChatResponse(null, List.of(new LLmApiClient.ToolCall("pref-1", "preferenceSet", "{\"key\":\"response.language\",\"value\":\"French\"}")));
                    }
                    return new LLmApiClient.ChatResponse("Done", List.of());
                });
        try (var first = builder.build()) {
            assertEquals(AgentRunState.SUCCEEDED, first.run("Remember that I prefer French").status());
        }
        assertEquals("French", preferences.list().get("response.language"));
        assertTrue(requests.get(1).stream().anyMatch(m -> m.content() != null
                && m.content().contains("\"response.language\":\"French\"")));
        try (var second = builder.build()) {
            assertEquals(AgentRunState.SUCCEEDED, second.run("Hello again").status());
        }
        var resumed = requests.get(requests.size() - 1);
        assertFalse(resumed.stream().anyMatch(m -> "Remember that I prefer French".equals(m.content())));
        assertTrue(resumed.stream().anyMatch(m -> m.content() != null && m.content().contains("French")));
    }
}
