package org.iaproject.memchat.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ActionDispatcher {
    public record Action(String thought, String name, Map<String, String> args) {
    }

    private static final ObjectMapper mapper = new ObjectMapper();

    public String dispatch(String response) {
        String thought = extractThought(response);
        JsonNode jsonNode = extractToolCalls()
        return "";
    }

    private static final String OPEN_TAG = "<tool_call>";
    private static final String CLOSE_TAG = "</tool_call>";

    private JsonNode extractToolCalls(String response) {
        int searchFrom = 0;

        while (true) {
            int tag = response.indexOf(OPEN_TAG, searchFrom);
            if (tag < 0) {
                break;
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
                return mapper.readTree(candidate);

            } catch (JsonProcessingException ignored) {
            }

            searchFrom = nextTag >= 0
                    ? nextTag
                    : end + (closeTag >= 0 ? CLOSE_TAG.length() : 0);
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
