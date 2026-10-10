package org.iaproject.memchat.tools;

import java.util.Objects;
import java.util.Scanner;

public class UserInteractionTools {

    private final Scanner scanner;

    public UserInteractionTools(Scanner scanner) {
        this.scanner = Objects.requireNonNull(scanner);
    }

    public UserInteractionTools() {
        this.scanner = new Scanner(System.in);
    }

    @Tool(description = """
            Ask the user for clarification when information is missing
            or the request is ambiguous.
            Use only when the task cannot continue without user input.
            """)
    public UserInteractionResult askUser(
            @Param(description = "Clear question to ask the user")
            String question
    ) {
        System.out.println();
        System.out.println("Agent: " + question);
        System.out.print("You: ");

        return new UserInteractionResult(
                InteractionType.ASK_USER,
                scanner.nextLine()
        );
    }

    @Tool(description = """
            Finish the current task and provide the final answer.
            Use when the requested work is complete or no further
            tool operations are necessary.
            Never claim a memory operation succeeded unless confirmed.
            """)
    public UserInteractionResult finish(
            @Param(description = "Final response to the user")
            String answer
    ) {
        System.out.println();
        System.out.println("Agent: " + answer);
        return new UserInteractionResult(
                InteractionType.FINISH,
                answer
        );
    }

    public enum InteractionType {
        ASK_USER,
        FINISH
    }

    public record UserInteractionResult(
            InteractionType type,
            String message
    ) {
    }
}