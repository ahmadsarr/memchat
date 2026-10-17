package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.impl.WorkingMemoryImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkingMemoryImplTest {
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
