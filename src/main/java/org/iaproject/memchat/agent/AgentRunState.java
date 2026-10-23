package org.iaproject.memchat.agent;

/** Terminal outcomes of a synchronous run, not executor or task states. */
public enum AgentRunState {
    SUCCEEDED, CANCELLED, FAILED, LIMIT_REACHED
}
