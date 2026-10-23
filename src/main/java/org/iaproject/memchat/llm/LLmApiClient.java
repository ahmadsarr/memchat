package org.iaproject.memchat.llm;

import lombok.Builder;
import org.iaproject.memchat.tools.ToolParser;

import java.net.http.HttpClient;
import java.util.List;

public interface LLmApiClient {
    public enum Role {SYSTEM, USER, ASSISTANT,TOOL}

    public record ChatMessage(
            Role role,
            String content,
            List<ToolCall> toolCalls,
            String toolCallId
    ) {
        public ChatMessage {
            toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage(Role.USER, content, null, null);
        }

        public static ChatMessage system(String content) {
            return new ChatMessage(Role.SYSTEM, content, null, null);
        }

        public static ChatMessage assistant(String content) {
            return assistant(content, null);
        }

        public static ChatMessage assistant(String content,  List<ToolCall> toolCalls) {
            return new ChatMessage(Role.ASSISTANT, content, toolCalls, null);
        }

        public static ChatMessage tool(String id, String result) {
            return new ChatMessage(Role.TOOL , result, null, id);
        }
    }

    record ToolCall(
            String id,
            String name,
            String arguments
    ) {}

    public record ChatResponse(String content, List<ToolCall> toolCalls) {
    }

    @Builder
    public record OpenApiArg(String baseUrl, String model, Double temperature, String apiKey, HttpClient httpClient) {
    }


    ChatResponse complete(List<ChatMessage> chatMessages, List<ToolParser.ToolDescription> tools);
}
