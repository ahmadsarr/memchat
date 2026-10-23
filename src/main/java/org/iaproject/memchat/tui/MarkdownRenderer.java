package org.iaproject.memchat.tui;

import java.util.regex.Pattern;

/** Small Markdown-to-terminal renderer for the common chat syntax. */
public final class MarkdownRenderer {
    private MarkdownRenderer() {
    }

    public static String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        boolean codeBlock = false;
        for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
            if (line.trim().startsWith("```")) {
                codeBlock = !codeBlock;
                result.append("  ").append(codeBlock ? "┌ code ─────" : "└──────────").append('\n');
                continue;
            }
            String rendered = line;
            if (!codeBlock) {
                rendered = ansiReplace(rendered, "\\*\\*(.+?)\\*\\*", "\033[1m", "\033[0m");
                rendered = ansiReplace(rendered, "(?<!`)`([^`]+)`(?!`)", "\033[7m", "\033[0m");
            }
            result.append(codeBlock ? "  │ " : "").append(rendered).append('\n');
        }
        return result.toString().stripTrailing();
    }

    private static String ansiReplace(String value, String regex, String prefix, String suffix) {
        return Pattern.compile(regex).matcher(value)
                .replaceAll(match -> prefix + match.group(1) + suffix);
    }
}
