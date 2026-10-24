package org.iaproject.memchat.agent;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.LLmApiClient.*;
import org.iaproject.memchat.memory.*;
import org.iaproject.memchat.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AgentLifecycleTest {
    @Test void executesOnTheCallerThreadWithoutAnExecutor() {
        Thread caller = Thread.currentThread();
        try (var session = session((messages, tools) -> {
            assertSame(caller, Thread.currentThread());
            return answer("synchronous");
        })) {
            assertEquals("synchronous", session.run("question").finalResponse());
        }
    }

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

    @Test void rejectsInvalidInstructionAndCallsAfterClosureWithoutChangingMemory() {
        var mem = memory();
        var session = new AgentLoop(1, (m, t) -> answer("valid"), mem);
        assertEquals(AgentLoop.State.OPEN, session.state());
        assertThrows(IllegalArgumentException.class, () -> session.run(" "));
        assertThrows(IllegalArgumentException.class, () -> session.run(null));
        assertFalse(mem.context().stream().anyMatch(m -> m.role() == Role.USER));
        assertEquals("valid", session.run("question").finalResponse());
        session.close();
        session.close();
        assertEquals(AgentLoop.State.CLOSED, session.state());
        assertThrows(IllegalStateException.class, () -> session.run("closed"));
        assertThrows(IllegalStateException.class, () -> session.subscribe(e -> {}));
        assertEquals(1, mem.context().stream().filter(m -> m.role() == Role.USER).count());
    }

    @Test void failedToolResultPersistenceBlocksReuseOfUncertainMemory() {
        var tool = new Tools();
        var mem = memory(message -> {
            if (message.role() == Role.TOOL) throw new IllegalStateException("disk unavailable");
        });
        try (var session = new AgentLoop(2, (m, t) -> call("step"), mem, tool)) {
            var result = session.run("question");
            assertEquals(AgentRunState.FAILED, result.status());
            assertEquals(AgentRunResult.Stage.RESPONSE, result.failure().stage());
            assertEquals("disk unavailable", result.failure().message());
            assertTrue(result.recoveryRequired());
            assertTrue(session.recoveryRequired());
            assertEquals(1, tool.calls.get());
            assertThrows(IllegalStateException.class, () -> session.run("retry"));
            assertEquals(1, tool.calls.get());
        }
    }

    @Test void failedInstructionWriteBlocksReuseAndNeverCallsTheModel() {
        var mem = memory(message -> { throw new IllegalStateException("write failed"); });
        try (var session = new AgentLoop(1, (m, t) -> { fail("model must not run"); return null; }, mem)) {
            var result = session.run("question");
            assertEquals(AgentRunState.FAILED, result.status());
            assertEquals(AgentRunResult.Stage.INSTRUCTION, result.failure().stage());
            assertEquals(0, result.stepsExecuted());
            assertTrue(result.recoveryRequired());
            assertThrows(IllegalStateException.class, () -> session.run("retry"));
        }
    }

    @Test void interruptionAfterRecordingCallStillWritesMatchingResultWithoutExecutingTool() {
        var tool = new Tools();
        var mem = memory(message -> {
            if (message.toolCalls() != null && !message.toolCalls().isEmpty())
                Thread.currentThread().interrupt();
        });
        try (var session = new AgentLoop(2, (m, t) -> call("step"), mem, tool)) {
            try {
                var result = session.run("question");
                assertEquals(AgentRunState.CANCELLED, result.status());
                assertEquals(0, tool.calls.get());
                assertEquals(Role.TOOL, mem.context().getLast().role());
                assertEquals("call", mem.context().getLast().toolCallId());
                assertFalse(session.recoveryRequired());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test void interruptionAfterModelResponsePreventsToolExecution() {
        var tool = new Tools();
        try (var session = new AgentLoop(2, (m, t) -> {
            Thread.currentThread().interrupt();
            return call("step");
        }, memory(), tool)) {
            try {
                assertEquals(AgentRunState.CANCELLED, session.run("question").status());
                assertEquals(0, tool.calls.get());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test void slowAndFailingListenersDoNotBlockTheDirectResult() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var session = session((m, t) -> answer("independent"));
        session.subscribe(e -> { entered.countDown(); await(release); throw new IllegalStateException("listener"); });
        session.subscribe(events::add);
        AgentRunResult result;
        try {
            result = session.run("question");
            assertEquals("independent", result.finalResponse());
            await(entered);
        } finally { release.countDown(); session.close(); }
        var terminal = events.stream().filter(e -> e.type() == AgentEvent.Type.RUN_COMPLETED).toList();
        assertEquals(1, terminal.size());
        assertSame(result, terminal.getFirst().result());
    }

    @Test void sequentialRunsHaveDistinctCorrelatedTerminalEvents() {
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var session = session((m, t) -> answer("done"));
        session.subscribe(events::add);
        var first = session.run("first");
        var second = session.run("second");
        session.close();
        assertNotEquals(first.runId(), second.runId());
        var terminals = events.stream().filter(e -> e.type() == AgentEvent.Type.RUN_COMPLETED).toList();
        assertEquals(2, terminals.size());
        assertSame(first, terminals.getFirst().result());
        assertSame(second, terminals.getLast().result());
        assertEquals(first.runId(), terminals.getFirst().runId());
        assertEquals(second.runId(), terminals.getLast().runId());
    }

    @Test void fatalErrorsPropagateAndBlockReuseOfTheSession() {
        var error = new AssertionError("fatal model error");
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var session = session((m, t) -> { throw error; });
        session.subscribe(events::add);
        assertSame(error, assertThrows(AssertionError.class, () -> session.run("question")));
        assertTrue(session.recoveryRequired());
        assertThrows(IllegalStateException.class, () -> session.run("retry"));
        session.close();
        assertEquals(AgentRunState.FAILED, events.getLast().result().status());
        assertTrue(events.getLast().result().recoveryRequired());
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

    @Test void resultRejectsImpossibleCombinations() {
        var id = UUID.randomUUID();
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
