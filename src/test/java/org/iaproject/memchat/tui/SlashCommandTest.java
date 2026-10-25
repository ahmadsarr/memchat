package org.iaproject.memchat.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlashCommandTest {
    @Test
    void parsesSupportedCommandsAndArguments() {
        assertEquals(SlashCommand.Kind.HELP, SlashCommand.parse("/help").kind());
        assertEquals(SlashCommand.Kind.CLEAR, SlashCommand.parse("/clear").kind());
        assertEquals("new-model", SlashCommand.parse("/model new-model").argument());
        assertEquals(SlashCommand.Kind.QUIT, SlashCommand.parse(" /quit ").kind());
        assertEquals(SlashCommand.Kind.NONE, SlashCommand.parse("question").kind());
        assertEquals(SlashCommand.Kind.UNKNOWN, SlashCommand.parse("/unknown").kind());
    }
}
