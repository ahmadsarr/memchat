package org.iaproject.memchat.tui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TuiRunTaskTest {
    @Test
    void cancellationDoesNotReportExitUntilTheActionReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        TuiRunTask task = new TuiRunTask(() -> {
            started.countDown();
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    interrupted.countDown();
                }
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    exited.countDown();
                }
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertTrue(task.cancel(true));
                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                assertTrue(task.isDone());
                assertFalse(task.hasExited());
            } finally {
                release.countDown();
            }
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertTrue(task.hasExited());
        }
    }

    @Test
    void cancellationBeforeStartStillAllowsTheTaskToExit() {
        TuiRunTask task = new TuiRunTask(() -> fail("Cancelled action must not run"));
        task.cancel(true);
        assertFalse(task.hasExited());
        task.run();
        assertTrue(task.hasExited());
    }

    @Test
    void failureStillMarksTheTaskAsExited() {
        TuiRunTask task = new TuiRunTask(() -> { throw new IllegalStateException("failure"); });
        task.run();
        assertTrue(task.hasExited());
    }
}
