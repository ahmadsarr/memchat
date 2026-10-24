package org.iaproject.memchat.tui;

import java.util.concurrent.FutureTask;

/** Cancellation completes a Future before its running code necessarily exits. */
final class TuiRunTask extends FutureTask<Void> {
    private volatile boolean exited;

    TuiRunTask(Runnable action) {
        super(action, null);
    }

    @Override
    public void run() {
        try {
            super.run();
        } finally {
            exited = true;
        }
    }

    boolean hasExited() {
        return exited;
    }
}
