package org.iaproject.memchat.tui;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.StreamBasedTerminal;
import com.googlecode.lanterna.terminal.ansi.UnixLikeTerminal;
import com.googlecode.lanterna.terminal.swing.TerminalEmulatorDeviceConfiguration;
import com.googlecode.lanterna.graphics.TextGraphics;
import org.iaproject.memchat.agent.AgentEvent;
import org.iaproject.memchat.agent.AgentLoop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/** Full-screen Lanterna UI with a stable editor at the bottom of the screen. */
public final class TuiApplication {
    private static final long TOOL_DISPLAY_NANOS = TimeUnit.SECONDS.toNanos(3);
    private final AgentLoop agent;
    private final String model;
    private final TuiUserInteraction interaction;
    private final LongSupplier nanoTime;
    private final Supplier<String> clipboard;
    private final Map<TuiState.ToolActivity, Long> completedTools = new IdentityHashMap<>();
    private String transientToolNotice;
    private long toolNoticeStartedAt;
    private TuiState state;
    private final StringBuilder input = new StringBuilder();
    private boolean inputWasPasted;
    private boolean receivingPaste;
    private final List<String> history = new ArrayList<>();
    private int historyIndex;
    private String notice;
    private boolean closing;
    private boolean interruptRequested;

    public TuiApplication(AgentLoop agent, String model) {
        this(agent, model, null);
    }

    public TuiApplication(AgentLoop agent, String model, TuiRenderer ignoredRenderer,
                          TuiUserInteraction interaction) {
        this(agent, model, interaction);
    }

    private TuiApplication(AgentLoop agent, String model, TuiUserInteraction interaction) {
        this(agent, model, interaction, System::nanoTime);
    }

    TuiApplication(AgentLoop agent, String model, TuiUserInteraction interaction, LongSupplier nanoTime) {
        this(agent, model, interaction, nanoTime, TuiClipboard::readText);
    }

    TuiApplication(AgentLoop agent, String model, TuiUserInteraction interaction, LongSupplier nanoTime,
                   Supplier<String> clipboard) {
        this.agent = agent;
        this.model = model;
        this.interaction = interaction;
        this.nanoTime = nanoTime;
        this.clipboard = clipboard;
        this.state = TuiState.initial(model);
    }

    public void run(String initialInstruction) throws IOException {
        var factory = new DefaultTerminalFactory(System.out, System.in, StandardCharsets.UTF_8)
                .setUnixTerminalCtrlCBehaviour(UnixLikeTerminal.CtrlCBehaviour.TRAP)
                .setInputTimeout(1)
                .setTerminalEmulatorDeviceConfiguration(TerminalEmulatorDeviceConfiguration.getDefault()
                        .withClipboardAvailable(false));
        try (Terminal terminal = factory.createTerminal();
             BracketedPaste paste = terminal instanceof StreamBasedTerminal stream
                     ? BracketedPaste.enable(stream.getInputDecoder(), System.out) : null) {
            run(new TerminalScreen(terminal), initialInstruction);
        }
    }

    // Accept an in-memory screen for regression tests without a physical terminal.
    void run(Screen screen, String initialInstruction) throws IOException {
        screen.startScreen();
        BlockingQueue<AgentEvent> events = new LinkedBlockingQueue<>();
        BlockingQueue<String> questions = new LinkedBlockingQueue<>();
        if (interaction != null) {
            interaction.onQuestion(questions::offer);
        }

        ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        AtomicReference<TuiRunTask> currentRun = new AtomicReference<>();
        AutoCloseable subscription = agent.subscribe(events::offer);
        try {
            if (initialInstruction != null && !initialInstruction.isBlank()) {
                submit(initialInstruction, currentRun, executor);
            }
            boolean dirty = true;
            TerminalSize lastSize = null;
            while (!closing) {
                TerminalSize newSize = screen.doResizeIfNecessary();
                if (newSize != null) dirty = true;
                AgentEvent event;
                while ((event = events.poll()) != null) {
                    state = TuiReducer.reduce(state, event);
                    notice = state.notice();
                    if (event.type() == AgentEvent.Type.TOOL_COMPLETED && event.toolExecution() != null
                            && event.toolExecution().result() != null && !event.toolExecution().result().success()) {
                        transientToolNotice = notice;
                        toolNoticeStartedAt = nanoTime.getAsLong();
                    }
                    dirty = true;
                }
                String currentQuestion;
                while ((currentQuestion = questions.poll()) != null) {
                    notice = currentQuestion;
                    dirty = true;
                }
                for (int keyCount = 0; keyCount < (receivingPaste ? 4096 : 32); keyCount++) {
                    KeyStroke key = screen.pollInput();
                    if (key == null) break;
                    handleKey(key, currentRun, executor);
                    dirty = true;
                    if (closing) break;
                }
                TerminalSize currentSize = screen.getTerminalSize();
                if (!currentSize.equals(lastSize)) {
                    lastSize = currentSize;
                    dirty = true;
                }
                dirty |= expireToolDisplay();
                if (dirty) {
                    draw(screen);
                    dirty = false;
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            closing = true;
        } finally {
            TuiRunTask running = currentRun.get();
            if (running != null && !running.hasExited()) {
                running.cancel(true);
            }
            try {
                subscription.close();
            } catch (Exception ignored) {
                // Terminal restoration must continue.
            }
            executor.close();
            screen.close();
        }
    }

    private boolean expireToolDisplay() {
        long now = nanoTime.getAsLong();
        boolean changed = false;
        completedTools.keySet().retainAll(state.activities());
        List<TuiState.ToolActivity> visible = new ArrayList<>();
        for (var activity : state.activities()) {
            if (activity.status() == TuiState.ToolStatus.RUNNING) {
                visible.add(activity);
            } else {
                long completedAt = completedTools.computeIfAbsent(activity, ignored -> now);
                if (now - completedAt < TOOL_DISPLAY_NANOS) {
                    visible.add(activity);
                } else {
                    completedTools.remove(activity);
                    changed = true;
                }
            }
        }
        if (changed) state = state.withActivities(visible);
        if (transientToolNotice != null && now - toolNoticeStartedAt >= TOOL_DISPLAY_NANOS) {
            if (Objects.equals(state.notice(), transientToolNotice)) state = state.withNotice(null);
            if (Objects.equals(notice, transientToolNotice) && (interaction == null || !interaction.waiting())) {
                notice = null;
            }
            transientToolNotice = null;
            changed = true;
        }
        return changed;
    }

    private void handleKey(KeyStroke key, AtomicReference<TuiRunTask> currentRun,
                           ExecutorService executor) {
        if (key instanceof BracketedPaste.Event paste) {
            switch (paste.kind) {
                case START -> {
                    receivingPaste = true;
                    notice = "Collage en cours…";
                }
                case TEXT -> appendPaste(paste.text);
                case END -> {
                    receivingPaste = false;
                    notice = "Texte collé · Entrée pour envoyer";
                }
            }
            return;
        }
        if (key.getKeyType() == KeyType.Insert && key.isShiftDown()) {
            pasteClipboard();
            return;
        }
        if (key.isCtrlDown() && key.getKeyType() == KeyType.Character) {
            if (Character.toLowerCase(key.getCharacter()) == 'v') {
                pasteClipboard();
                return;
            }
            if (key.getCharacter() == 'u') {
                input.setLength(0);
                inputWasPasted = false;
                notice = null;
                return;
            }
            if (key.getCharacter() == 'c') {
                TuiRunTask running = currentRun.get();
                if (running != null && !running.hasExited() && !interruptRequested) {
                    interruptRequested = true;
                    running.cancel(true);
                    notice = "Interruption demandée (Ctrl+C pour quitter)";
                } else {
                    closing = true;
                }
                return;
            }
            if (key.getCharacter() == 'd') {
                closing = true;
                return;
            }
        }
        if (key.getKeyType() == KeyType.EOF) {
            closing = true;
            return;
        }
        if (key.getKeyType() == KeyType.ArrowUp) {
            previousHistory();
            return;
        }
        if (key.getKeyType() == KeyType.ArrowDown) {
            nextHistory();
            return;
        }
        if (key.getKeyType() == KeyType.Backspace) {
            if (!input.isEmpty()) input.delete(input.offsetByCodePoints(input.length(), -1), input.length());
            if (input.isEmpty()) inputWasPasted = false;
            return;
        }
        if (key.getKeyType() == KeyType.Enter) {
            if (key.isAltDown()) {
                input.append('\n');
            } else {
                submitInput(currentRun, executor);
            }
            return;
        }
        if (key.getKeyType() == KeyType.Character && key.getCharacter() != null
                && !Character.isISOControl(key.getCharacter())) {
            input.append(key.getCharacter());
        }
    }

    private void appendPaste(String text) {
        if (text != null && !text.isEmpty()) {
            input.append(text);
            inputWasPasted = true;
        }
    }

    private void pasteClipboard() {
        try {
            String text = clipboard.get();
            if (text == null || text.isEmpty()) {
                notice = "Presse-papiers vide";
                return;
            }
            appendPaste(text);
            notice = "Texte collé · Entrée pour envoyer";
        } catch (RuntimeException exception) {
            notice = exception.getMessage();
        }
    }

    private void submitInput(AtomicReference<TuiRunTask> currentRun, ExecutorService executor) {
        String value = input.toString();
        boolean literalPrompt = inputWasPasted;
        if (value.isBlank()) return;
        if (interaction != null && interaction.answerIfWaiting(value)) {
            input.setLength(0);
            inputWasPasted = false;
            questionDone();
            return;
        }
        SlashCommand command = SlashCommand.parse(value);
        if (!literalPrompt && command.kind() != SlashCommand.Kind.NONE) {
            input.setLength(0);
            inputWasPasted = false;
            handleCommand(command);
            return;
        }
        if (currentRun.get() != null && !currentRun.get().hasExited()) {
            notice = "Une exécution est déjà en cours · saisie conservée";
            return;
        }
        input.setLength(0);
        inputWasPasted = false;
        submit(value, currentRun, executor);
    }

    private void submit(String instruction, AtomicReference<TuiRunTask> currentRun,
                        ExecutorService executor) {
        history.remove(instruction);
        history.add(instruction);
        historyIndex = history.size();
        interruptRequested = false;
        notice = null;
        state = TuiReducer.userMessage(state, instruction);
        TuiRunTask task = new TuiRunTask(() -> agent.run(instruction));
        currentRun.set(task);
        executor.execute(task);
    }

    private void handleCommand(SlashCommand command) {
        switch (command.kind()) {
            case QUIT -> closing = true;
            case HELP -> notice = "Commandes: /help /clear /model /quit · Ctrl+V coller · Ctrl+U effacer · Alt+Entrée ajouter une ligne";
            case CLEAR -> state = TuiReducer.clear(state);
            case MODEL -> notice = "Modèle: " + (command.argument().isBlank() ? model : command.argument());
            case UNKNOWN -> notice = "Commande inconnue: /" + command.argument();
            case NONE -> { }
        }
    }

    private void questionDone() {
        notice = null;
    }

    private void previousHistory() {
        if (history.isEmpty()) return;
        historyIndex = Math.max(0, historyIndex - 1);
        input.setLength(0);
        input.append(history.get(historyIndex));
        inputWasPasted = true;
    }

    private void nextHistory() {
        if (history.isEmpty()) return;
        historyIndex = Math.min(history.size(), historyIndex + 1);
        input.setLength(0);
        if (historyIndex < history.size()) input.append(history.get(historyIndex));
        inputWasPasted = !input.isEmpty();
    }

    private void draw(Screen screen) throws IOException {
        TerminalSize size = screen.getTerminalSize();
        int width = size.getColumns();
        int height = size.getRows();
        if (width <= 0 || height <= 0) {
            screen.setCursorPosition(null);
            screen.refresh();
            return;
        }
        TextGraphics graphics = screen.newTextGraphics();
        graphics.setForegroundColor(TextColor.ANSI.WHITE);

        // Update the back buffer in one pass. Lanterna then refreshes only the
        // changed cells, instead of clearing the physical terminal every tick.
        String blankLine = " ".repeat(Math.max(0, width));
        for (int row = 0; row < height; row++) {
            graphics.putString(0, row, blankLine);
        }

        if (width < 3 || height < 5) {
            graphics.putString(0, 0, truncate("Terminal trop petit", width));
            screen.setCursorPosition(null);
            screen.refresh();
            return;
        }

        List<String> lines = conversationLines(width);
        int footerHeight = 5;
        int start = Math.max(0, lines.size() - Math.max(1, height - footerHeight));
        int y = 0;
        for (int i = start; i < lines.size() && y < height - footerHeight; i++) {
            graphics.putString(0, y++, truncate(lines.get(i), width));
        }
        String status = "Build · " + model + " · " + state.status().name().toLowerCase();
        graphics.setForegroundColor(TextColor.ANSI.CYAN);
        graphics.putString(0, height - 4, truncate(status, width));
        graphics.setForegroundColor(TextColor.ANSI.WHITE);
        String label = "╭─ input · " + input.codePointCount(0, input.length()) + " caractères ─";
        graphics.putString(0, height - 3, truncate(label, width));
        String preview = InputPreview.render(input, Math.max(0, width - 2));
        graphics.putString(0, height - 2, "│ " + preview);
        graphics.putString(0, height - 1, truncate(notice == null ? "Enter envoyer · Ctrl+C interrompre · /help" : notice, width));
        screen.setCursorPosition(new TerminalPosition(
                Math.min(width - 1, 2 + TerminalTextUtils.getColumnWidth(preview)), height - 2));
        screen.refresh();
    }

    private List<String> conversationLines(int width) {
        List<String> lines = new ArrayList<>();
        for (TuiState.TranscriptEntry entry : state.transcript()) {
            String prefix = switch (entry.role()) {
                case USER -> "User > ";
                case ASSISTANT -> "Agent > ";
                case ERROR -> "Error > ";
                case SYSTEM -> "System > ";
            };
            String displayedText = entry.role() == TuiState.Role.USER && entry.text().length() > 500
                    ? InputPreview.render(entry.text(), 300) + " [" + entry.text().length() + " caractères]"
                    : entry.text();
            for (String part : MarkdownRenderer.render(displayedText).split("\\n", -1)) {
                lines.addAll(wrap(prefix + stripAnsi(part), width));
                prefix = "        ";
            }
        }
        for (TuiState.ToolActivity activity : state.activities()) {
            lines.add("  • " + activity.name() + " [" + activity.status().name().toLowerCase() + "]");
        }
        return lines;
    }

    private static List<String> wrap(String value, int width) {
        List<String> result = new ArrayList<>();
        if (value.length() <= width) return List.of(value);
        for (int start = 0; start < value.length(); start += width) {
            result.add(value.substring(start, Math.min(value.length(), start + width)));
        }
        return result;
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\\033\\[[;\\d]*m", "");
    }

    private static String truncate(String value, int width) {
        if (width <= 0) return "";
        return value.length() <= width ? value : value.substring(0, Math.max(0, width - 1)) + "…";
    }
}
