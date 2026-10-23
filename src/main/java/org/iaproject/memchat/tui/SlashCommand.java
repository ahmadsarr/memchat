package org.iaproject.memchat.tui;

import java.util.Arrays;

/** Parser for commands handled locally by the TUI. */
public record SlashCommand(Kind kind, String argument) {
    public enum Kind { HELP, CLEAR, MODEL, QUIT, UNKNOWN, NONE }

    public SlashCommand {
        argument = argument == null ? "" : argument;
    }

    public static SlashCommand parse(String input) {
        if (input == null || !input.trim().startsWith("/")) {
            return new SlashCommand(Kind.NONE, input == null ? "" : input);
        }
        String[] parts = input.trim().substring(1).split("\\s+", 2);
        Kind kind = Arrays.stream(Kind.values())
                .filter(value -> value != Kind.NONE && value.name().equalsIgnoreCase(parts[0]))
                .findFirst().orElse(Kind.UNKNOWN);
        String argument = parts.length == 2 ? parts[1] : kind == Kind.UNKNOWN ? parts[0] : "";
        return new SlashCommand(kind, argument);
    }
}
