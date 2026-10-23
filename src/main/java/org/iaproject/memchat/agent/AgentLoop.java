package org.iaproject.memchat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.action.ActionExecutor;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.LLmApiClient.*;
import org.iaproject.memchat.memory.MemoryFullException;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.tools.ToolParser;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/** A conversation session: one admitted run at a time, independent of worker threads. */
public class AgentLoop implements AutoCloseable {
    public enum State { OPEN, CLOSING, CLOSED }

    private final Object lifecycle = new Object();
    private final int maxStep;
    private final LLmApiClient client;
    private final ActionExecutor actionExecutor;
    private final MemoryManager memoryManager;
    private final AgentProcessor agentProcessor;
    private final AgentEventDispatcher eventDispatcher = new AgentEventDispatcher();
    private final Executor executor;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    // Only detects blocking self-joins; execution identity/state never depends on this thread-local.
    private final ThreadLocal<Boolean> executing = ThreadLocal.withInitial(() -> false);
    private State state = State.OPEN;
    private AgentRun active;
    private boolean recoveryRequired;
    private boolean shutdownStarted;
    private AgentRun terminalRun;

    public AgentLoop(int maxStep, LLmApiClient client, MemoryManager memoryManager, Object... tools) {
        this(maxStep, client, memoryManager, List.of(tools), null);
    }

    public AgentLoop(int maxStep, LLmApiClient client, MemoryManager memoryManager,
                     List<Object> tools, Executor executor) {
        if (maxStep < 1) throw new IllegalArgumentException("maxStep must be positive");
        this.maxStep = maxStep;
        this.client = Objects.requireNonNull(client);
        this.memoryManager = Objects.requireNonNull(memoryManager);
        this.executor = executor;
        this.actionExecutor = new ActionExecutor(ToolParser.parse(List.copyOf(tools)));
        this.agentProcessor = new AgentProcessor(memoryManager, actionExecutor, new ObjectMapper());
        this.agentProcessor.setEventPublisher(event -> {
            synchronized (lifecycle) {
                eventDispatcher.publish(new AgentEvent(event.type(), event.message(), active.id(), null));
            }
        });
    }

    public State state() { synchronized (lifecycle) { return state; } }
    public boolean recoveryRequired() { synchronized (lifecycle) { return recoveryRequired; } }

    public AutoCloseable subscribe(AgentListener listener) {
        synchronized (lifecycle) {
            requireOpen();
            return eventDispatcher.subscription(listener);
        }
    }

    /** Runs on the caller's thread and returns the outcome without waiting for listeners. */
    public AgentRunResult run(String instruction) {
        AgentRun run = admit(instruction);
        execute(run, instruction);
        return run.completion().toCompletableFuture().join();
    }

    /** Reserves the session immediately, then schedules on the application's executor. */
    public AgentRun submit(String instruction) {
        if (executor == null) throw new IllegalStateException("Configure an executor before submit");
        AgentRun run = admit(instruction);
        try {
            executor.execute(() -> execute(run, instruction));
        } catch (RejectedExecutionException exception) {
            synchronized (lifecycle) {
                // Closing/cancellation may already have completed a queued run.
                if (run.state().isTerminal()) return run;
                if (active == run && run.state() == AgentRunState.CREATED) active = null;
                else throw exception;
            }
            shutdownIfIdle();
            throw exception;
        }
        return run;
    }

    private AgentRun admit(String instruction) {
        if (instruction == null || instruction.isBlank())
            throw new IllegalArgumentException("instruction must not be empty");
        synchronized (lifecycle) {
            requireOpen();
            if (recoveryRequired) throw new IllegalStateException("Session memory requires recovery");
            if (active != null) throw new IllegalStateException("Session already has an active run");
            active = new AgentRun(this::cancel);
            return active;
        }
    }

    private void execute(AgentRun run, String instruction) {
        synchronized (lifecycle) {
            if (run.state().isTerminal()) return; // A queued run can be cancelled without a worker.
            run.transition(AgentRunState.RUNNING);
        }
        executing.set(true);
        int steps = 0;
        AgentRunResult.Stage stage = AgentRunResult.Stage.INSTRUCTION;
        boolean mutationPending = false;
        AgentRunState outcome = AgentRunState.LIMIT_REACHED;
        String answer = null;
        AgentRunResult.Failure failure = null;
        boolean needsRecovery = false;
        Error fatal = null;
        try {
            checkCancellation(run);
            mutationPending = true;
            memoryManager.addMessage(ChatMessage.user(instruction));
            mutationPending = false;
            for (int i = 0; i < maxStep; i++) {
                checkCancellation(run);
                stage = AgentRunResult.Stage.CONTEXT;
                List<ChatMessage> context = getContext();
                checkCancellation(run);
                stage = AgentRunResult.Stage.MODEL;
                steps++;
                ChatResponse response = client.complete(context, actionExecutor.getToolsDescriptions());
                checkCancellation(run);
                stage = AgentRunResult.Stage.RESPONSE;
                AgentStepResult step = agentProcessor.processResult(response, () -> cancellationRequested(run));
                checkCancellation(run);
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
            // Release the lifecycle even on fatal errors, but never disguise them as normal failures.
            outcome = AgentRunState.FAILED;
            needsRecovery = true;
            failure = new AgentRunResult.Failure(stage, error.getClass().getName(), error.getMessage());
            fatal = error;
        } finally {
            executing.remove();
        }
        finish(run, outcome, answer, failure, steps, needsRecovery);
        if (fatal != null) throw fatal;
    }

    private boolean cancellationRequested(AgentRun run) {
        synchronized (lifecycle) {
            if ((Thread.currentThread().isInterrupted() || state != State.OPEN) && !run.state().isTerminal())
                run.transition(AgentRunState.CANCELLING);
            return run.state() == AgentRunState.CANCELLING;
        }
    }

    private void checkCancellation(AgentRun run) {
        if (cancellationRequested(run)) throw new CancellationException("Agent execution cancelled");
    }

    private boolean cancel(AgentRun run) {
        AgentRunResult result = null;
        synchronized (lifecycle) {
            if (run.state().isTerminal() || run.state() == AgentRunState.CANCELLING) return false;
            if (run.state() == AgentRunState.CREATED) {
                result = terminal(run, AgentRunState.CANCELLED, null, null, 0, false);
            } else {
                run.transition(AgentRunState.CANCELLING);
            }
        }
        if (result != null) {
            shutdownIfIdle();
            AgentRunResult cancelled = result;
            // Completion callbacks are application code: they must not block cancel/closeAsync.
            Thread.ofVirtual().name("memchat-cancel").start(() -> run.complete(cancelled));
        }
        return true;
    }

    private void finish(AgentRun run, AgentRunState outcome, String answer,
                        AgentRunResult.Failure failure, int steps, boolean needsRecovery) {
        AgentRunResult result;
        synchronized (lifecycle) {
            // Cancellation and terminal commitment share one lock. Failures retain their cause.
            if ((run.state() == AgentRunState.CANCELLING || state != State.OPEN)
                    && outcome != AgentRunState.FAILED) {
                outcome = AgentRunState.CANCELLED;
                answer = null;
            }
            result = terminal(run, outcome, answer, failure, steps, needsRecovery);
        }
        shutdownIfIdle();
        run.complete(result);
    }

    // Called under lifecycle. Enqueue terminal events before admitting another run or closing.
    private AgentRunResult terminal(AgentRun run, AgentRunState outcome, String answer,
                                   AgentRunResult.Failure failure, int steps, boolean needsRecovery) {
        AgentRunResult result = new AgentRunResult(run.id(), outcome, answer, failure, steps, needsRecovery);
        run.transition(outcome);
        recoveryRequired |= needsRecovery;
        active = null;
        terminalRun = run;
        if (outcome == AgentRunState.SUCCEEDED)
            eventDispatcher.publish(new AgentEvent(AgentEvent.Type.AGENT_MESSAGE, answer, run.id(), null));
        eventDispatcher.publish(new AgentEvent(AgentEvent.Type.RUN_COMPLETED, outcome.name(), run.id(), result));
        return result;
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

    /** Reject new runs, cancel the active one, then drain callbacks. Never shuts down the supplied executor. */
    public CompletionStage<Void> closeAsync() {
        AgentRun run;
        synchronized (lifecycle) {
            if (state == State.OPEN) state = State.CLOSING;
            run = active;
        }
        if (run != null) cancel(run);
        shutdownIfIdle();
        return closed.minimalCompletionStage();
    }

    private void shutdownIfIdle() {
        AgentRun last;
        synchronized (lifecycle) {
            if (state != State.CLOSING || active != null || shutdownStarted) return;
            shutdownStarted = true;
            last = terminalRun;
        }
        // Our event dispatcher owns resources. Its drain must not run on an application worker
        // or in a listener/completion callback that might itself await session closure.
        Thread.ofVirtual().name("memchat-close").start(() -> {
            if (last != null) last.completion().toCompletableFuture().join();
            eventDispatcher.close();
            synchronized (lifecycle) { state = State.CLOSED; }
            closed.complete(null);
        });
    }

    /** A timeout leaves the session CLOSING; blocking operations are never forcibly stopped. */
    public boolean close(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative timeout");
        long nanos = timeout.toNanos();
        requireExternalWaiter();
        closeAsync();
        try {
            closed.get(nanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException exception) {
            return false;
        } catch (ExecutionException exception) {
            throw new IllegalStateException(exception.getCause());
        }
    }

    private void requireExternalWaiter() {
        if (executing.get() || eventDispatcher.isDelivering())
            throw new IllegalStateException("Use closeAsync from an agent tool or event listener");
    }

    /** Graceful, unbounded close. Use close(Duration) when shutdown must have a deadline. */
    @Override public void close() {
        requireExternalWaiter();
        closeAsync().toCompletableFuture().join();
    }
}
