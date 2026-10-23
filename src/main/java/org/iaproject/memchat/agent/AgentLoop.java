package org.iaproject.memchat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.action.ActionExecutor;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.LLmApiClient.*;
import org.iaproject.memchat.memory.MemoryFullException;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.tools.ToolParser;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Synchronous conversation session. Not thread-safe or reentrant: the caller owns
 * exclusive access, scheduling and shutdown coordination. Close only after run returns;
 * listeners must not call back into this session or mutate its memory.
 */
public class AgentLoop implements AutoCloseable {
    public enum State { OPEN, CLOSED }

    private final int maxStep;
    private final LLmApiClient client;
    private final ActionExecutor actionExecutor;
    private final MemoryManager memoryManager;
    private final AgentProcessor agentProcessor;
    private final AgentEventDispatcher eventDispatcher;
    private State state = State.OPEN;
    private boolean recoveryRequired;

    public AgentLoop(int maxStep, LLmApiClient client, MemoryManager memoryManager, Object... tools) {
        if (maxStep < 1) throw new IllegalArgumentException("maxStep must be positive");
        this.maxStep = maxStep;
        this.client = Objects.requireNonNull(client);
        this.memoryManager = Objects.requireNonNull(memoryManager);
        this.actionExecutor = new ActionExecutor(ToolParser.parse(List.of(tools)));
        this.agentProcessor = new AgentProcessor(memoryManager, actionExecutor, new ObjectMapper());
        this.eventDispatcher = new AgentEventDispatcher();
    }

    public State state() { return state; }
    public boolean recoveryRequired() { return recoveryRequired; }

    public AutoCloseable subscribe(AgentListener listener) {
        requireOpen();
        return eventDispatcher.subscription(listener);
    }

    /** Returns the outcome on the caller's thread without waiting for event delivery. */
    public AgentRunResult run(String instruction) {
        requireOpen();
        if (instruction == null || instruction.isBlank())
            throw new IllegalArgumentException("instruction must not be empty");
        if (recoveryRequired) throw new IllegalStateException("Session memory requires recovery");
        UUID runId = UUID.randomUUID();
        agentProcessor.setEventPublisher(event -> eventDispatcher.publish(
                new AgentEvent(event.type(), event.message(), runId, null)));
        int steps = 0;
        AgentRunResult.Stage stage = AgentRunResult.Stage.INSTRUCTION;
        boolean mutationPending = false;
        AgentRunState outcome = AgentRunState.LIMIT_REACHED;
        String answer = null;
        AgentRunResult.Failure failure = null;
        boolean needsRecovery = false;
        Error fatal = null;
        try {
            checkInterruption();
            mutationPending = true;
            memoryManager.addMessage(ChatMessage.user(instruction));
            mutationPending = false;
            for (int i = 0; i < maxStep; i++) {
                checkInterruption();
                stage = AgentRunResult.Stage.CONTEXT;
                List<ChatMessage> context = getContext();
                checkInterruption();
                stage = AgentRunResult.Stage.MODEL;
                steps++;
                ChatResponse response = client.complete(context, actionExecutor.getToolsDescriptions());
                checkInterruption();
                stage = AgentRunResult.Stage.RESPONSE;
                AgentStepResult step = agentProcessor.processResult(response, Thread.currentThread()::isInterrupted);
                checkInterruption();
                if (step.decision() == AgentDecision.COMPLETE) {
                    outcome = AgentRunState.SUCCEEDED;
                    answer = step.finalResponse();
                    break;
                }
            }
        } catch (Exception exception) {
            needsRecovery = mutationPending || agentProcessor.recoveryRequired();
            if (!needsRecovery && (exception instanceof CancellationException
                    || Thread.currentThread().isInterrupted())) {
                outcome = AgentRunState.CANCELLED;
            } else {
                outcome = AgentRunState.FAILED;
                failure = new AgentRunResult.Failure(stage, exception.getClass().getName(), exception.getMessage());
            }
        } catch (Error error) {
            // Record the failure and quarantine memory, then propagate fatal errors to the caller.
            outcome = AgentRunState.FAILED;
            needsRecovery = true;
            failure = new AgentRunResult.Failure(stage, error.getClass().getName(), error.getMessage());
            fatal = error;
        }
        recoveryRequired |= needsRecovery;
        AgentRunResult result = new AgentRunResult(runId, outcome, answer, failure, steps, needsRecovery);
        if (outcome == AgentRunState.SUCCEEDED) {
            eventDispatcher.publish(new AgentEvent(AgentEvent.Type.AGENT_MESSAGE, answer, runId, null));
        }
        eventDispatcher.publish(new AgentEvent(AgentEvent.Type.RUN_COMPLETED, outcome.name(), runId, result));
        if (fatal != null) throw fatal;
        return result;
    }

    private static void checkInterruption() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("Agent execution interrupted");
    }

    private void requireOpen() {
        if (state != State.OPEN) throw new IllegalStateException("Session is " + state);
    }

    private List<ChatMessage> getContext() {
        try {
            return memoryManager.context();
        } catch (MemoryFullException exception) {
            memoryManager.compact();
            return memoryManager.context();
        }
    }

    /** Idempotent. The caller must finish execution first; accepted events are drained here. */
    @Override public void close() {
        if (state == State.CLOSED) return;
        eventDispatcher.close();
        state = State.CLOSED;
    }
}
