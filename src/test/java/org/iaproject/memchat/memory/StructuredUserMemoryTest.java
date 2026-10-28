package org.iaproject.memchat.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.agent.AgentBuilder;
import org.iaproject.memchat.agent.AgentRunState;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.impl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.iaproject.memchat.memory.UserMemoryEntry.Type.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuredUserMemoryTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private UserMemoryImpl memory() {
        return new UserMemoryImpl(new FileUserMemoryRepository(directory.resolve("user.json")));
    }

    @Test void migratesLegacyWithoutRewritingUntilFirstSuccessfulMutation() throws Exception {
        Path file = directory.resolve("user.json");
        String original = "{\"response.language\":\"French\"}";
        Files.writeString(file, original);
        String timestamp = Files.getLastModifiedTime(file).toInstant().toString();
        var memory = memory();
        var migrated = memory.entries().get("response.language");
        assertEquals(PREFERENCE, migrated.type());
        assertEquals(timestamp, migrated.createdAt());
        assertTrue(migrated.source().contains("unavailable"));
        assertEquals(original, Files.readString(file));
        memory.store("project.memchat", FACT, "Maintains memchat", "User: I maintain memchat");
        var json = mapper.readTree(file.toFile());
        assertEquals(1, json.path("schemaVersion").intValue());
        assertEquals(memory.entries(), memory().entries());
        assertEquals("French", memory().list().get("response.language"));
    }

    @Test void correctionsAndConfirmationPreserveCreationAndRequireExplicitTransition() {
        Path file = directory.resolve("user.json");
        var repository = new FileUserMemoryRepository(file);
        var firstDate = Instant.parse("2026-01-01T00:00:00Z");
        var laterDate = firstDate.plusSeconds(100);
        var first = new UserMemoryImpl(repository, Clock.fixed(firstDate, ZoneOffset.UTC));
        first.store("language.java", HYPOTHESIS, "Enjoys Java", "Observed Java questions; tentative");
        var later = new UserMemoryImpl(repository, Clock.fixed(laterDate, ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () -> later.store("language.java", FACT, "Enjoys Java", "Guess"));
        assertThrows(IllegalArgumentException.class, () -> later.confirm("language.java", HYPOTHESIS, "Guess"));
        later.store("language.java", HYPOTHESIS, "Uses Java at work", "Clarified interpretation, still tentative");
        later.confirm("language.java", FACT, "User: Yes, I use Java at work");
        var confirmed = later.entries().get("language.java");
        assertEquals(FACT, confirmed.type());
        assertEquals("Uses Java at work", confirmed.content());
        assertEquals(firstDate.toString(), confirmed.createdAt());
        assertEquals(laterDate.toString(), confirmed.updatedAt());
        assertTrue(confirmed.source().contains("Yes"));
        assertThrows(IllegalArgumentException.class, () -> later.confirm("language.java", FACT, "Again"));
        assertEquals(confirmed, memory().entries().get("language.java"));
    }

    @Test void searchesPersonalInformationWithProvenanceAndBoundedDeterministicResults() {
        var memory = memory();
        memory.store("project.memchat", FACT, "Develops memchat in Java", "User statement");
        memory.store("interest.java", HYPOTHESIS, "Enjoys Java", "Recurring topic");
        memory.store("response.language", PREFERENCE, "French", "User preference");
        var matches = memory.search("Java", 1);
        assertEquals(1, matches.size());
        assertEquals("interest.java", matches.getFirst().key());
        assertEquals(HYPOTHESIS, matches.getFirst().entry().type());
        assertEquals("Recurring topic", matches.getFirst().entry().source());
        assertEquals(2, memory.search("Java", 20).size());
        assertTrue(memory.search("absentword", 5).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> memory.search("Java", 0));
        assertThrows(IllegalArgumentException.class, () -> memory.search("Java", 21));
        assertThrows(IllegalArgumentException.class, () -> memory.search(" ", 5));
    }

    @Test void compactProfileExcludesFactsAndHypothesesAndBoundsSerializedMetadata() {
        var memory = memory();
        for (int i = 0; i < 7; i++) memory.store("style"+i, PREFERENCE, "x".repeat(500), "source".repeat(80));
        memory.store("secret.project", FACT, "uniquepersonalproject", "User statement");
        memory.store("tentative", HYPOTHESIS, "uncertaininterest", "Observed pattern");
        var profile = memory.profile();
        assertTrue(Utils.json(profile).length() <= UserMemoryImpl.MAX_PROFILE_CHARACTERS);
        assertTrue(profile.size() < memory.list().size(), "omitted preferences remain searchable");
        assertTrue(profile.values().stream().allMatch(e -> e.type() == PREFERENCE));
        assertFalse(Utils.json(profile).contains("uniquepersonalproject"));
        assertEquals(1, memory.search("uniquepersonalproject", 1).size());
        assertThrows(UnsupportedOperationException.class, profile::clear);
    }

    @Test void rejectsCorruptSchemaTypesAndDatesWithoutOverwritingFile() throws Exception {
        Path file = directory.resolve("user.json");
        for (String bad : List.of("null", "[]", "{\"schemaVersion\":2,\"entries\":{}}",
                "{\"schemaVersion\":1,\"entries\":{\"bad\":null}}",
                "{\"schemaVersion\":1,\"entries\":{\"bad\":{\"type\":\"UNKNOWN\"}}}")) {
            Files.writeString(file, bad);
            assertThrows(IllegalStateException.class, this::memory);
            assertEquals(bad, Files.readString(file));
        }
        assertThrows(RuntimeException.class, () -> new UserMemoryEntry(FACT, "fact", "source", "bad-date", "bad-date"));
        assertThrows(IllegalArgumentException.class, () -> new UserMemoryEntry(FACT, "fact", "source", "2026-02-01T00:00:00Z", "2026-01-01T00:00:00Z"));
    }

    @Test void allMutationsKeepPreviousSnapshotWhenRepositorySaveFails() {
        class Repository implements UserMemoryRepository {
            Map<String, UserMemoryEntry> committed = Map.of();
            boolean fail;
            public Map<String, UserMemoryEntry> load() { return committed; }
            public void save(Map<String, UserMemoryEntry> entries) {
                if (fail) throw new IllegalStateException("Disk full");
                committed = entries;
            }
        }
        var repository = new Repository();
        var memory = new UserMemoryImpl(repository);
        memory.store("job", HYPOTHESIS, "Works with Java", "Observed topic");
        var original = memory.entries();
        repository.fail = true;
        assertThrows(IllegalStateException.class, () -> memory.store("job", HYPOTHESIS, "Works with Go", "New evidence"));
        assertThrows(IllegalStateException.class, () -> memory.confirm("job", FACT, "Confirmed"));
        assertThrows(IllegalStateException.class, () -> memory.remove("job"));
        assertEquals(original, memory.entries());
        assertEquals(original, repository.committed);
    }

    @Test void globalEntryBudgetProtectsStorageIndependentlyOfCompactProfile() {
        class Repository implements UserMemoryRepository {
            Map<String, UserMemoryEntry> data = Map.of();
            public Map<String, UserMemoryEntry> load() { return data; }
            public void save(Map<String, UserMemoryEntry> entries) { data = entries; }
        }
        var repository = new Repository();
        var memory = new UserMemoryImpl(repository);
        for (int i = 0; i < 1000; i++) memory.store("fact"+i, FACT, "x", "s");
        assertThrows(IllegalArgumentException.class, () -> memory.store("overflow", FACT, "x", "s"));
        assertEquals(1000, memory.entries().size());
        assertTrue(memory.profile().isEmpty());
        var oversized = new LinkedHashMap<String, UserMemoryEntry>();
        String date = "2026-01-01T00:00:00Z";
        for (int i = 0; i < 300; i++) oversized.put("large"+i,
                new UserMemoryEntry(FACT, "x".repeat(500), "s".repeat(500), date, date));
        repository.data = oversized;
        assertThrows(IllegalStateException.class, () -> new UserMemoryImpl(repository));
    }

    @Test void toolsRetrieveFactsOnDemandAndForgetWithoutErasingRecall() {
        var memory = memory();
        memory.store("project", FACT, "uniquepersonalproject", "User: I maintain this project");
        memory.set("response.language", "French");
        var requests = new ArrayList<List<LLmApiClient.ChatMessage>>();
        AtomicInteger count = new AtomicInteger();
        Path recall = directory.resolve("recall.json");
        try (var agent = AgentBuilder.builder().userMemory(memory)
                .recallStorageRepository(new FileRecallStorageRepository(recall))
                .archivalMemoryRepository(new FileArchivalMemoryRepository(directory.resolve("archive.json")))
                .client((messages, tools) -> {
                    requests.add(List.copyOf(messages));
                    return switch (count.getAndIncrement()) {
                        case 0 -> new LLmApiClient.ChatResponse(null, List.of(new LLmApiClient.ToolCall("search", "userMemorySearch", "{\"query\":\"uniquepersonalproject\",\"limit\":5}")));
                        case 1 -> new LLmApiClient.ChatResponse(null, List.of(new LLmApiClient.ToolCall("forget", "userMemoryForget", "{\"key\":\"project\"}")));
                        default -> new LLmApiClient.ChatResponse("Done", List.of());
                    };
                }).build()) {
            assertEquals(AgentRunState.SUCCEEDED, agent.run("Find and forget my project").status());
        }
        assertTrue(requests.getFirst().stream().filter(m -> m.role() == LLmApiClient.Role.SYSTEM)
                .noneMatch(m -> m.content() != null && m.content().contains("uniquepersonalproject")));
        assertTrue(requests.get(1).stream().anyMatch(m -> m.role() == LLmApiClient.Role.TOOL
                && m.content().contains("uniquepersonalproject") && m.content().contains("FACT")));
        assertFalse(memory.entries().containsKey("project"));
        assertFalse(new FileRecallStorageRepository(recall).search("uniquepersonalproject", 5).isEmpty());
    }

    @Test void toolEnumBindingConfirmationAndInvalidTransitionsUseRealDispatcher() {
        var memory = memory();
        var executor = new org.iaproject.memchat.action.ActionExecutor(
                org.iaproject.memchat.tools.ToolParser.parse(List.of(new org.iaproject.memchat.tools.UserMemoryTools(memory))));
        assertTrue(executor.execute("userMemoryStore", "{\"key\":\"job\",\"type\":\"HYPOTHESIS\",\"content\":\"Uses Java\",\"source\":\"Observed questions\"}").success());
        assertFalse(executor.execute("userMemoryConfirm", "{\"key\":\"job\",\"confirmedType\":\"HYPOTHESIS\",\"source\":\"No confirmation\"}").success());
        assertTrue(executor.execute("userMemoryConfirm", "{\"key\":\"job\",\"confirmedType\":\"FACT\",\"source\":\"User: yes\"}").success());
        assertEquals(FACT, memory.entries().get("job").type());
        assertFalse(executor.execute("userMemoryStore", "{\"key\":\"bad\",\"type\":\"OTHER\",\"content\":\"x\",\"source\":\"s\"}").success());
        assertFalse(memory.entries().containsKey("bad"));
    }
}
