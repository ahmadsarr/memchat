package org.iaproject.memchat;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleUserInteractionTest {
    @Test
    void readsUserInputAndWritesInteractionMessages() {
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        System.setOut(new PrintStream(output));
        try {
            var interaction = new ConsoleUserInteraction(
                    new Scanner("read value\nanswer\n")
            );

            assertEquals("read value", interaction.read());
            assertEquals("answer", interaction.ask("question"));
            interaction.display("done");
            assertTrue(output.toString().contains("question"));
            assertTrue(output.toString().contains("done"));
        } finally {
            System.setOut(previous);
        }
    }
}
