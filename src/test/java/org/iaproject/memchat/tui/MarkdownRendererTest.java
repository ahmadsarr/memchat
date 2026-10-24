package org.iaproject.memchat.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownRendererTest {
    @Test
    void preservesReplacementMetacharactersInFormattedText() {
        assertEquals("\033[1m$5\033[0m", MarkdownRenderer.render("**$5**"));
        assertEquals("\033[7mC:\\temp\033[0m", MarkdownRenderer.render("`C:\\temp`"));
        assertEquals("\033[7m${HOME}\\bin\033[0m", MarkdownRenderer.render("`${HOME}\\bin`"));
    }
}
