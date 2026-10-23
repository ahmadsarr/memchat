package org.iaproject.memchat.agent;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

/** A run handle. Cancellation targets this run, never its executor's worker thread. */
public final class AgentRun {
    private final UUID id = UUID.randomUUID();
    private final CompletableFuture<AgentRunResult> completion = new CompletableFuture<>();
    private final Predicate<AgentRun> cancellation;
    private volatile AgentRunState state = AgentRunState.CREATED;

    AgentRun(Predicate<AgentRun> cancellation) { this.cancellation = cancellation; }

    public UUID id() { return id; }
    public AgentRunState state() { return state; }

    /** Returns true only for the first accepted cancellation request. */
    public boolean cancel() { return cancellation.test(this); }

    /** A read-only stage: cancelling/completing a derived future cannot change this run. */
    public CompletionStage<AgentRunResult> completion() { return completion.minimalCompletionStage(); }

    void transition(AgentRunState state) { this.state = state; }
    void complete(AgentRunResult result) { completion.complete(result); }
}
