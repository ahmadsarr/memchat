package org.iaproject.memchat.agent;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.memory.impl.ArchivalMemoryImpl;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;
import org.iaproject.memchat.memory.impl.RecallStorageImpl;
import org.iaproject.memchat.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunLifecycleTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void constructorRejectsNonPositiveStepLimits(int steps) {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentLoop(steps, (messages, tools) -> answer(), manager()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsEmptyInstructionsBeforeChangingMemory(String instruction) throws Exception {
        var memory = manager();
        var calls = new AtomicInteger();
        var loop = new AgentLoop(1, (messages, tools) -> { calls.incrementAndGet(); return answer(); }, memory);
        try {
            assertThrows(IllegalArgumentException.class, () -> loop.run(instruction));
            assertEquals(0, calls.get());
            assertTrue(memory.context().stream().allMatch(message -> message.role() == LLmApiClient.Role.SYSTEM));
        } finally {
            loop.close();
        }
    }

    @Test
    void closedAgentRejectsRunAndSubscriptionsBeforeAnyWork() throws Exception {
        var calls = new AtomicInteger();
        var memory = manager();
        var loop = new AgentLoop(1, (messages, tools) -> { calls.incrementAndGet(); return answer(); }, memory);
        loop.close();
        assertThrows(IllegalStateException.class, () -> loop.run("late instruction"));
        assertThrows(IllegalStateException.class, () -> loop.subscribe(event -> { }));
        assertEquals(0, calls.get());
        assertFalse(memory.context().contains(LLmApiClient.ChatMessage.user("late instruction")));
        loop.close();
    }

    @Test
    void concurrentRunAndCloseAreRejectedWithoutCorruptingTheActiveRun() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var memory = manager();
        var loop = new AgentLoop(1, (messages, tools) -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
            return answer();
        }, memory);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var active = executor.submit(() -> { loop.run("first"); return null; });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> loop.run("second"));
                assertThrows(IllegalStateException.class, loop::close);
            } finally {
                release.countDown();
                active.get(2, TimeUnit.SECONDS);
                loop.close();
            }
        }
        assertEquals(1, calls.get());
        assertFalse(memory.context().contains(LLmApiClient.ChatMessage.user("second")));
    }

    @Test
    void failedRunReleasesTheExecutionGuard() throws Exception {
        var calls = new AtomicInteger();
        var loop = new AgentLoop(1, (messages, tools) -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("temporary failure");
            return answer();
        }, manager());
        try {
            assertThrows(IllegalStateException.class, () -> loop.run("first"));
            assertDoesNotThrow(() -> loop.run("retry"));
            assertEquals(2, calls.get());
        } finally {
            loop.close();
        }
    }

    @Test
    void preInterruptedRunDoesNotContactTheModelOrChangeMemory() throws Exception {
        var calls = new AtomicInteger();
        var memory = manager();
        var loop = new AgentLoop(1, (messages, tools) -> { calls.incrementAndGet(); return answer(); }, memory);
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> loop.run("cancelled"));
            assertEquals(0, calls.get());
            assertFalse(memory.context().contains(LLmApiClient.ChatMessage.user("cancelled")));
        } finally {
            Thread.interrupted();
            loop.close();
        }
    }

    @Test
    void interruptAfterCompletionPreventsToolExecution() throws Exception {
        var tool = new SideEffectTool();
        var loop = new AgentLoop(1, (messages, tools) -> {
            Thread.currentThread().interrupt();
            return new LLmApiClient.ChatResponse(null,
                    List.of(new LLmApiClient.ToolCall("one", "perform", "{}")));
        }, manager(), tool);
        try {
            assertThrows(InterruptedException.class, () -> loop.run("cancelled"));
            assertEquals(0, tool.calls.get());
        } finally {
            Thread.interrupted();
            loop.close();
        }
    }

    @Test
    void interruptionThrownByAToolStopsTheRun() throws Exception {
        var loop = new AgentLoop(1, (messages, tools) -> new LLmApiClient.ChatResponse(null,
                List.of(new LLmApiClient.ToolCall("one", "cancel", "{}"))), manager(), new SideEffectTool());
        try {
            assertThrows(InterruptedException.class, () -> loop.run("cancelled tool"));
        } finally {
            Thread.interrupted();
            loop.close();
        }
    }

    @Test
    void preCancelledRunDoesNotContactTheModelOrChangeMemory() throws Exception {
        var cancellation = new AgentCancellation();
        cancellation.cancel();
        var memory = manager();
        try (var loop = new AgentLoop(1, (messages, tools) -> {
            fail("Cancelled run must not contact the model");
            return answer();
        }, memory)) {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> loop.run("cancelled", cancellation));
            assertFalse(memory.context().contains(LLmApiClient.ChatMessage.user("cancelled")));
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }

    @Test
    void cancelsFromAnotherThreadWithoutInterruptingOrPoisoningThePooledWorker() throws Exception {
        var cancellation = new AgentCancellation();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var workerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var tool = new SideEffectTool();
        var loop = new AgentLoop(2, (messages, tools) -> {
            if (calls.incrementAndGet() == 1) {
                workerThread.set(Thread.currentThread());
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                } catch (InterruptedException e) {
                    throw new AssertionError("Cooperative cancellation must not interrupt the worker", e);
                }
                return new LLmApiClient.ChatResponse(null,
                        List.of(new LLmApiClient.ToolCall("one", "perform", "{}")));
            }
            return answer();
        }, manager(), tool);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var active = executor.submit(() -> { loop.run("first", cancellation); return null; });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                cancellation.cancel();
                assertTrue(cancellation.isCancelled());
                assertFalse(active.isDone(), "An in-flight client call remains cooperative");
            } finally {
                release.countDown();
            }
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> active.get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause());
            assertEquals(0, tool.calls.get());
            executor.submit(() -> {
                assertSame(workerThread.get(), Thread.currentThread());
                assertFalse(Thread.currentThread().isInterrupted());
                loop.run("next request", new AgentCancellation());
                return null;
            }).get(2, TimeUnit.SECONDS);
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
            loop.close();
        }
    }

    public static class SideEffectTool {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(description = "Perform a side effect")
        public String perform() { calls.incrementAndGet(); return "done"; }
        @Tool(description = "Interrupted tool")
        public String cancel() throws InterruptedException { throw new InterruptedException("cancelled"); }
    }

    private static LLmApiClient.ChatResponse answer() {
        return new LLmApiClient.ChatResponse("answer", List.of());
    }

    private MemoryManager manager() {
        return new MemoryManager(
                new RecallStorageImpl(new FileRecallStorageRepository(directory.resolve("recall.json"))),
                new ArchivalMemoryImpl(new FileArchivalMemoryRepository(directory.resolve("archive.json"))),
                (summary, messages) -> "summary");
    }
}
