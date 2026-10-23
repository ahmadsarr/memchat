package org.iaproject.memchat.agent;

public enum AgentRunState {
    CREATED, RUNNING, CANCELLING, SUCCEEDED, CANCELLED, FAILED, LIMIT_REACHED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELLED || this == FAILED || this == LIMIT_REACHED;
    }
}
