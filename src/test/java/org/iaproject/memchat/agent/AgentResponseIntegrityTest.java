package org.iaproject.memchat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.action.ActionExecutor;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.memory.impl.ArchivalMemoryImpl;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;
import org.iaproject.memchat.memory.impl.RecallStorageImpl;
import org.iaproject.memchat.tools.Tool;
import org.iaproject.memchat.tools.ToolParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AgentResponseIntegrityTest {
    @TempDir Path directory;

    static Stream<LLmApiClient.ChatResponse> invalidResponses() {
        return Stream.of(
                null,
                new LLmApiClient.ChatResponse(null, List.of()),
                new LLmApiClient.ChatResponse("", null),
                new LLmApiClient.ChatResponse("   ", List.of()),
                new LLmApiClient.ChatResponse(null, java.util.Collections.singletonList(null)),
                response(new LLmApiClient.ToolCall(null, "perform", "{}")),
                response(new LLmApiClient.ToolCall("   ", "perform", "{}")),
                response(new LLmApiClient.ToolCall("one", null, "{}")),
                response(new LLmApiClient.ToolCall("one", "   ", "{}")),
                new LLmApiClient.ChatResponse(null, List.of(
                        new LLmApiClient.ToolCall("one", "perform", "{}"),
                        new LLmApiClient.ToolCall("two", "perform", "{}"))));
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsInvalidResponsesBeforeTouchingMemoryToolsOrEvents(LLmApiClient.ChatResponse response) {
        var memory = memory();
        var tool = new CountingTool();
        var processor = new AgentProcessor(memory,
                new ActionExecutor(ToolParser.parse(List.of(tool))), new ObjectMapper());
        List<AgentEvent> events = new ArrayList<>();
        processor.setEventPublisher(events::add);
        var before = memory.context();

        assertThrows(IllegalStateException.class, () -> processor.process(response));
        assertEquals(before, memory.context());
        assertTrue(memory.searchRecall("perform", 10).isEmpty());
        assertEquals(0, tool.calls.get());
        assertTrue(events.isEmpty());
    }

    @Test
    void anUnknownButWellFormedToolStillProducesARecoverableToolResult() throws Exception {
        var memory = memory();
        var processor = new AgentProcessor(memory,
                new ActionExecutor(List.of()), new ObjectMapper());
        assertEquals(AgentDecision.CONTINUE, processor.process(
                response(new LLmApiClient.ToolCall("one", "unknown", "{}"))));
        var result = memory.context().getLast();
        assertEquals(LLmApiClient.Role.TOOL, result.role());
        assertEquals("one", result.toolCallId());
        assertFalse(new ObjectMapper().readTree(result.content()).path("success").asBoolean());
    }

    @Test
    void storedToolCallsAreAnImmutableSnapshotOfTheClientList() throws Exception {
        var calls = new ArrayList<>(List.of(new LLmApiClient.ToolCall("one", "perform", "{}")));
        var message = LLmApiClient.ChatMessage.assistant(null, calls);
        calls.clear();
        var memory = memory();
        memory.addMessage(message);

        var stored = memory.context().getLast();
        assertEquals(1, stored.toolCalls().size());
        assertEquals("one", stored.toolCalls().getFirst().id());
        assertThrows(UnsupportedOperationException.class, () -> stored.toolCalls().clear());
    }

    private static LLmApiClient.ChatResponse response(LLmApiClient.ToolCall call) {
        return new LLmApiClient.ChatResponse(null, List.of(call));
    }

    public static class CountingTool {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(description = "Side effect")
        public String perform() { calls.incrementAndGet(); return "done"; }
    }

    private MemoryManager memory() {
        return new MemoryManager(
                new RecallStorageImpl(new FileRecallStorageRepository(directory.resolve("recall.json"))),
                new ArchivalMemoryImpl(new FileArchivalMemoryRepository(directory.resolve("archive.json"))),
                (summary, messages) -> "summary");
    }
}
