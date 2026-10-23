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
import java.util.logging.Level;
import java.util.logging.Logger;

public class OpenAICompatibleClient implements LLmApiClient {
    private static final Logger LOGGER = Logger.getLogger(OpenAICompatibleClient.class.getName());
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

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
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        headers.forEach(request::header);
        long startedAt = System.nanoTime();
        LOGGER.info("LLM request started (model=" + model
                + ", messages=" + chatMessages.size()
                + ", tools=" + tools.size() + ")");
        try {
            HttpResponse<String> response = this.httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            long durationMillis = elapsedMillis(startedAt);
            if (!isSuccessfull(response)) {
                LOGGER.warning("LLM request failed (status=" + response.statusCode()
                        + ", durationMs=" + durationMillis + ")");
                throw new RuntimeException("Erreur vLLM [" + response.statusCode() + "] : " + response.body());
            }
            ChatResponse completion = parseResponse(response.body());

            LOGGER.info("LLM request completed (status=" + response.statusCode()
                    + ", durationMs=" + durationMillis
                    + ", toolCalls=" + completion.toolCalls().size() + ")");
            return completion;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM request interrupted", e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "LLM request failed (durationMs=" + elapsedMillis(startedAt)
                    + ", error=" + e.getClass().getSimpleName() + ")", e);
            throw new RuntimeException(e);
        }
    }

    private ChatResponse parseResponse(String body) throws IOException {
        JsonNode root = mapper.readTree(body);
        if (root == null || !root.path("choices").isArray() || root.path("choices").isEmpty()) {
            throw invalidResponse("missing completion choices");
        }
        JsonNode choice = root.path("choices").get(0);
        String finishReason = choice.path("finish_reason").asText("");
        if ("length".equals(finishReason) || "content_filter".equals(finishReason)) {
            throw invalidResponse("incomplete completion (" + finishReason + ")");
        }
        JsonNode message = choice.path("message");
        if (!message.isObject()) {
            throw invalidResponse("missing assistant message");
        }
        JsonNode contentNode = message.path("content");
        if (!contentNode.isMissingNode() && !contentNode.isNull() && !contentNode.isTextual()) {
            throw invalidResponse("content must be text or null");
        }
        String content = contentNode.asText("");

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode callsNode = message.path("tool_calls");
        if (!callsNode.isMissingNode() && !callsNode.isNull() && !callsNode.isArray()) {
            throw invalidResponse("tool_calls must be an array");
        }
        for (JsonNode call : callsNode) {
            JsonNode function = call.path("function");
            if (!call.path("id").isTextual() || call.path("id").asText().isBlank()
                    || !function.path("name").isTextual() || function.path("name").asText().isBlank()
                    || !function.path("arguments").isTextual()) {
                throw invalidResponse("tool calls require an id, name and string arguments");
            }
            toolCalls.add(new ToolCall(call.path("id").asText(),
                    function.path("name").asText(), function.path("arguments").asText()));
        }

        if ("tool_calls".equals(finishReason) && toolCalls.isEmpty()) {
            throw invalidResponse("finish_reason=tool_calls requires a tool call");
        }
        // Reasoning is not a final answer or a replacement memory summary.
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw invalidResponse("incomplete completion: no final answer or tool call");
        }
        return new ChatResponse(content, List.copyOf(toolCalls));
    }

    private static IllegalStateException invalidResponse(String reason) {
        return new IllegalStateException("Invalid LLM response: " + reason);
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private <T> boolean isSuccessfull(HttpResponse<T> response) {
        return (response.statusCode() >= 200 && response.statusCode() < 300);
    }

    private ObjectNode createApiBodyRequest(List<ChatMessage> chatMessages, List<ToolParser.ToolDescription> tools) {
        ObjectNode request = this.mapper.createObjectNode();
        request.put("model", this.model);
        request.put("temperature", this.temperature);
        request.put("parallel_tool_calls", false);
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
