package org.iaproject.memchat.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptViewportTest {
    @Test
    void holdsTheReadingPositionWhenNewLinesArriveAndResumesFollowingAtTheBottom() {
        var viewport = new TranscriptViewport();
        viewport.update(100, 20);
        assertEquals(80, viewport.top());
        viewport.pageUp();
        assertEquals(61, viewport.top());
        assertFalse(viewport.following());
        viewport.update(130, 20);
        assertEquals(61, viewport.top());
        viewport.last();
        assertEquals(110, viewport.top());
        viewport.update(131, 20);
        assertEquals(111, viewport.top());
        assertTrue(viewport.following());
    }

    @Test
    void clampsPositionAfterResizeOrContentRemovalWithoutLosingTheReadingPositionUnnecessarily() {
        var viewport = new TranscriptViewport();
        viewport.update(100, 20);
        viewport.scroll(-10);
        viewport.update(98, 20);
        assertEquals(70, viewport.top());
        viewport.update(98, 40);
        assertEquals(58, viewport.top());
        assertTrue(viewport.following());
        viewport.update(0, 0);
        assertEquals(0, viewport.top());
        assertTrue(viewport.following());
    }

    @Test
    void boundsLargeScrollsAndReturnsToFollowingThroughPageDown() {
        var viewport = new TranscriptViewport();
        viewport.update(40, 10);
        viewport.scroll(Integer.MIN_VALUE);
        assertEquals(0, viewport.top());
        viewport.scroll(Integer.MAX_VALUE);
        assertEquals(30, viewport.top());
        viewport.first();
        for (int i = 0; i < 4; i++) viewport.pageDown();
        assertEquals(30, viewport.top());
        assertTrue(viewport.following());
    }
}
