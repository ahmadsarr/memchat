package org.iaproject.memchat.agent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentEventDispatcher implements AutoCloseable {
    private final List<AgentListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("memchat-events-", 0).factory());
    private final ThreadLocal<Boolean> delivering = ThreadLocal.withInitial(() -> false);
    private final Object pendingMonitor = new Object();
    private int pendingEvents;
    private boolean closed;

    public AutoCloseable subscription(AgentListener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (pendingMonitor) {
            requireOpen();
            listeners.add(listener);
        }
        return () -> listeners.remove(listener);
    }

    public void publish(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (pendingMonitor) {
            requireOpen();
            for (AgentListener listener : listeners) {
                pendingEvents++;
                try {
                    executor.execute(() -> {
                        delivering.set(true);
                        try {
                            listener.onEvent(event);
                        } catch (RuntimeException ignored) {
                            // A listener must not affect the agent execution.
                        } finally {
                            delivering.remove();
                            eventCompleted();
                        }
                    });
                } catch (RuntimeException exception) {
                    eventCompleted();
                    throw exception;
                }
            }
        }
    }

    public void awaitPendingEvents() throws InterruptedException {
        if (delivering.get()) {
            throw new IllegalStateException("A listener cannot wait for its own event delivery");
        }
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

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Event dispatcher is closed");
        }
    }

    @Override
    public void close() {
        synchronized (pendingMonitor) {
            closed = true;
            executor.shutdown();
            listeners.clear();
        }
        // A callback may initiate shutdown, but cannot join its own worker.
        if (!delivering.get()) {
            boolean interrupted = Thread.interrupted();
            try {
                while (true) {
                    try {
                        awaitPendingEvents();
                        break;
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                executor.close();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
