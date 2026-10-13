package org.iaproject.memchat.memory;

import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.strategy.ContextControlStrategy;
import org.iaproject.memchat.memory.strategy.ContextControlStrategyImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextControlStrategyTest {
    @Test
    void classifiesNormalWarningAndFlushPressure() {
        var strategy = new ContextControlStrategyImpl(100, 0, 0.5, 0.8);

        assertEquals(ContextControlStrategy.Pressure.NORMAL,
                strategy.pressure(List.of(LLmApiClient.ChatMessage.user("short"))));
        assertEquals(ContextControlStrategy.Pressure.WARNING,
                strategy.pressure(List.of(LLmApiClient.ChatMessage.user("x".repeat(220)))));
        assertEquals(ContextControlStrategy.Pressure.FLUSH_REQUIRED,
                strategy.pressure(List.of(LLmApiClient.ChatMessage.user("x".repeat(340)))));
    }

    @Test
    void countsToolCallMetadataInTokenEstimate() {
        var strategy = new ContextControlStrategyImpl(100, 0, 0.5, 0.9);
        var message = LLmApiClient.ChatMessage.assistant(
                null,
                List.of(new LLmApiClient.ToolCall("id", "search", "x".repeat(40)))
        );

        assertTrue(strategy.estimateTokens(List.of(message)) > 10);
    }

    @Test
    void rejectsInvalidThresholds() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContextControlStrategyImpl(10, 10, 0.5, 0.9));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextControlStrategyImpl(10, 0, 0.9, 0.8));
    }
}
