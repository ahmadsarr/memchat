package org.iaproject.memchat.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionParserTest {
    private final ActionParser parser = new ActionParser();

    @Test
    void parsesThoughtToolNameAndArguments() {
        var action = parser.parse("Thought: use memory\n<tool_call>{\"name\":\"search\",\"arguments\":{\"query\":\"java\"}}</tool_call>");

        assertNotNull(action);
        assertEquals("use memory", action.thought());
        assertEquals("search", action.name());
        assertEquals("java", action.args().path("query").asText());
    }

    @Test
    void skipsInvalidToolCallAndFindsNextValidOne() {
        var action = parser.parse("<tool_call>invalid</tool_call>\n"
                + "<tool_call>{\"name\":\"get\",\"arguments\":{\"id\":\"1\"}}</tool_call>");

        assertEquals("get", action.name());
    }

    @Test
    void reportsToolCallPresence() {
        assertTrue(parser.hasToolCall("<tool_call>{}</tool_call>"));
        assertFalse(parser.hasToolCall("plain response"));
    }
}
