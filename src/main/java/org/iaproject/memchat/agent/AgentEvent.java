package org.iaproject.memchat.agent;

public record AgentEvent(Type type, String message) {
    public enum Type {
        AGENT_MESSAGE,
        TOOL_EXECUTION,
        ERROR,
        SYSTEM
    }
}
