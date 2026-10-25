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

    private static UserMemory memory(Path file) {
        return new UserMemoryImpl(new FileUserPreferencesRepository(file));
    }

    @Test
    void persistsReplacementsAndDeletionWithoutLeakingBetweenUsers() {
        Path file = directory.resolve("alice/preferences.json");
        var alice = memory(file);
        var bob = memory(directory.resolve("bob/preferences.json"));
        alice.set("response.language", "French");
        Map<String, String> snapshot = alice.list();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", "y"));
        alice.set("response.language", "English");
        assertEquals("French", snapshot.get("response.language"));
        assertEquals(Map.of("response.language", "English"), memory(file).list());
        assertTrue(bob.list().isEmpty());
        alice.remove("response.language");
        alice.remove("response.language");
        assertTrue(memory(file).list().isEmpty());
    }

    @Test
    void boundsProfileAndRejectsInvalidDataWithoutMutatingCommittedState() throws Exception {
        Path file = directory.resolve("preferences.json");
        var repository = memory(file);
        repository.set("language", "French");
        String committed = Files.readString(file);
        assertThrows(IllegalArgumentException.class, () -> repository.set("Bad key", "x"));
        assertThrows(IllegalArgumentException.class, () -> repository.set("style", " "));
        assertThrows(IllegalArgumentException.class, () -> repository.set("style", "x".repeat(501)));
        assertEquals(committed, Files.readString(file));
        assertEquals(Map.of("language", "French"), repository.list());
        for (int i = 0; i < 49; i++) repository.set("key" + i, "v");
        assertThrows(IllegalArgumentException.class, () -> repository.set("overflow", "v"));
        var large = memory(directory.resolve("large.json"));
        for (int i = 0; i < 7; i++) large.set("key" + i, "x".repeat(500));
        assertThrows(IllegalArgumentException.class, () -> large.set("overflow", "x".repeat(500)));
    }

    @Test
    void refusesCorruptProfilesAndKeepsMemoryUnchangedOnWriteFailure() throws Exception {
        Path corrupt = directory.resolve("corrupt.json");
        Files.writeString(corrupt, "{broken");
        assertThrows(IllegalStateException.class, () -> memory(corrupt));
        Files.writeString(corrupt, "{\"language\":null}");
        assertThrows(IllegalStateException.class, () -> memory(corrupt));
        Path file = directory.resolve("blocked/preferences.json");
        var repository = memory(file);
        Files.writeString(file.getParent(), "not a directory");
        assertThrows(IllegalStateException.class, () -> repository.set("language", "French"));
        assertTrue(repository.list().isEmpty());
    }

    @Test
    void toolsAcceptUserMemoryWithoutManagerOrRepository() throws Exception {
        var values = new java.util.LinkedHashMap<String, String>();
        UserMemory memory = new UserMemory() {
            public Map<String, String> list() { return Map.copyOf(values); }
            public void set(String key, String value) { values.put(key, value); }
            public void remove(String key) { values.remove(key); }
        };
        var executor = new org.iaproject.memchat.action.ActionExecutor(
                org.iaproject.memchat.tools.ToolParser.parse(List.of(
                        new org.iaproject.memchat.tools.UserPreferencesTools(memory))));
        assertTrue(executor.execute("preferenceSet", "{\"key\":\"response.language\",\"value\":\"French\"}").success());
        assertEquals(Map.of("response.language", "French"), values);
        var listed = executor.execute("preferencesList", "{}");
        assertTrue(listed.success());
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(listed.result());
        assertEquals("French", result.path("preferences").path("response.language").asText());
        assertTrue(executor.execute("preferenceRemove", "{\"key\":\"response.language\"}").success());
        assertTrue(values.isEmpty());
    }

    @Test
    void validatesProfilesWithAnyBackendAndRetainsStateAfterFailedSave() {
        class Repository implements UserPreferencesRepository {
            Map<String, String> stored = Map.of("language", "French");
            boolean fail;
            public Map<String, String> load() { return stored; }
            public void save(Map<String, String> profile) {
                if (fail) throw new IllegalStateException("Storage unavailable");
                stored = profile;
            }
        }
        var repository = new Repository();
        var memory = new UserMemoryImpl(repository);
        Map<String, String> original = memory.list();
        repository.fail = true;
        assertThrows(IllegalStateException.class, () -> memory.set("language", "English"));
        assertThrows(IllegalStateException.class, () -> memory.remove("language"));
        assertEquals(original, memory.list());
        assertEquals(original, repository.stored);
        repository.fail = false;
        memory.set("language", "English");
        assertEquals("French", original.get("language"));
        assertThrows(UnsupportedOperationException.class, () -> repository.stored.put("x", "y"));
        repository.stored = Map.of("Invalid key", "value");
        assertThrows(IllegalStateException.class, () -> new UserMemoryImpl(repository));
    }

    @Test
    void sharedMemorySerializesConcurrentUpdatesWithoutLosingPreferences() throws Exception {
        Path file = directory.resolve("shared.json");
        var memory = memory(file);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                for (int i = 0; i < 20; i++) memory.set("first" + i, "a");
            });
            var second = executor.submit(() -> {
                for (int i = 0; i < 20; i++) memory.set("second" + i, "b");
            });
            first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            second.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(40, memory.list().size());
        assertEquals(memory.list(), memory(file).list());
    }

    @Test
    void forgettingThroughToolRemovesPreferenceFromFollowingContext() {
        var preferences = memory(directory.resolve("preferences.json"));
        preferences.set("response.style", "short");
        AtomicInteger calls = new AtomicInteger();
        try (var agent = AgentBuilder.builder()
                .userPreferencesMemory(preferences)
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
        assertTrue(memory(directory.resolve("preferences.json")).list().isEmpty());
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
        try (var first = builder.build(); var second = builder.build()) {
            assertEquals(AgentRunState.SUCCEEDED, first.run("Remember that I prefer French").status());
            assertEquals("French", preferences.load().get("response.language"));
            assertTrue(requests.get(1).stream().anyMatch(m -> m.content() != null
                    && m.content().contains("\"response.language\":\"French\"")));
            assertEquals(AgentRunState.SUCCEEDED, second.run("Hello again").status());
        }
        var resumed = requests.get(requests.size() - 1);
        assertFalse(resumed.stream().anyMatch(m -> "Remember that I prefer French".equals(m.content())));
        assertTrue(resumed.stream().anyMatch(m -> m.content() != null && m.content().contains("French")));
    }
}
