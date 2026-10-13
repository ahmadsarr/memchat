package org.iaproject.memchat.tools;

import org.iaproject.memchat.UserInteraction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserInteractionToolsTest {
    @Test
    void askUserDelegatesToTheInteraction() {
        UserInteraction interaction = new UserInteraction() {
            public String read() { return ""; }
            public String ask(String message) { return "answer"; }
            public void display(String answer) { }
        };

        var result = new UserInteractionTools(interaction).askUser("question");

        assertEquals(UserInteractionTools.InteractionType.ASK_USER, result.type());
        assertEquals("answer", result.message());
    }

    @Test
    void finishReturnsTheAnswerWithoutRenderingIt() {
        boolean[] displayed = {false};
        UserInteraction interaction = new UserInteraction() {
            public String read() { return ""; }
            public String ask(String message) { return ""; }
            public void display(String answer) { displayed[0] = true; }
        };

        String answer = new UserInteractionTools(interaction).finish("done");

        assertEquals("done", answer);
        assertEquals(false, displayed[0]);
    }
}
