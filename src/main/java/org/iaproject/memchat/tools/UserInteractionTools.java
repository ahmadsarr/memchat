package org.iaproject.memchat.tools;

import org.iaproject.memchat.UserInteraction;

import java.util.Objects;
public class UserInteractionTools {

    private final UserInteraction interaction;

    public UserInteractionTools(UserInteraction interaction) {
        this.interaction = Objects.requireNonNull(interaction);
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

        String answer = interaction.ask(question);

        return new UserInteractionResult(
                InteractionType.ASK_USER,
                answer
        );
    }

    @Tool(description = """
            Finish the current task and provide the final answer.
            Use when the requested work is complete or no further
            tool operations are necessary.
            Never claim a memory operation succeeded unless confirmed.
            """, terminal = true)
    public String finish(
            @Param(description = "Final response to the user")
            String answer
    ) {
        return answer;
    }

    public enum InteractionType {
        ASK_USER,
        FINISH
    }

    public record UserInteractionResult(
            InteractionType type,
            String message
    ) {}
}
