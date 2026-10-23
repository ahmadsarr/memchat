package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.impl.WorkingMemoryImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkingMemoryImplTest {
    @Test
    void selectsAnEmptyOrShortContextWithoutFailing() {
        var memory = new WorkingMemoryImpl();
        assertEquals(List.of(), memory.selectEvictionCandidates());
        var message = LLmApiClient.ChatMessage.user("long instruction");
        memory.addMessage(message);
        assertEquals(List.of(message), memory.selectEvictionCandidates());
    }

    @Test
    void candidatesAreAnImmutableSnapshot() {
        var memory = new WorkingMemoryImpl();
        for (int i = 0; i < 6; i++) {
            memory.addMessage(LLmApiClient.ChatMessage.user("message " + i));
        }
        var candidates = memory.selectEvictionCandidates();
        assertEquals(5, candidates.size());
        assertThrows(UnsupportedOperationException.class, candidates::clear);
        memory.addMessage(LLmApiClient.ChatMessage.user("later"));
        memory.applyCompact(candidates, "summary");
        assertEquals(5, candidates.size());
        assertEquals(2, memory.getContext().size());
    }

    @Test
    void keepsToolCallsAndAllTheirResultsInTheSameBatch() {
        var memory = new WorkingMemoryImpl();
        for (int i = 0; i < 4; i++) {
            memory.addMessage(LLmApiClient.ChatMessage.user("message " + i));
        }
        memory.addMessage(LLmApiClient.ChatMessage.assistant(null, List.of(
                new LLmApiClient.ToolCall("one", "search", "{}"),
                new LLmApiClient.ToolCall("two", "search", "{}"))));
        memory.addMessage(LLmApiClient.ChatMessage.tool("two", "second result"));
        memory.addMessage(LLmApiClient.ChatMessage.tool("one", "first result"));
        var remaining = LLmApiClient.ChatMessage.assistant("answer");
        memory.addMessage(remaining);

        var candidates = memory.selectEvictionCandidates();
        assertEquals(7, candidates.size());
        memory.applyCompact(candidates, "summary");
        assertEquals(List.of(remaining), memory.getContext());
    }

    @Test
    void leavesAnUnfinishedToolExchangeInContext() {
        var memory = new WorkingMemoryImpl();
        var first = LLmApiClient.ChatMessage.user("question");
        memory.addMessage(first);
        var call = LLmApiClient.ChatMessage.assistant(null, List.of(
                new LLmApiClient.ToolCall("pending", "search", "{}")));
        memory.addMessage(call);

        assertEquals(List.of(first), memory.selectEvictionCandidates());
        memory.applyCompact(List.of(first), "summary");
        assertEquals(List.of(), memory.selectEvictionCandidates());
        assertEquals(List.of(call), memory.getContext());
    }

    @Test
    void storesMessagesAndReturnsAnImmutableSnapshot() {
        var memory = new WorkingMemoryImpl();
        memory.addMessage(LLmApiClient.ChatMessage.user("hello"));

        var context = memory.getContext();

        assertEquals(1, context.size());
        assertThrows(UnsupportedOperationException.class,
                () -> context.add(LLmApiClient.ChatMessage.user("no")));
    }

    @Test
    void appliesCompactionOnlyToTheCurrentPrefix() {
        var memory = new WorkingMemoryImpl();
        var first = LLmApiClient.ChatMessage.user("first");
        var second = LLmApiClient.ChatMessage.assistant("second");
        memory.addMessage(first);
        memory.addMessage(second);

        memory.applyCompact(List.of(first), "summary");

        assertEquals("summary", memory.getSummary());
        assertEquals(List.of(second), memory.getContext());
    }

    @Test
    void rejectsStaleCompactionCandidates() {
        var memory = new WorkingMemoryImpl();
        memory.addMessage(LLmApiClient.ChatMessage.user("current"));

        assertThrows(IllegalStateException.class, () -> memory.applyCompact(
                List.of(LLmApiClient.ChatMessage.user("stale")),
                "summary"
        ));
    }

    @Test
    void rejectsEmptyCoreValues() {
        var memory = new WorkingMemoryImpl();

        assertThrows(IllegalArgumentException.class,
                () -> memory.appendCore("", "value"));
        assertThrows(IllegalArgumentException.class,
                () -> memory.replaceCore("section", ""));
    }
}
