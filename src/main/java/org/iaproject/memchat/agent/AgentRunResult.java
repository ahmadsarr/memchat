package org.iaproject.memchat.agent;

import java.util.Objects;
import java.util.UUID;

/** The terminal outcome of a synchronous run; independent of event delivery. */
public record AgentRunResult(UUID runId, AgentRunState status, String finalResponse,
                             Failure failure, int stepsExecuted, boolean recoveryRequired) {
    public enum Stage { INSTRUCTION, CONTEXT, MODEL, RESPONSE }

    /** Immutable diagnostic snapshot. Tool side effects are never rolled back automatically. */
    public record Failure(Stage stage, String type, String message) {
        public Failure {
            Objects.requireNonNull(stage);
            Objects.requireNonNull(type);
        }
    }

    public AgentRunResult {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(status);
        if ((status == AgentRunState.SUCCEEDED) != (finalResponse != null))
            throw new IllegalArgumentException("Only success must have a final response");
        if ((status == AgentRunState.FAILED) != (failure != null))
            throw new IllegalArgumentException("Only failure must have a cause");
        if (stepsExecuted < 0) throw new IllegalArgumentException("Negative step count");
        if (recoveryRequired && status != AgentRunState.FAILED)
            throw new IllegalArgumentException("Recovery requires a failure");
    }
}
