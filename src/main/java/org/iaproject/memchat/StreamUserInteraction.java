package org.iaproject.memchat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** User interaction backed by explicit streams, suitable for CLI and E2E runs. */
public final class StreamUserInteraction implements UserInteraction {
    private final BufferedReader input;
    private final PrintStream output;

    public StreamUserInteraction(InputStream input, PrintStream output) {
        this.input = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(input), StandardCharsets.UTF_8));
        this.output = Objects.requireNonNull(output);
    }

    @Override
    public String read() {
        prompt("User >");
        try {
            return input.readLine();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read user input", exception);
        }
    }

    @Override
    public String ask(String agentMessage) {
        output.print("Agent >" + agentMessage);
        prompt("User >");
        return readLine();
    }

    @Override
    public void display(String answer) {
        output.print("Agent >" + answer);
        output.flush();
    }

    private String readLine() {
        try {
            return input.readLine();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read user input", exception);
        }
    }

    private void prompt(String prompt) {
        output.print(prompt);
        output.flush();
    }
}
