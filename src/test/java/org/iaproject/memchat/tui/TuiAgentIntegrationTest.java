package org.iaproject.memchat.tui;

import org.iaproject.memchat.agent.AgentEvent;
import org.iaproject.memchat.agent.AgentLoop;
import org.iaproject.memchat.agent.AgentRunState;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.ArchivalMemory;
import org.iaproject.memchat.memory.Memory;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.memory.RecallStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Agent-level event sequence consumed by the same reducer as the TUI. */
class TuiAgentIntegrationTest {
    @Test
    void mockAgentEventsProduceATuiTranscript() {
        LLmApiClient client = (messages, tools) -> new LLmApiClient.ChatResponse("answer", List.of());
        MemoryManager memory = new MemoryManager(new Recall(), new Archive(), (summary, messages) -> summary);
        List<AgentEvent> events = new ArrayList<>();
        try (AgentLoop loop = new AgentLoop(1, client, memory)) {
            loop.subscribe(events::add);
            assertEquals(AgentRunState.SUCCEEDED, loop.run("hello").status());
        }

        TuiState state = TuiReducer.userMessage(TuiState.initial("mock"), "hello");
        for (AgentEvent event : events) state = TuiReducer.reduce(state, event);
        assertEquals(List.of("hello", "answer"), state.transcript().stream()
                .map(TuiState.TranscriptEntry::text).toList());
        assertNull(state.usage());
        assertEquals(TuiState.Status.IDLE, state.status());
    }

    private static final class Recall implements RecallStorage {
        public String add(LLmApiClient.ChatMessage message) { return "id"; }
        public List<Memory.RecallSearchResult> search(String query, int limit) { return List.of(); }
    }

    private static final class Archive implements ArchivalMemory {
        public String insert(String content) { return "id"; }
        public List<Memory.MemorySearchResult> search(String query, int limit) { return List.of(); }
        public void update(String id, String content) { }
        public void delete(String id) { }
        public String get(String id) { return null; }
    }
}
