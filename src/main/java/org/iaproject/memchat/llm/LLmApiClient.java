package org.iaproject.memchat.llm;

import lombok.Builder;

import java.net.http.HttpClient;
import java.util.List;

public interface LLmApiClient {
    public enum Role {SYSTEM, USER, ASSISTANT}

    public record ChatMessage(Role role, String content) {
    }

    public record ChatResponse(String reponse) {
    }

    @Builder
    public record OpenApiArg(String baseUrl, String model, Double temperature, String apiKey, HttpClient httpClient) {
    }


    public abstract ChatResponse complete(List<ChatMessage> chatMessages);
}
