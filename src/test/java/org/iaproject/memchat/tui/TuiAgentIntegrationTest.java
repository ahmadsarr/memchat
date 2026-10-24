package org.iaproject.memchat.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.iaproject.memchat.agent.AgentEvent;
import org.iaproject.memchat.agent.AgentLoop;
import org.iaproject.memchat.agent.AgentRunState;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.ArchivalMemory;
import org.iaproject.memchat.memory.Memory;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.memory.RecallStorage;
import org.iaproject.memchat.tools.UserInteractionTools;
import org.iaproject.memchat.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Agent-level event sequence consumed by the same reducer as the TUI. */
class TuiAgentIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void previewsPastedTextAndSendsTheCompletePromptOnlyAfterEnter(boolean useClipboard) throws Exception {
        String payload = "  /quit\r\n\t\u0003\033[A" + "long text ".repeat(1000) + "UNIQUE_TAIL\n  ";
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> submitted = new AtomicReference<>();
        LLmApiClient client = (messages, tools) -> {
            calls.incrementAndGet();
            submitted.set(messages.getLast().content());
            return new LLmApiClient.ChatResponse("Full prompt received", List.of());
        };
        MemoryManager memory = new MemoryManager(new Recall(), new Archive(), (summary, messages) -> summary);
        try (AgentLoop loop = new AgentLoop(1, client, memory);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            RecordingScreen screen = new RecordingScreen();
            var ui = executor.submit(() -> {
                new TuiApplication(loop, "mock", null, System::nanoTime, () -> payload).run(screen, null);
                return null;
            });
            try {
                screen.awaitSize(new TerminalSize(80, 24));
                if (useClipboard) screen.keys.offer(new KeyStroke('v', true, false));
                else screen.keys.addAll(BracketedPasteTest.decodePaste(payload));
                screen.awaitFooter("Texte collé");
                var preview = screen.awaitText(payload.codePointCount(0, payload.length()) + " caractères");
                assertTrue(preview.text().contains("…"));
                assertFalse(preview.text().contains("UNIQUE_TAIL"));
                assertEquals(0, calls.get(), "Pasted newlines and shortcuts must not submit or close the UI");
                screen.keys.offer(new KeyStroke(KeyType.Enter));
                var response = screen.awaitText("Full prompt received");
                assertEquals(payload, submitted.get(), "Whitespace and the hidden tail must reach the model unchanged");
                assertEquals(1, calls.get());
                assertFalse(response.text().contains("UNIQUE_TAIL"), "Long prompt history should also stay compact");
            } finally {
                screen.keys.offer(new KeyStroke(KeyType.EOF));
                ui.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void reportsClipboardFailureWithoutInsertingTheShortcutAsText() throws Exception {
        AtomicReference<String> submitted = new AtomicReference<>();
        LLmApiClient client = (messages, tools) -> {
            submitted.set(messages.getLast().content());
            return new LLmApiClient.ChatResponse("Received", List.of());
        };
        MemoryManager memory = new MemoryManager(new Recall(), new Archive(), (summary, messages) -> summary);
        try (AgentLoop loop = new AgentLoop(1, client, memory);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            RecordingScreen screen = new RecordingScreen();
            var ui = executor.submit(() -> {
                new TuiApplication(loop, "mock", null, System::nanoTime,
                        () -> { throw new IllegalStateException("Clipboard unavailable"); }).run(screen, null);
                return null;
            });
            try {
                screen.keys.offer(new KeyStroke('v', true, false));
                screen.awaitFooter("Clipboard unavailable");
                screen.enter("hello");
                screen.awaitText("Received");
                assertEquals("hello", submitted.get());
            } finally {
                screen.keys.offer(new KeyStroke(KeyType.EOF));
                ui.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void clearsAPastedDraftAndStillRecognizesTypedCommands() throws Exception {
        LLmApiClient client = (messages, tools) -> { throw new AssertionError("Draft must not be submitted"); };
        MemoryManager memory = new MemoryManager(new Recall(), new Archive(), (summary, messages) -> summary);
        try (AgentLoop loop = new AgentLoop(1, client, memory);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            RecordingScreen screen = new RecordingScreen();
            var ui = executor.submit(() -> {
                new TuiApplication(loop, "mock", null, System::nanoTime, () -> "pasted draft").run(screen, null);
                return null;
            });
            try {
                screen.keys.offer(new KeyStroke('v', true, false));
                screen.awaitFooter("Texte collé");
                screen.keys.offer(new KeyStroke('u', true, false));
                screen.awaitText("0 caractères");
                screen.enter("/quit");
                ui.get(5, TimeUnit.SECONDS);
            } finally {
                screen.keys.offer(new KeyStroke(KeyType.EOF));
            }
        }
    }

    @Test
    void appliesTerminalResizesAndRecoversFromATinyWindow() throws Exception {
        LLmApiClient client = (messages, tools) -> { throw new AssertionError("No model call expected"); };
        MemoryManager memory = new MemoryManager(new Recall(), new Archive(), (summary, messages) -> summary);
        try (AgentLoop loop = new AgentLoop(1, client, memory);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            RecordingScreen screen = new RecordingScreen();
            var ui = executor.submit(() -> { new TuiApplication(loop, "mock").run(screen, null); return null; });
            try {
                screen.awaitSize(new TerminalSize(80, 24));
                screen.terminal.setTerminalSize(new TerminalSize(100, 30));
                var enlarged = screen.awaitSize(new TerminalSize(100, 30));
                assertTrue(enlarged.footer().contains("Enter envoyer"));
                screen.terminal.setTerminalSize(new TerminalSize(2, 2));
                screen.awaitSize(new TerminalSize(2, 2));
                screen.terminal.setTerminalSize(new TerminalSize(80, 24));
                assertTrue(screen.awaitSize(new TerminalSize(80, 24)).footer().contains("Enter envoyer"));
            } finally {
                screen.keys.offer(new KeyStroke(KeyType.EOF));
                ui.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void showsAToolFailureEvenWhenTheAgentRecoversAndSucceeds() throws Exception {
        AtomicLong clock = new AtomicLong();
        CountDownLatch allowRecovery = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        LLmApiClient client = (messages, tools) -> {
            if (calls.getAndIncrement() == 0) {
                return new LLmApiClient.ChatResponse(null,
                        List.of(new LLmApiClient.ToolCall("failing-call", "explode", "{}")));
            }
            try {
                if (!allowRecovery.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Recovery not released");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new LLmApiClient.ChatResponse("Recovered answer", List.of());
        };
        MemoryManager memory = new MemoryManager(new Recall(), new Archive(), (summary, messages) -> summary);
        try (AgentLoop loop = new AgentLoop(2, client, memory, new FailingTool());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            loop.subscribe(events::add);
            RecordingScreen screen = new RecordingScreen();
            var ui = executor.submit(() -> {
                new TuiApplication(loop, "mock", null, clock::get).run(screen, "hello");
                return null;
            });
            try {
                screen.awaitFooter("Échec de explode");
                allowRecovery.countDown();
                var frame = screen.awaitText("Recovered answer");
                assertTrue(frame.text().contains("explode [error]"));
                assertTrue(frame.text().contains("tool exploded"));
                clock.set(TimeUnit.SECONDS.toNanos(3));
                var expired = screen.awaitFrame(value -> !value.text().contains("explode [error]")
                        && value.footer().contains("Enter envoyer"), "expired tool notification");
                assertTrue(expired.text().contains("tool exploded"), "The diagnostic must remain in the transcript");
                assertTrue(expired.text().contains("Recovered answer"));
            } finally {
                allowRecovery.countDown();
                screen.keys.offer(new KeyStroke(KeyType.EOF));
                ui.get(5, TimeUnit.SECONDS);
            }
        }
        var completion = events.stream().filter(event -> event.type() == AgentEvent.Type.TOOL_COMPLETED)
                .findFirst().orElseThrow();
        var run = events.stream().filter(event -> event.type() == AgentEvent.Type.RUN_COMPLETED)
                .findFirst().orElseThrow();
        assertEquals(run.runId(), completion.runId());
        assertEquals("failing-call", completion.toolExecution().callId());
        assertFalse(completion.toolExecution().result().success());
        assertEquals(AgentRunState.SUCCEEDED, run.result().status());
    }

    @Test
    void keepsARunningToolVisibleAndExpiresItsSuccessAfterCompletionWithoutMoreInput() throws Exception {
        AtomicLong clock = new AtomicLong();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        LLmApiClient client = (messages, tools) -> calls.getAndIncrement() == 0
                ? new LLmApiClient.ChatResponse(null,
                        List.of(new LLmApiClient.ToolCall("waiting-call", "waitForRelease", "{}")))
                : new LLmApiClient.ChatResponse("Done", List.of());
        MemoryManager memory = new MemoryManager(new Recall(), new Archive(), (summary, messages) -> summary);
        try (AgentLoop loop = new AgentLoop(2, client, memory, new WaitingTool(release));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            RecordingScreen screen = new RecordingScreen();
            var ui = executor.submit(() -> {
                new TuiApplication(loop, "mock", null, clock::get).run(screen, "hello");
                return null;
            });
            try {
                screen.awaitText("waitForRelease [running]");
                clock.set(TimeUnit.SECONDS.toNanos(10));
                screen.terminal.setTerminalSize(new TerminalSize(100, 30));
                assertTrue(screen.awaitSize(new TerminalSize(100, 30)).text().contains("waitForRelease [running]"));
                release.countDown();
                screen.awaitText("waitForRelease [ok]");
                clock.set(TimeUnit.SECONDS.toNanos(13));
                screen.awaitFrame(frame -> frame.text().contains("Done")
                        && !frame.text().contains("waitForRelease"), "expired successful tool activity");
            } finally {
                release.countDown();
                screen.keys.offer(new KeyStroke(KeyType.EOF));
                ui.get(5, TimeUnit.SECONDS);
            }
        }
    }

    public static final class WaitingTool {
        private final CountDownLatch release;
        WaitingTool(CountDownLatch release) { this.release = release; }

        @Tool(description = "Wait for the UI test to release the tool")
        public String waitForRelease() throws InterruptedException {
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Tool not released");
            return "released";
        }
    }

    public static final class FailingTool {
        @Tool(description = "Fail for the UI regression test")
        public String explode() { throw new IllegalStateException("tool exploded"); }
    }

    @Test
    void refusesANewRunWhileTheCancelledModelCallIsStillRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        LLmApiClient client = (messages, tools) -> {
            calls.incrementAndGet();
            started.countDown();
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    interrupted.countDown();
                }
            }
            return new LLmApiClient.ChatResponse("answer", List.of());
        };
        MemoryManager memory = new MemoryManager(new Recall(), new Archive(), (summary, messages) -> summary);
        try (AgentLoop loop = new AgentLoop(1, client, memory);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            RecordingScreen screen = new RecordingScreen();
            TuiApplication app = new TuiApplication(loop, "mock");
            var ui = executor.submit(() -> { app.run(screen, "first"); return null; });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                screen.keys.offer(new KeyStroke('c', true, false));
                screen.awaitFooter("Interruption demandée");
                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                screen.enter("second");
                screen.awaitFooter("Une exécution est déjà en cours");
                assertEquals(1, calls.get());
            } finally {
                release.countDown();
                screen.keys.offer(new KeyStroke(KeyType.EOF));
                ui.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void redisplaysAnIdenticalQuestionAfterThePreviousAnswer() throws Exception {
        String question = "Which file?";
        TuiUserInteraction interaction = new TuiUserInteraction();
        CountDownLatch allowSecondQuestion = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        LLmApiClient client = (messages, tools) -> {
            int call = calls.getAndIncrement();
            if (call == 1) {
                try {
                    if (!allowSecondQuestion.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Second question was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return call < 2
                    ? new LLmApiClient.ChatResponse(null, List.of(new LLmApiClient.ToolCall(
                            "ask-" + call, "askUser", "{\"question\":\"Which file?\"}")))
                    : new LLmApiClient.ChatResponse("done", List.of());
        };
        MemoryManager memory = new MemoryManager(new Recall(), new Archive(), (summary, messages) -> summary);
        try (AgentLoop loop = new AgentLoop(3, client, memory, new UserInteractionTools(interaction));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            RecordingScreen screen = new RecordingScreen();
            TuiApplication app = new TuiApplication(loop, "mock", new TuiRenderer(), interaction);
            var ui = executor.submit(() -> { app.run(screen, "hello"); return null; });
            try {
                screen.awaitFooter(question);
                screen.enter("first.txt");
                screen.awaitFooter("Enter envoyer");
                allowSecondQuestion.countDown();
                screen.awaitFooter(question);
                assertTrue(interaction.waiting());
            } finally {
                allowSecondQuestion.countDown();
                screen.keys.offer(new KeyStroke(KeyType.EOF));
                ui.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static final class RecordingScreen extends TerminalScreen {
        private final DefaultVirtualTerminal terminal;
        private final BlockingQueue<KeyStroke> keys = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> footers = new LinkedBlockingQueue<>();
        private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();

        private record Frame(TerminalSize size, String text, String footer) {}

        RecordingScreen() throws java.io.IOException {
            this(new DefaultVirtualTerminal(new TerminalSize(80, 24)));
        }

        private RecordingScreen(DefaultVirtualTerminal terminal) throws java.io.IOException {
            super(terminal);
            this.terminal = terminal;
        }

        @Override
        public KeyStroke pollInput() {
            return keys.poll();
        }

        @Override
        public void refresh() {
            TerminalSize size = getTerminalSize();
            StringBuilder text = new StringBuilder();
            StringBuilder footer = new StringBuilder();
            for (int y = 0; y < size.getRows(); y++) {
                for (int x = 0; x < size.getColumns(); x++) {
                    char character = getBackCharacter(x, y).getCharacter();
                    text.append(character);
                    if (y == size.getRows() - 1) footer.append(character);
                }
                text.append('\n');
            }
            footers.offer(footer.toString());
            frames.offer(new Frame(size, text.toString(), footer.toString()));
        }

        Frame awaitSize(TerminalSize expected) throws InterruptedException {
            return awaitFrame(frame -> frame.size().equals(expected), "screen size " + expected);
        }

        Frame awaitText(String expected) throws InterruptedException {
            return awaitFrame(frame -> frame.text().contains(expected), "screen text " + expected);
        }

        private Frame awaitFrame(java.util.function.Predicate<Frame> matches, String expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                long remaining = deadline - System.nanoTime();
                Frame frame = remaining > 0 ? frames.poll(remaining, TimeUnit.NANOSECONDS) : null;
                assertNotNull(frame, "Expected " + expected);
                if (matches.test(frame)) return frame;
            }
        }

        void enter(String text) {
            for (char character : text.toCharArray()) keys.offer(new KeyStroke(character, false, false));
            keys.offer(new KeyStroke(KeyType.Enter));
        }

        void awaitFooter(String expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                long remaining = deadline - System.nanoTime();
                String footer = remaining > 0 ? footers.poll(remaining, TimeUnit.NANOSECONDS) : null;
                assertNotNull(footer, "Expected footer containing: " + expected);
                if (footer.contains(expected)) return;
            }
        }
    }

    @Test
    void mockAgentEventsProduceATuiTranscript() {
        LLmApiClient client = (messages, tools) -> new LLmApiClient.ChatResponse("answer", List.of());
        MemoryManager memory = new MemoryManager(new Recall(), new Archive(), (summary, messages) -> summary);
        List<AgentEvent> events = new ArrayList<>();
        try (AgentLoop loop = new AgentLoop(1, client, memory)) {
            loop.subscribe(events::add);
            assertEquals(AgentRunState.SUCCEEDED, loop.run("hello").status());
        }

        TuiState state = TuiReducer.userMessage(TuiState.initial("mock"), "hello");
        for (AgentEvent event : events) state = TuiReducer.reduce(state, event);
        assertEquals(List.of("hello", "answer"), state.transcript().stream()
                .map(TuiState.TranscriptEntry::text).toList());
        assertNull(state.usage());
        assertEquals(TuiState.Status.IDLE, state.status());
    }

    private static final class Recall implements RecallStorage {
        public String add(LLmApiClient.ChatMessage message) { return "id"; }
        public List<Memory.RecallSearchResult> search(String query, int limit) { return List.of(); }
    }

    private static final class Archive implements ArchivalMemory {
        public String insert(String content) { return "id"; }
        public List<Memory.MemorySearchResult> search(String query, int limit) { return List.of(); }
        public void update(String id, String content) { }
        public void delete(String id) { }
        public String get(String id) { return null; }
    }
}
