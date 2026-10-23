package org.iaproject.memchat.agent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** A cooperative cancellation signal owned by one run, independent of its worker thread. */
public final class AgentCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** May be called from any thread. Does not interrupt a thread or reset for later runs. */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Agent execution cancelled");
        }
    }
}
