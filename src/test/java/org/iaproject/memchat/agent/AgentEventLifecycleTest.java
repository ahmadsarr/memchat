package org.iaproject.memchat.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventLifecycleTest {
    @Test
    void callbacksAreDeliveredInOrderWithoutConcurrentCalls() throws Exception {
        var dispatcher = new AgentEventDispatcher();
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        List<String> delivered = new CopyOnWriteArrayList<>();
        dispatcher.subscription(event -> {
            if ("first".equals(event.message())) {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                secondStarted.countDown();
            }
            delivered.add(event.message());
        });
        try {
            dispatcher.publish(event("first"));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            dispatcher.publish(event("second"));
            assertFalse(secondStarted.await(200, TimeUnit.MILLISECONDS));
        } finally {
            releaseFirst.countDown();
            dispatcher.close();
        }
        assertEquals(List.of("first", "second"), delivered);
    }

    @Test
    void aListenerCanCloseTheDispatcherWithoutWaitingForItself() throws Exception {
        var dispatcher = new AgentEventDispatcher();
        var closed = new CountDownLatch(1);
        dispatcher.subscription(event -> {
            dispatcher.close();
            closed.countDown();
        });
        dispatcher.publish(event("close"));
        assertTrue(closed.await(2, TimeUnit.SECONDS), "close deadlocked inside the callback");
        dispatcher.close();
    }

    @Test
    void awaitingFromACallbackFailsInsteadOfDeadlocking() throws Exception {
        var dispatcher = new AgentEventDispatcher();
        var finished = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        dispatcher.subscription(event -> {
            try {
                dispatcher.awaitPendingEvents();
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                finished.countDown();
            }
        });
        dispatcher.publish(event("await"));
        assertTrue(finished.await(2, TimeUnit.SECONDS), "callback waited for itself");
        dispatcher.close();
        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    @Test
    void closedDispatcherRejectsNewWorkAndCloseIsIdempotent() {
        var dispatcher = new AgentEventDispatcher();
        dispatcher.close();
        dispatcher.close();
        assertThrows(IllegalStateException.class, () -> dispatcher.publish(event("late")));
        assertThrows(IllegalStateException.class, () -> dispatcher.subscription(event -> { }));
    }

    @Test
    void closeDrainsEveryAcceptedEventEvenWhenOneListenerFails() {
        var dispatcher = new AgentEventDispatcher();
        List<String> delivered = new CopyOnWriteArrayList<>();
        dispatcher.subscription(event -> { throw new IllegalStateException("listener failure"); });
        dispatcher.subscription(event -> delivered.add(event.message()));
        for (int i = 0; i < 100; i++) {
            dispatcher.publish(event(Integer.toString(i)));
        }
        dispatcher.close();
        assertEquals(java.util.stream.IntStream.range(0, 100).mapToObj(Integer::toString).toList(), delivered);
    }

    @Test
    void interruptedCloseDrainsAcceptedEventsAndRestoresTheFlag() throws Exception {
        var dispatcher = new AgentEventDispatcher();
        var delivered = new CountDownLatch(20);
        dispatcher.subscription(event -> delivered.countDown());
        for (int i = 0; i < 20; i++) {
            dispatcher.publish(event("queued"));
        }
        try {
            Thread.currentThread().interrupt();
            dispatcher.close();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, delivered.getCount());
        } finally {
            Thread.interrupted();
        }
    }

    private static AgentEvent event(String text) {
        return new AgentEvent(AgentEvent.Type.SYSTEM, text);
    }
}
