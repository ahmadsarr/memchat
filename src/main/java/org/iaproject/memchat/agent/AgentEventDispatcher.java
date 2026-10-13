package org.iaproject.memchat.agent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentEventDispatcher implements AutoCloseable {
    private final List<AgentListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object pendingMonitor = new Object();
    private int pendingEvents;

    public AutoCloseable subscription(AgentListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void publish(AgentEvent event) {
        listeners.forEach(l -> {
            synchronized (pendingMonitor) {
                pendingEvents++;
            }
            try {
                executor.execute(() -> {
                    try {
                        l.onEvent(event);
                    } catch (RuntimeException ignored) {
                        // A listener must not affect the agent execution.
                    } finally {
                        eventCompleted();
                    }
                });
            } catch (RuntimeException exception) {
                eventCompleted();
                throw exception;
            }
        });
    }

    public void awaitPendingEvents() throws InterruptedException {
        synchronized (pendingMonitor) {
            while (pendingEvents > 0) {
                pendingMonitor.wait();
            }
        }
    }

    private void eventCompleted() {
        synchronized (pendingMonitor) {
            pendingEvents--;
            if (pendingEvents == 0) {
                pendingMonitor.notifyAll();
            }
        }
    }

    @Override
    public void close() {
        try {
            awaitPendingEvents();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        executor.close();
        listeners.clear();
    }
}
