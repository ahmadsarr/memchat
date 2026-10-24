package org.iaproject.memchat.tui;

/** Keeps a stable top line while reading history, and follows appended lines only at the bottom. */
final class TranscriptViewport {
    private int totalLines;
    private int height = 1;
    private int top;
    private boolean following = true;

    void update(int totalLines, int height) {
        this.totalLines = Math.max(0, totalLines);
        this.height = Math.max(1, height);
        top = following ? maximumTop() : Math.min(top, maximumTop());
        if (top == maximumTop()) following = true;
    }

    void scroll(int delta) {
        top = (int) Math.max(0L, Math.min((long) maximumTop(), (long) top + delta));
        following = top == maximumTop();
    }

    void pageUp() { scroll(-Math.max(1, height - 1)); }
    void pageDown() { scroll(Math.max(1, height - 1)); }
    void first() { top = 0; following = maximumTop() == 0; }
    void last() { top = maximumTop(); following = true; }
    int top() { return top; }
    boolean following() { return following; }
    int totalLines() { return totalLines; }
    private int maximumTop() { return Math.max(0, totalLines - height); }
}
