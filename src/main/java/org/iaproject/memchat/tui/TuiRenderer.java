package org.iaproject.memchat.tui;

import java.util.ArrayList;
import java.util.List;

/** Renders a complete snapshot without writing to a terminal. */
public final class TuiRenderer {
    public String initial(TuiState state) {
        return "\nBuild · " + state.model() + " · ready\n";
    }

    public String userMessage(String message) {
        return "\nUser >\n" + MarkdownRenderer.render(message) + "\n";
    }

    public String assistantMessage(String message) {
        return "\n" + MarkdownRenderer.render(message) + "\n";
    }

    public String toolMessage(String tool) {
        return "\n  • " + tool + "\n";
    }

    public String errorMessage(String message) {
        return "\n\033[31m✗ " + message + "\033[0m\n";
    }

    public String completion(TuiState state, long durationMillis) {
        String duration = String.format("%.1fs", durationMillis / 1000.0);
        return "\nBuild · " + state.model() + " · " + duration + " · n/a tok/s\n";
    }

    public String render(TuiState state, int width, int height) {
        return initial(state);
    }

    private static List<String> transcriptLines(TuiState state, int width) {
        List<String> lines = new ArrayList<>();
        lines.add(" TRANSCRIPT");
        for (TuiState.TranscriptEntry entry : state.transcript()) {
            String prefix = switch (entry.role()) {
                case USER -> "User > ";
                case ASSISTANT -> "Agent > ";
                case ERROR -> "Error > ";
                case SYSTEM -> "System > ";
            };
            for (String line : MarkdownRenderer.render(entry.text()).split("\n", -1)) {
                lines.addAll(wrap(prefix + line, width));
                prefix = "       ";
            }
        }
        return lines;
    }

    private static List<String> activityLines(TuiState state, int width) {
        List<String> lines = new ArrayList<>();
        lines.add(" TOOLS");
        for (TuiState.ToolActivity activity : state.activities()) {
            String duration = activity.durationMillis() < 0 ? "..." : activity.durationMillis() + "ms";
            lines.add(truncate(" " + icon(activity.status()) + " " + activity.name()
                    + " [" + duration + "]", width));
            lines.add(truncate("   " + activity.arguments(), width));
        }
        if (state.activities().isEmpty()) lines.add(" (none)");
        return lines;
    }

    private static String icon(TuiState.ToolStatus status) {
        return switch (status) {
            case RUNNING -> "…";
            case OK -> "✓";
            case ERROR -> "✗";
        };
    }

    private static List<String> columns(List<String> left, List<String> right,
                                        int leftWidth, int rightWidth, int height) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < height; i++) {
            String l = i < left.size() ? truncate(left.get(i), leftWidth) : "";
            String r = i < right.size() ? truncate(right.get(i), rightWidth) : "";
            result.add(pad(l, leftWidth, ' ') + " │" + pad(r, rightWidth, ' '));
        }
        return result;
    }

    private static List<String> wrap(String value, int width) {
        List<String> lines = new ArrayList<>();
        if (value.length() <= width) return List.of(value);
        String remaining = value;
        while (remaining.length() > width) {
            int split = remaining.lastIndexOf(' ', width);
            if (split <= 0) split = width;
            lines.add(remaining.substring(0, split));
            remaining = remaining.substring(split).stripLeading();
        }
        lines.add(remaining);
        return lines;
    }

    private static String truncate(String value, int width) {
        if (value == null) return "";
        if (value.length() <= width) return value;
        return width < 2 ? value.substring(0, width) : value.substring(0, width - 1) + "…";
    }

    private static String pad(String value, int width, char fill) {
        String result = truncate(value, width);
        return result + String.valueOf(fill).repeat(Math.max(0, width - result.length()));
    }
}
