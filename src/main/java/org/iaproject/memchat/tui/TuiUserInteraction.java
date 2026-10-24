package org.iaproject.memchat.tui;

import org.iaproject.memchat.UserInteraction;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public final class TuiUserInteraction implements UserInteraction {
    private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
    private volatile Consumer<String> questionConsumer = question -> {};
    private volatile boolean waiting;

    public void onQuestion(Consumer<String> consumer) {
        questionConsumer = Objects.requireNonNull(consumer);
    }

    public boolean answerIfWaiting(String answer) {
        if (!waiting) return false;
        answers.offer(answer);
        return true;
    }

    public boolean waiting() {
        return waiting;
    }

    @Override
    public String read() {
        return null;
    }

    @Override
    public String ask(String agentMessage) {
        waiting = true;
        questionConsumer.accept(agentMessage);
        try {
            return answers.take();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("User interaction interrupted", exception);
        } finally {
            waiting = false;
        }
    }

    @Override
    public void display(String answer) {
        questionConsumer.accept(answer);
    }
}
