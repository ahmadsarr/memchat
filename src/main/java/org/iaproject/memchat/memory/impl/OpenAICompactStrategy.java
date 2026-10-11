package org.iaproject.memchat.memory.impl;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.CompactStrategy;

import java.util.ArrayList;
import java.util.List;

public class OpenAICompactStrategy implements CompactStrategy {
    private static final String COMPACTION_PROMPT = """
    You are maintaining the memory of a conversational agent.

    Merge the previous summary with the new conversation messages
    into one updated summary.

    Preserve:
    - User preferences and goals
    - Important facts and decisions
    - Technical context and architectural choices
    - Unresolved tasks and next steps
    - Important tool results

    Remove repetition, greetings and irrelevant details.

    The new summary must replace the previous summary completely.
    Do not lose important information from the previous summary.

    Return only the updated summary in plain text.
    """;

    private final LLmApiClient client;

    public OpenAICompactStrategy(LLmApiClient client) {
        this.client = client;
    }

    @Override
    public String compact(String previousSummary, List<LLmApiClient.ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("No messages to compact");
        }

        List<LLmApiClient.ChatMessage> request = new ArrayList<>();
        request.add(LLmApiClient.ChatMessage.system(COMPACTION_PROMPT));
        request.add(LLmApiClient.ChatMessage.user(formatInput(previousSummary, messages)));

        LLmApiClient.ChatResponse response = client.complete(request, List.of());
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new IllegalStateException("Compaction returned an empty summary");
        }
        return response.content().trim();
    }

    private String formatInput(
            String previousSummary,
            List<LLmApiClient.ChatMessage> messages
    ) {
        StringBuilder input = new StringBuilder("Previous summary:\n");
        input.append(previousSummary == null ? "(none)" : previousSummary);
        input.append("\n\nMessages to merge:\n");

        for (LLmApiClient.ChatMessage message : messages) {
            input.append(message.role()).append(": ")
                    .append(message.content() == null ? "" : message.content())
                    .append('\n');

            if (message.toolCallId() != null) {
                input.append("  tool_call_id: ")
                        .append(message.toolCallId())
                        .append('\n');
            }

            if (message.toolCalls() != null) {
                for (LLmApiClient.ToolCall toolCall : message.toolCalls()) {
                    input.append("  tool_call: id=")
                            .append(toolCall.id())
                            .append(", name=")
                            .append(toolCall.name())
                            .append(", arguments=")
                            .append(toolCall.arguments())
                            .append('\n');
                }
            }
        }
        return input.toString();
    }
}
