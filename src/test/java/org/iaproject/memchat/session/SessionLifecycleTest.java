package org.iaproject.memchat.session;

import org.iaproject.memchat.UserInteraction;
import org.iaproject.memchat.agent.AgentBuilder;
import org.iaproject.memchat.agent.AgentLoop;
import org.iaproject.memchat.agent.AgentRunResult;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;
import org.iaproject.memchat.llm.LLmApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLifecycleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void closeWaitsForRunAndRejectsNewRuns() throws Exception {
        CountDownLatch clientEntered = new CountDownLatch(1);
        CountDownLatch releaseClient = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        AtomicReference<AgentRunResult> runResult = new AtomicReference<>();

        LLmApiClient client = (messages, tools) -> {
            clientEntered.countDown();
            try {
                releaseClient.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new LLmApiClient.ChatResponse("answer", List.of());
        };

        try (Session session = new Session(agent(client))) {
            Thread runThread = new Thread(() -> {
                try {
                    runResult.set(session.run("first"));
                } catch (Throwable throwable) {
                    runFailure.set(throwable);
                }
            });
            runThread.start();
            assertTrue(clientEntered.await(5, TimeUnit.SECONDS));

            Thread closeThread = new Thread(() -> {
                session.close();
                closeFinished.countDown();
            });
            closeThread.start();

            assertFalse(closeFinished.await(200, TimeUnit.MILLISECONDS));
            assertThrows(IllegalStateException.class, () -> session.run("second"));

            releaseClient.countDown();
            runThread.join(5_000);
            closeThread.join(5_000);
            assertFalse(runThread.isAlive());
            assertFalse(closeThread.isAlive());
            assertNull(runFailure.get());
            assertEquals("answer", runResult.get().finalResponse());
            assertEquals(0, closeFinished.getCount());
            assertEquals(AgentLoop.State.CLOSED, session.state());
        } finally {
            releaseClient.countDown();
        }
    }

    private AgentLoop agent(LLmApiClient client) {
        return AgentBuilder.builder()
                .client(client)
                .recallStorageRepository(new FileRecallStorageRepository(temporaryDirectory.resolve("recall.json")))
                .archivalMemoryRepository(new FileArchivalMemoryRepository(temporaryDirectory.resolve("memory.json")))
                .userInteraction(interaction())
                .build();
    }

    private static UserInteraction interaction() {
        return new UserInteraction() {
            @Override public String read() { return "input"; }
            @Override public String ask(String message) { return "answer"; }
            @Override public void display(String answer) { }
        };
    }
}
