package org.iaproject.memchat.agent;

import java.util.Objects;

/** Processor output, including the value returned by a terminal tool. */
public record AgentStepResult(AgentDecision decision, String finalResponse) {
    public AgentStepResult {
        Objects.requireNonNull(decision);
        if ((decision == AgentDecision.COMPLETE) != (finalResponse != null))
            throw new IllegalArgumentException("Only a complete step must have a response");
    }
}
