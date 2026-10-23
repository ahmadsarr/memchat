package org.iaproject.memchat.agent;

public record AgentEvent(Type type, String message, java.util.UUID runId, AgentRunResult result) {
    public AgentEvent(Type type, String message) {
        this(type, message, null, null);
    }

    public enum Type {
        AGENT_MESSAGE,
        TOOL_EXECUTION,
        ERROR,
        SYSTEM,
        RUN_COMPLETED
    }
}
