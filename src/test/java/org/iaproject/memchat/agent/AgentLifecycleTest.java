package org.iaproject.memchat.agent;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.LLmApiClient.*;
import org.iaproject.memchat.memory.*;
import org.iaproject.memchat.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AgentLifecycleTest {
    @Test void returnsFinalAnswerWithoutSubscribers() {
        try (var session = session((messages, tools) -> answer("direct"))) {
            var result = session.run("question");
            assertEquals(AgentRunState.SUCCEEDED, result.status());
            assertEquals("direct", result.finalResponse());
            assertEquals(1, result.stepsExecuted());
            assertNull(result.failure());
            assertFalse(result.recoveryRequired());
            assertNotEquals(result.runId(), session.run("again").runId());
        }
    }

    @Test void returnsTerminalToolValueAndSameTerminalEventResult() throws Exception {
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var tool = new Tools();
        var session = new AgentLoop(2, (m, t) -> call("finish"), memory(), tool);
        session.subscribe(events::add);
        var result = session.run("question");
        session.close();
        assertEquals("tool answer", result.finalResponse());
        assertEquals(AgentRunState.SUCCEEDED, result.status());
        var terminal = events.stream().filter(e -> e.type() == AgentEvent.Type.RUN_COMPLETED).toList();
        assertEquals(1, terminal.size());
        assertSame(result, terminal.getFirst().result());
        assertTrue(events.stream().allMatch(e -> result.runId().equals(e.runId())));
        assertEquals(1, tool.calls.get());
    }

    @Test void stepBudgetIsNotSuccess() {
        var tool = new Tools();
        try (var session = new AgentLoop(2, (m, t) -> call("step"), memory(), tool)) {
            var result = session.run("question");
            assertEquals(AgentRunState.LIMIT_REACHED, result.status());
            assertEquals(2, result.stepsExecuted());
            assertNull(result.finalResponse());
            assertEquals(2, tool.calls.get());
        }
    }

    @Test void recordsModelFailureAndAllowsNextRunOnCoherentMemory() {
        var count = new AtomicInteger();
        try (var session = session((m, t) -> {
            if (count.getAndIncrement() == 0) throw new IllegalStateException("offline");
            return answer("retry");
        })) {
            var failed = session.run("first");
            assertEquals(AgentRunState.FAILED, failed.status());
            assertEquals(AgentRunResult.Stage.MODEL, failed.failure().stage());
            assertEquals("offline", failed.failure().message());
            assertFalse(failed.recoveryRequired());
            assertEquals("retry", session.run("next").finalResponse());
        }
    }

    @Test void malformedResponseFailsBeforeMemoryIsMarkedUncertain() {
        try (var session = session((m, t) -> null)) {
            var result = session.run("question");
            assertEquals(AgentRunState.FAILED, result.status());
            assertEquals(AgentRunResult.Stage.RESPONSE, result.failure().stage());
            assertFalse(result.recoveryRequired());
        }
    }

    @Test void cancelsQueuedRunWithoutWaitingForExecutorAndOldTaskCannotAffectNextRun() throws Exception {
        var queue = new ArrayList<Runnable>();
        var calls = new AtomicInteger();
        var mem = memory();
        try (var session = new AgentLoop(2, (m, t) -> { calls.incrementAndGet(); return answer("done"); },
                mem, List.of(), queue::add)) {
            var run = session.submit("queued");
            assertEquals(AgentRunState.CREATED, run.state());
            assertTrue(run.cancel());
            assertFalse(run.cancel());
            assertEquals(AgentRunState.CANCELLED, result(run).status());
            assertEquals(0, result(run).stepsExecuted());
            assertFalse(mem.context().stream().anyMatch(m -> m.role() == Role.USER));
            var next = session.submit("next");
            queue.getFirst().run();
            assertEquals(AgentRunState.CREATED, next.state());
            assertEquals(0, calls.get());
            queue.getLast().run();
            assertEquals("done", result(next).finalResponse());
            assertFalse(next.cancel());
        }
    }

    @Test void runningCancellationWaitsForModelAndDoesNotInterruptApplicationWorker() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tool = new Tools();
        var calls = new AtomicInteger();
        try (var pool = Executors.newSingleThreadExecutor();
             var session = new AgentLoop(2, (m, t) -> {
                 if (calls.getAndIncrement() == 0) { entered.countDown(); await(release); return call("step"); }
                 return answer("next");
             }, memory(), List.of(tool), pool)) {
            var run = session.submit("first");
            try {
                await(entered);
                assertEquals(AgentRunState.RUNNING, run.state());
                assertTrue(run.cancel());
                assertEquals(AgentRunState.CANCELLING, run.state());
                assertFalse(run.completion().toCompletableFuture().isDone());
                assertThrows(IllegalStateException.class, () -> session.run("concurrent"));
            } finally { release.countDown(); }
            assertEquals(AgentRunState.CANCELLED, result(run).status());
            assertEquals(0, tool.calls.get());
            assertFalse(pool.submit(() -> Thread.currentThread().isInterrupted()).get(2, TimeUnit.SECONDS));
            assertEquals("next", result(session.submit("second")).finalResponse());
        }
    }

    @Test void cancellationAfterRecordingToolCallWritesMatchingResultWithoutExecutingTool() throws Exception {
        var queued = new ArrayList<Runnable>();
        var handle = new AtomicReference<AgentRun>();
        var tool = new Tools();
        var mem = memory(message -> {
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) handle.get().cancel();
        });
        try (var session = new AgentLoop(2, (m, t) -> call("step"), mem, List.of(tool), queued::add)) {
            handle.set(session.submit("first"));
            queued.getFirst().run();
            assertEquals(AgentRunState.CANCELLED, result(handle.get()).status());
            assertEquals(0, tool.calls.get());
            assertEquals(Role.TOOL, mem.context().getLast().role());
            assertEquals("call", mem.context().getLast().toolCallId());
            assertFalse(session.recoveryRequired());
        }
    }

    @Test void cancellationDuringToolPreservesItsResultAndDoesNotClaimRollback() throws Exception {
        var tool = new BlockingTool();
        var mem = memory();
        try (var pool = Executors.newSingleThreadExecutor();
             var session = new AgentLoop(2, (m, t) -> call("work"), mem, List.of(tool), pool)) {
            var run = session.submit("first");
            try {
                await(tool.entered);
                assertTrue(run.cancel());
                assertEquals(AgentRunState.CANCELLING, run.state());
                assertFalse(run.completion().toCompletableFuture().isDone());
            } finally { tool.release.countDown(); }
            assertEquals(AgentRunState.CANCELLED, result(run).status());
            assertEquals(1, tool.effects.get());
            assertTrue(mem.context().getLast().content().contains("side effect recorded"));
        }
    }

    @Test void failedToolResultPersistenceBlocksSessionEvenIfCancellationWasRequested() throws Exception {
        var queue = new ArrayList<Runnable>();
        var handle = new AtomicReference<AgentRun>();
        var tool = new Tools();
        var mem = memory(message -> {
            if (message.role() == Role.TOOL) {
                handle.get().cancel();
                throw new IllegalStateException("disk unavailable");
            }
        });
        try (var session = new AgentLoop(2, (m, t) -> call("step"), mem, List.of(tool), queue::add)) {
            handle.set(session.submit("first"));
            queue.getFirst().run();
            var result = result(handle.get());
            assertEquals(AgentRunState.FAILED, result.status());
            assertEquals("disk unavailable", result.failure().message());
            assertTrue(result.recoveryRequired());
            assertTrue(session.recoveryRequired());
            assertThrows(IllegalStateException.class, () -> session.run("retry"));
            assertEquals(1, tool.calls.get());
        }
    }

    @Test void closeTimeoutKeepsClosingAndDoesNotOwnApplicationExecutor() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor();
             var session = new AgentLoop(1, (m, t) -> { entered.countDown(); await(release); return answer("late"); },
                     memory(), List.of(), pool)) {
            var run = session.submit("first");
            try {
                await(entered);
                assertFalse(session.close(Duration.ZERO));
                assertEquals(AgentLoop.State.CLOSING, session.state());
                assertEquals(AgentRunState.CANCELLING, run.state());
                assertThrows(IllegalStateException.class, () -> session.submit("late"));
            } finally { release.countDown(); }
            assertEquals(AgentRunState.CANCELLED, result(run).status());
            assertTrue(session.close(Duration.ofSeconds(2)));
            assertEquals(AgentLoop.State.CLOSED, session.state());
            assertEquals("still usable", pool.submit(() -> "still usable").get(2, TimeUnit.SECONDS));
            session.close();
            assertThrows(IllegalStateException.class, () -> session.run("closed"));
        }
    }

    @Test void closeCancelsQueuedRunEvenIfExecutorNeverRunsIt() throws Exception {
        var queue = new ArrayList<Runnable>();
        var session = new AgentLoop(1, (m, t) -> { fail("must not call model"); return null; },
                memory(), List.of(), queue::add);
        var run = session.submit("queued");
        assertTrue(session.close(Duration.ofSeconds(2)));
        assertEquals(AgentRunState.CANCELLED, result(run).status());
        queue.getFirst().run();
        assertEquals(AgentLoop.State.CLOSED, session.state());
    }

    @Test void executorRejectionReleasesAdmissionWithoutChangingMemory() {
        var mem = memory();
        try (var session = new AgentLoop(1, (m, t) -> answer("sync"), mem, List.of(),
                task -> { throw new RejectedExecutionException("full"); })) {
            assertThrows(RejectedExecutionException.class, () -> session.submit("rejected"));
            assertFalse(mem.context().stream().anyMatch(m -> m.role() == Role.USER));
            assertEquals("sync", session.run("accepted").finalResponse());
        }
    }

    @Test void blockingOrFailingListenerCannotDelayResultButCloseDrainsDelivery() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var session = session((m, t) -> answer("independent"));
        session.subscribe(e -> { entered.countDown(); await(release); throw new IllegalStateException("listener"); });
        session.subscribe(events::add);
        try {
            var result = session.run("question");
            assertEquals("independent", result.finalResponse());
            await(entered);
            assertFalse(session.close(Duration.ZERO));
            assertEquals(AgentLoop.State.CLOSING, session.state());
        } finally { release.countDown(); session.close(); }
        assertEquals(1, events.stream().filter(e -> e.type() == AgentEvent.Type.RUN_COMPLETED).count());
    }

    @Test void listenerMayRequestAsyncCloseWithoutDeadlock() throws Exception {
        var session = session((m, t) -> answer("answer"));
        var requested = new CountDownLatch(1);
        session.subscribe(e -> { session.closeAsync(); requested.countDown(); });
        assertEquals("answer", session.run("question").finalResponse());
        await(requested);
        assertTrue(session.close(Duration.ofSeconds(2)));
    }

    @Test void completionCallbackMayCloseSessionWithoutDeadlock() throws Exception {
        var queue = new ArrayList<Runnable>();
        var session = new AgentLoop(1, (m, t) -> answer("answer"), memory(), List.of(), queue::add);
        var run = session.submit("question");
        var closed = run.completion().thenRun(session::close);
        queue.getFirst().run();
        closed.toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(AgentLoop.State.CLOSED, session.state());
    }

    @Test void queuedCancellationDoesNotRunBlockingCompletionCallbackOnShutdownCaller() throws Exception {
        var queue = new ArrayList<Runnable>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var session = new AgentLoop(1, (m, t) -> answer("unused"), memory(), List.of(), queue::add);
        var run = session.submit("queued");
        var callback = run.completion().thenRun(() -> { entered.countDown(); await(release); });
        try (var waiter = Executors.newSingleThreadExecutor()) {
            try {
                waiter.submit(() -> session.close(Duration.ZERO)).get(1, TimeUnit.SECONDS);
                await(entered);
                assertEquals(AgentRunState.CANCELLED, result(run).status());
                assertTrue(session.close(Duration.ofSeconds(1)));
            } finally { release.countDown(); session.close(); }
            callback.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    @Test void callerCannotCompleteOrCancelUnderlyingCompletion() throws Exception {
        var queue = new ArrayList<Runnable>();
        try (var session = new AgentLoop(1, (m, t) -> answer("real"), memory(), List.of(), queue::add)) {
            var run = session.submit("question");
            run.completion().toCompletableFuture().cancel(true);
            assertEquals(AgentRunState.CREATED, run.state());
            queue.getFirst().run();
            assertEquals("real", result(run).finalResponse());
        }
    }

    @Test void interruptsCancelRunAndPreserveInterruptBit() {
        try (var session = session((m, t) -> { fail("must not start"); return null; })) {
            Thread.currentThread().interrupt();
            try {
                var result = session.run("question");
                assertEquals(AgentRunState.CANCELLED, result.status());
                assertEquals(0, result.stepsExecuted());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test void interruptedToolStopsFurtherStepsAndPreservesToolHistory() {
        var mem = memory();
        try (var session = new AgentLoop(2, (m, t) -> call("interrupt"), mem, new Tools())) {
            try {
                assertEquals(AgentRunState.CANCELLED, session.run("question").status());
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(Role.TOOL, mem.context().getLast().role());
            } finally { Thread.interrupted(); }
        }
    }

    @Test void rejectsInvalidAdmissionAndReentrantRunWithoutExtraMessages() {
        var holder = new AtomicReference<AgentLoop>();
        var mem = memory();
        try (var session = new AgentLoop(1, (m, t) -> {
            assertThrows(IllegalStateException.class, () -> holder.get().run("nested"));
            assertThrows(IllegalStateException.class, holder.get()::close);
            return answer("outer");
        }, mem)) {
            holder.set(session);
            assertThrows(IllegalArgumentException.class, () -> session.run(" "));
            assertThrows(IllegalArgumentException.class, () -> session.run(null));
            assertThrows(IllegalStateException.class, () -> session.submit("no executor"));
            assertEquals("outer", session.run("valid").finalResponse());
            assertEquals(1, mem.context().stream().filter(m -> m.role() == Role.USER).count());
        }
    }

    @Test void terminalOutcomeWinsOrCancellationWinsButNeverBoth() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 50; i++) {
                var release = new CountDownLatch(1);
                var events = new CopyOnWriteArrayList<AgentEvent>();
                try (var session = new AgentLoop(1, (m, t) -> { await(release); return answer("done"); },
                        memory(), List.of(), pool)) {
                    session.subscribe(events::add);
                    var run = session.submit("race");
                    var cancellation = pool.submit(() -> { release.countDown(); return run.cancel(); });
                    var result = result(run);
                    boolean cancelled = cancellation.get(2, TimeUnit.SECONDS);
                    assertEquals(cancelled ? AgentRunState.CANCELLED : AgentRunState.SUCCEEDED, result.status());
                    assertFalse(run.cancel());
                    session.close();
                    var terminals = events.stream().filter(e -> e.type() == AgentEvent.Type.RUN_COMPLETED).toList();
                    assertEquals(1, terminals.size());
                    assertSame(result, terminals.getFirst().result());
                }
            }
        }
    }

    @Test void resultRejectsImpossibleCombinations() {
        var id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new AgentRunResult(id, AgentRunState.RUNNING, null, null, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunResult(id, AgentRunState.SUCCEEDED, null, null, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunResult(id, AgentRunState.FAILED, null, null, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunResult(id, AgentRunState.CANCELLED, "partial", null, 1, false));
    }

    public static class Tools {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(description = "final", terminal = true) public String finish() { calls.incrementAndGet(); return "tool answer"; }
        @Tool(description = "step") public String step() { calls.incrementAndGet(); return "step"; }
        @Tool(description = "interrupt") public String interrupt() throws InterruptedException { throw new InterruptedException("tool interrupted"); }
    }
    public static class BlockingTool {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger effects = new AtomicInteger();
        @Tool(description = "effect", terminal = true) public String work() {
            effects.incrementAndGet(); entered.countDown(); await(release); return "side effect recorded";
        }
    }
    private static AgentRunResult result(AgentRun run) throws Exception { return run.completion().toCompletableFuture().get(2, TimeUnit.SECONDS); }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS), "barrier timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
    private static ChatResponse answer(String text) { return new ChatResponse(text, List.of()); }
    private static ChatResponse call(String tool) { return new ChatResponse(null, List.of(new ToolCall("call", tool, "{}"))); }
    private static AgentLoop session(LLmApiClient client) { return new AgentLoop(3, client, memory()); }
    private static MemoryManager memory() { return memory(message -> {}); }
    private static MemoryManager memory(Consumer<ChatMessage> onAdd) {
        return new MemoryManager(new RecallStorage() {
            public String add(ChatMessage message) { onAdd.accept(message); return "id"; }
            public List<Memory.RecallSearchResult> search(String query, int limit) { return List.of(); }
        }, new ArchivalMemory() {
            public String insert(String content) { return "id"; }
            public List<Memory.MemorySearchResult> search(String query, int limit) { return List.of(); }
            public void update(String id, String content) {}
            public void delete(String id) {}
            public String get(String id) { return null; }
        }, (summary, messages) -> "summary");
    }
}
