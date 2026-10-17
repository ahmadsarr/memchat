package org.iaproject.memchat.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventDispatcherTest {
    @Test
    void waitsUntilPublishedEventsHaveBeenDelivered() throws Exception {
        try (var dispatcher = new AgentEventDispatcher()) {
            CountDownLatch delivered = new CountDownLatch(1);
            dispatcher.subscription(event -> delivered.countDown());

            dispatcher.publish(new AgentEvent(AgentEvent.Type.AGENT_MESSAGE, "answer"));
            dispatcher.awaitPendingEvents();

            assertTrue(delivered.await(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void listenerFailureDoesNotPreventOtherListeners() throws Exception {
        try (var dispatcher = new AgentEventDispatcher()) {
            CountDownLatch delivered = new CountDownLatch(1);
            dispatcher.subscription(event -> {
                throw new IllegalStateException("listener failure");
            });
            dispatcher.subscription(event -> delivered.countDown());

            dispatcher.publish(new AgentEvent(AgentEvent.Type.SYSTEM, "message"));
            dispatcher.awaitPendingEvents();

            assertTrue(delivered.await(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void subscriptionCanBeClosed() throws Exception {
        try (var dispatcher = new AgentEventDispatcher()) {
            CountDownLatch delivered = new CountDownLatch(1);
            AutoCloseable subscription = dispatcher.subscription(event -> delivered.countDown());
            subscription.close();

            dispatcher.publish(new AgentEvent(AgentEvent.Type.SYSTEM, "message"));
            dispatcher.awaitPendingEvents();

            assertFalse(delivered.await(100, TimeUnit.MILLISECONDS));
        }
    }
}
