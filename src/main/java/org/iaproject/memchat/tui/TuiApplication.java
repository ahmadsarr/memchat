package org.iaproject.memchat.tui;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.graphics.TextGraphics;
import org.iaproject.memchat.agent.AgentEvent;
import org.iaproject.memchat.agent.AgentLoop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/** Full-screen Lanterna UI with a stable editor at the bottom of the screen. */
public final class TuiApplication {
    private final AgentLoop agent;
    private final String model;
    private final TuiUserInteraction interaction;
    private TuiState state;
    private String input = "";
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
        this.agent = agent;
        this.model = model;
        this.interaction = interaction;
        this.state = TuiState.initial(model);
    }

    public void run(String initialInstruction) throws IOException {
        try (Terminal terminal = new DefaultTerminalFactory().createTerminal()) {
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
                    dirty = true;
                }
                String currentQuestion;
                while ((currentQuestion = questions.poll()) != null) {
                    notice = currentQuestion;
                    dirty = true;
                }
                for (int keyCount = 0; keyCount < 32; keyCount++) {
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

    private void handleKey(KeyStroke key, AtomicReference<TuiRunTask> currentRun,
                           ExecutorService executor) {
        if (key.isCtrlDown() && key.getKeyType() == KeyType.Character) {
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
            if (!input.isEmpty()) input = input.substring(0, input.length() - 1);
            return;
        }
        if (key.getKeyType() == KeyType.Enter) {
            if (key.isAltDown()) {
                input += "\n";
            } else {
                submitInput(currentRun, executor);
            }
            return;
        }
        if (key.getKeyType() == KeyType.Character && key.getCharacter() != null
                && !Character.isISOControl(key.getCharacter())) {
            input += key.getCharacter();
        }
    }

    private void submitInput(AtomicReference<TuiRunTask> currentRun, ExecutorService executor) {
        String value = input.strip();
        input = "";
        if (value.isEmpty()) return;
        if (interaction != null && interaction.answerIfWaiting(value)) {
            questionDone();
            return;
        }
        SlashCommand command = SlashCommand.parse(value);
        if (command.kind() != SlashCommand.Kind.NONE) {
            handleCommand(command);
            return;
        }
        if (currentRun.get() != null && !currentRun.get().hasExited()) {
            notice = "Une exécution est déjà en cours";
            return;
        }
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
            case HELP -> notice = "Commandes: /help /clear /model /quit · Alt+Entrée ajoute une ligne";
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
        input = history.get(historyIndex);
    }

    private void nextHistory() {
        if (history.isEmpty()) return;
        historyIndex = Math.min(history.size(), historyIndex + 1);
        input = historyIndex == history.size() ? "" : history.get(historyIndex);
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
        graphics.putString(0, height - 3, "╭─ input ─" + "─".repeat(Math.max(0, width - 11)));
        graphics.putString(0, height - 2, "│ " + truncate(input, Math.max(0, width - 2)));
        graphics.putString(0, height - 1, truncate(notice == null ? "Enter envoyer · Ctrl+C interrompre · /help" : notice, width));
        screen.setCursorPosition(new TerminalPosition(
                Math.min(width - 1, 2 + input.length()), height - 2));
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
            for (String part : MarkdownRenderer.render(entry.text()).split("\\n", -1)) {
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
