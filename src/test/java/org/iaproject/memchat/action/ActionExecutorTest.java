package org.iaproject.memchat.action;

import org.iaproject.memchat.tools.Param;
import org.iaproject.memchat.tools.Tool;
import org.iaproject.memchat.tools.ToolParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActionExecutorTest {
    public static class TestTools {
        @Tool(description = "Complete the task", terminal = true)
        public String finish(@Param(description = "Answer") String answer) {
            return answer;
        }

        @Tool(description = "Echo a value")
        public String echo(@Param(description = "Value") String value) {
            return value;
        }
    }

    private final ActionExecutor executor = new ActionExecutor(
            ToolParser.parse(List.of(new TestTools()))
    );

    @Test
    void terminalMetadataIsExposedWithoutKnowingTheToolClass() {
        assertTrue(executor.isTerminal("finish"));
        assertFalse(executor.isTerminal("echo"));
    }

    @Test
    void executesValidJsonArguments() {
        var result = executor.execute("echo", "{\"value\":\"hello\"}");

        assertTrue(result.success(), result.toString());
        assertEquals("hello", result.result());
    }

    @Test
    void reportsInvalidArgumentsWithoutThrowing() {
        var result = executor.execute("echo", "not-json");

        assertFalse(result.success());
        assertTrue(result.error().contains("Invalid JSON"));
    }
}
