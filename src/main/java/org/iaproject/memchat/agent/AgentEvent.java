package org.iaproject.memchat.agent;

public record AgentEvent(Type type, String message, java.util.UUID runId, AgentRunResult result,
                         ToolExecution toolExecution) {
    public AgentEvent(Type type, String message, java.util.UUID runId, AgentRunResult result) {
        this(type, message, runId, result, null);
    }

    public AgentEvent(Type type, String message) {
        this(type, message, null, null);
    }

    public enum Type {
        AGENT_MESSAGE,
        TOOL_EXECUTION,
        TOOL_COMPLETED,
        ERROR,
        SYSTEM,
        RUN_COMPLETED
    }

    /** A completion carries the actual tool outcome, independently of the whole agent run. */
    public record ToolExecution(String callId, String name,
                                org.iaproject.memchat.tools.ToolResult result, long durationMillis) {}
}
