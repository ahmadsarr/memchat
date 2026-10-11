package org.iaproject.memchat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.iaproject.memchat.tools.ToolParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    public ChatResponse complete(List<ChatMessage> chatMessages, List<ToolParser.ToolDescription> tools) {
        Map<String, String> headers = apiKey == null || apiKey.isBlank() ? Map.of("Content-Type", "application/json")
                : Map.of("Content-Type", "application/json", "Authorization", "Bearer " + apiKey);
        ObjectNode body = createApiBodyRequest(chatMessages,tools);
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(this.baseUrl + "/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = this.httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (!isSuccessfull(response)) {
                throw new RuntimeException("Erreur vLLM [" + response.statusCode() + "] : " + response.body());
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode message = root.path("choices")
                    .path(0)
                    .path("message");

            String content = message.path("content").asText("");

            List<ToolCall> toolCalls = new ArrayList<>();

            JsonNode callsNode = message.path("tool_calls");

            if (callsNode.isArray()) {
                for (JsonNode call : callsNode) {
                    JsonNode function = call.path("function");

                    toolCalls.add(new ToolCall(
                            call.path("id").asText(),
                            function.path("name").asText(),
                            function.path("arguments").asText()
                    ));
                }
            }

            return new ChatResponse(content, toolCalls);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> boolean isSuccessfull(HttpResponse<T> response) {
        return (response.statusCode() >= 200 && response.statusCode() < 300);
    }

    private ObjectNode createApiBodyRequest(List<ChatMessage> chatMessages, List<ToolParser.ToolDescription> tools) {
        ObjectNode request = this.mapper.createObjectNode();
        request.put("model", this.model);
        request.put("temperature", this.temperature);
        ArrayNode messagesBody = request.putArray("messages");
        for (ChatMessage chatMessage : chatMessages) {
            ObjectNode msgBody = messagesBody.addObject();
            msgBody.put("role", chatMessage.role().name().toLowerCase(Locale.ROOT));
            if (chatMessage.content() == null) {
                msgBody.putNull("content");
            } else {
                msgBody.put("content", chatMessage.content());
            }

            if (chatMessage.toolCallId() != null) {
                msgBody.put("tool_call_id", chatMessage.toolCallId());
            }

            if (chatMessage.toolCalls() != null && !chatMessage.toolCalls().isEmpty()) {
                ArrayNode toolCalls = msgBody.putArray("tool_calls");
                for (ToolCall toolCall : chatMessage.toolCalls()) {
                    ObjectNode call = toolCalls.addObject();
                    call.put("id", toolCall.id());
                    call.put("type", "function");
                    ObjectNode function = call.putObject("function");
                    function.put("name", toolCall.name());
                    function.put("arguments", toolCall.arguments());
                }
            }
        }
        ArrayNode result = mapper.createArrayNode();

        for (var tool : tools) {
            ObjectNode toolNode = result.addObject();
            toolNode.put("type", "function");

            ObjectNode function = toolNode.putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());

            ObjectNode parameters = function.putObject("parameters");
            parameters.put("type", "object");

            ObjectNode properties = parameters.putObject("properties");
            ArrayNode required = parameters.putArray("required");

            for (var entry : tool.params().entrySet()) {
                String name = entry.getKey();
                var param = entry.getValue();

                ObjectNode property = properties.putObject(name);
                property.put("type", param.type());
                property.put("description", param.description());

                if (param.required()) {
                    required.add(name);
                }
            }
        }
        request.set("tools", result);
        return request;
    }
}
