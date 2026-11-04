package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.strategy.OpenAICompactStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAICompactStrategyTest {
    @Test
    void sendsPreviousSummaryAndToolMetadataToTheClient() {
        final List<List<LLmApiClient.ChatMessage>> requests = new java.util.ArrayList<>();
        LLmApiClient client = (messages, tools) -> {
            requests.add(messages);
            return new LLmApiClient.ChatResponse(" updated summary ", List.of());
        };
        var strategy = new OpenAICompactStrategy(client);

        String summary = strategy.compact(
                "previous",
                List.of(LLmApiClient.ChatMessage.assistant(
                        null,
                        List.of(new LLmApiClient.ToolCall("id", "search", "{\"q\":\"java\"}"))
                ))
        );

        assertEquals("updated summary", summary);
        assertTrue(requests.getFirst().get(1).content().contains("previous"));
        assertTrue(requests.getFirst().get(1).content().contains("search"));
        assertTrue(requests.getFirst().get(1).content().contains("java"));
    }

    @Test
    void rejectsToolCallEvenWhenAccompaniedByText() {
        var strategy = new OpenAICompactStrategy((messages, tools) ->
                new LLmApiClient.ChatResponse("Searching before summarizing", List.of(
                        new LLmApiClient.ToolCall("call-1", "search", "{}"))));
        assertThrows(IllegalStateException.class, () -> strategy.compact(
                "previous summary", List.of(LLmApiClient.ChatMessage.user("important fact"))));
    }

    @Test
    void acceptsFinalSummaryWithNullToolCalls() {
        var strategy = new OpenAICompactStrategy((messages, tools) ->
                new LLmApiClient.ChatResponse(" final summary ", null));
        assertEquals("final summary", strategy.compact("previous",
                List.of(LLmApiClient.ChatMessage.user("important fact"))));
    }

    @Test
    void rejectsEmptyMessages() {
        var strategy = new OpenAICompactStrategy((messages, tools) ->
                new LLmApiClient.ChatResponse("summary", List.of()));

        assertThrows(IllegalArgumentException.class,
                () -> strategy.compact("previous", List.of()));
    }

    @Test
    void rejectsEmptyClientSummary() {
        var strategy = new OpenAICompactStrategy((messages, tools) ->
                new LLmApiClient.ChatResponse("", List.of()));

        assertThrows(IllegalStateException.class, () -> strategy.compact(
                "previous", List.of(LLmApiClient.ChatMessage.user("message"))));
    }
}
