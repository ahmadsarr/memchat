package org.iaproject.memchat.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class OpenAICompatibleClient implements LLmApiClient {
    private final String baseUrl;
    private final String model;
    private final Double temperature;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAICompatibleClient(String baseUrl, String model, Double temperature, String apiKey, HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    public OpenAICompatibleClient(String baseUrl, String model, String apiKey) {

        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = 0.1;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMinutes(5))
                .build();
    }

    @Override
    public ChatResponse complete(List<ChatMessage> chatMessages) {
        Map<String, String> headers = apiKey == null || apiKey.isBlank() ? Map.of("Content-Type", "application/json")
                : Map.of("Content-Type", "application/json", "Authorization", "Bearer " + apiKey);
        ObjectNode body = createApiBodyRequest(chatMessages);
        System.out.println(body.toPrettyString());
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(this.baseUrl + "/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = this.httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (!isSuccessfull(response)) {
                throw new RuntimeException("Erreur vLLM [" + response.statusCode() + "] : " + response.body());
            }
            return new ChatResponse(mapper.readTree(response.body())
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> boolean isSuccessfull(HttpResponse<T> response) {
        return (response.statusCode() >= 200 && response.statusCode() < 300);
    }

    private ObjectNode createApiBodyRequest(List<ChatMessage> chatMessages) {
        ObjectNode request = this.mapper.createObjectNode();
        request.put("model", this.model);
        request.put("temperature", this.temperature);
        ArrayNode messagesBody = request.putArray("messages");
        for (ChatMessage chatMessage : chatMessages) {
            ObjectNode msgBody = messagesBody.addObject();
            msgBody.put("role", chatMessage.role().toString());
            msgBody.put("content", chatMessage.content());
        }
        return request;
    }
}
