package org.iaproject.memchat.tui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TuiUserInteractionTest {
    @Test
    void failedQuestionDeliveryResetsWaitingAndAllowsRetry() {
        var interaction = new TuiUserInteraction();
        var failure = new IllegalStateException("delivery failed");
        interaction.onQuestion(question -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> interaction.ask("question")));
        assertFalse(interaction.waiting());
        assertFalse(interaction.answerIfWaiting("stale answer"));
        interaction.onQuestion(question -> interaction.answerIfWaiting("fresh answer"));
        assertEquals("fresh answer", interaction.ask("retry"));
        assertFalse(interaction.waiting());
    }
}
