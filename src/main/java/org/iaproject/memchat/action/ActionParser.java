package org.iaproject.memchat.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ActionParser {
    public record Action(String thought, String name, JsonNode args) {
    }

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    public Action parse(String response) {
        String thought = extractThought(response);
        JsonNode jsonNode = extractToolCalls(response);
        if (jsonNode == null)
            return null;
        if (jsonNode.isNull())
            throw new RuntimeException("Invalid action JSON or argument type.Generate the tool call again using valid JSON.");
        String name = jsonNode.path("name").asText();
        JsonNode paramsNode = jsonNode.path("arguments");

        return new Action(thought, name, paramsNode);
    }
    public boolean hasToolCall(String response) {
        return response != null && response.contains("<tool_call>");
    }

    private static final String OPEN_TAG = "<tool_call>";
    private static final String CLOSE_TAG = "</tool_call>";

    private JsonNode extractToolCalls(String response) {
        int searchFrom = 0;
        if (response == null || response.isBlank()) {
            return null;
        }
        while (true) {
            int tag = response.indexOf(OPEN_TAG, searchFrom);

            if (tag < 0) {
                return null;
            }

            int start = tag + OPEN_TAG.length();
            int nextTag = response.indexOf(OPEN_TAG, start);
            int closeTag = response.indexOf(CLOSE_TAG, start);

            int end = response.length();

            if (nextTag >= 0) {
                end = Math.min(end, nextTag);
            }
            if (closeTag >= 0) {
                end = Math.min(end, closeTag);
            }

            String candidate = response.substring(start, end).trim();
            try {
                JsonNode node = mapper.readTree(candidate);

                if (node.isObject()
                        && node.path("name").isTextual()
                        && node.path("arguments").isObject()) {
                    return node;
                }

            } catch (JsonProcessingException ignored) {
            }

            if (nextTag < 0) {
                break;
            }

            searchFrom = nextTag;
        }

        return mapper.nullNode();
    }

    private String extractThought(String response) {
        if (response == null || response.isBlank()) {
            return "";
        }
        int start = response.indexOf("Thought:");
        if (start < 0) {
            return "";
        }

        start += "Thought:".length();

        int end = response.indexOf("<tool_call>", start);
        if (end < 0) {
            end = response.length();
        }
        return response.substring(start, end).trim();
    }

}
