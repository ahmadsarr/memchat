package org.iaproject.memchat.action;

import org.iaproject.memchat.tools.Param;
import org.iaproject.memchat.tools.Tool;
import org.iaproject.memchat.tools.ToolParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActionExecutorTest {
    public static class TestTools {
        static final AssertionError FATAL = new AssertionError("fatal tool error");

        @Tool(description = "Fail fatally")
        public String fatal() {
            throw FATAL;
        }

        @Tool(description = "Fail ordinarily")
        public String ordinaryFailure() {
            throw new IllegalStateException("ordinary tool failure");
        }

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
    void propagatesOriginalFatalToolError() {
        assertSame(TestTools.FATAL,
                assertThrows(AssertionError.class, () -> executor.execute("fatal", "{}")));
    }

    @Test
    void ordinaryToolExceptionsRemainRecoverableFailures() {
        var result = executor.execute("ordinaryFailure", "{}");
        assertFalse(result.success());
        assertTrue(result.error().contains("ordinary tool failure"));
    }

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
