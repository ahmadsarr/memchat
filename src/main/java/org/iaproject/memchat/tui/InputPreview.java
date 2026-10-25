package org.iaproject.memchat.tui;

import com.googlecode.lanterna.TerminalTextUtils;

/** A bounded, single-line view; the original prompt is never modified. */
final class InputPreview {
    private InputPreview() {}

    static String render(CharSequence text, int columns) {
        if (columns <= 0) return "";
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = Character.codePointAt(text, offset);
            int next = offset + Character.charCount(codePoint);
            String visible = switch (codePoint) {
                case '\n', '\r' -> "↵";
                case '\t' -> "⇥";
                default -> Character.isISOControl(codePoint) ? "�" : new String(Character.toChars(codePoint));
            };
            int width = TerminalTextUtils.getColumnWidth(visible);
            if (used + width > columns || (next < text.length() && used + width >= columns)) {
                if (used < columns) result.append('…');
                break;
            }
            result.append(visible);
            used += width;
            offset = next;
        }
        return result.toString();
    }
}
