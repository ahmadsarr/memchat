package org.iaproject.memchat.agent;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.ArchivalMemory;
import org.iaproject.memchat.memory.Memory;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.memory.RecallStorage;
import org.iaproject.memchat.tools.Param;
import org.iaproject.memchat.tools.Tool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopIntegrationTest {
    public static class EchoTool {
        @Tool(description = "Echo the provided value")
        public String echo(@Param(description = "Value") String value) {
            return value;
        }
    }

    @Test
    void runsToolThenLetsTheModelProduceTheFinalAnswer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LLmApiClient client = (messages, tools) -> {
            if (calls.getAndIncrement() == 0) {
                return new LLmApiClient.ChatResponse(
                        "",
                        List.of(new LLmApiClient.ToolCall(
                                "call-1", "echo", "{\"value\":\"tool-result\"}"
                        ))
                );
            }
            return new LLmApiClient.ChatResponse("final answer", List.of());
        };

        MemoryManager memoryManager = new MemoryManager(
                new InMemoryRecallStorage(),
                new InMemoryArchivalMemory(),
                (summary, messages) -> summary + " compacted"
        );
        AgentLoop loop = new AgentLoop(3, client, memoryManager, new EchoTool());
        List<AgentEvent> events = new ArrayList<>();
        loop.subscribe(events::add);

        loop.run("hello");

        assertEquals(2, calls.get());
        assertTrue(events.stream().anyMatch(event ->
                event.type() == AgentEvent.Type.TOOL_EXECUTION));
        assertTrue(events.stream().anyMatch(event ->
                event.type() == AgentEvent.Type.AGENT_MESSAGE
                        && event.message().equals("final answer")));
        loop.close();
    }

    private static final class InMemoryRecallStorage implements RecallStorage {
        public String add(LLmApiClient.ChatMessage message) { return "id"; }
        public List<Memory.RecallSearchResult> search(String query, int limit) { return List.of(); }
    }

    private static final class InMemoryArchivalMemory implements ArchivalMemory {
        public String insert(String content) { return "id"; }
        public List<Memory.MemorySearchResult> search(String query, int limit) { return List.of(); }
        public void update(String id, String content) { }
        public void delete(String id) { }
        public String get(String id) { return null; }
    }
}
