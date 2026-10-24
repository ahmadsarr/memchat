package org.iaproject.memchat.tui;

import com.googlecode.lanterna.TerminalTextUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InputPreviewTest {
    @Test
    void displaysABoundedSingleLineWithoutChangingTheOriginalText() {
        var text = new StringBuilder("  hello\n\t\u0003" + "world".repeat(1000));
        String original = text.toString();
        String preview = InputPreview.render(text, 20);
        assertTrue(preview.contains("↵⇥�"));
        assertTrue(preview.endsWith("…"));
        assertTrue(TerminalTextUtils.getColumnWidth(preview) <= 20);
        assertEquals(original, text.toString());
    }

    @Test
    void respectsWideCharactersAndDoesNotSplitSurrogatePairs() {
        String preview = InputPreview.render("界🙂界🙂".repeat(10), 9);
        assertTrue(TerminalTextUtils.getColumnWidth(preview) <= 9);
        assertFalse(preview.matches(".*[\\uD800-\\uDBFF]…$"));
        assertEquals("", InputPreview.render("hello", 0));
        assertEquals("…", InputPreview.render("hello", 1));
        assertEquals("hello", InputPreview.render("hello", 5));
    }
}
