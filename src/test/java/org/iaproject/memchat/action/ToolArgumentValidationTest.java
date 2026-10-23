package org.iaproject.memchat.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.tools.Param;
import org.iaproject.memchat.tools.Tool;
import org.iaproject.memchat.tools.ToolParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolArgumentValidationTest {
    private final CountingTools tools = new CountingTools();
    private final ActionExecutor executor = new ActionExecutor(ToolParser.parse(List.of(tools)));

    @Test
    void everyExecutionEntryPointReturnsAFailureForMissingActions() {
        assertNotNull(executor.execute((ActionParser.Action) null));
        assertFalse(executor.execute((ActionParser.Action) null).success());
        assertFalse(executor.execute(new ActionParser.Action("", null, null)).success());
        assertEquals(0, tools.calls.get());
    }

    @Test
    void objectArgumentsAreRequiredEvenForZeroParameterTools() {
        var mapper = new ObjectMapper();
        assertFalse(executor.execute("finish", mapper.createArrayNode()).success());
        assertFalse(executor.execute("finish", mapper.nullNode()).success());
        assertFalse(executor.execute("finish", (com.fasterxml.jackson.databind.JsonNode) null).success());
        assertEquals(0, tools.calls.get());
    }

    @Test
    void duplicateKeysAndTrailingJsonCannotExecuteATool() {
        assertFalse(executor.execute("finish", "{} {}").success());
        assertFalse(executor.execute("finish", "{\"value\":1,\"value\":2}").success());
        assertEquals(0, tools.calls.get());
    }

    @Test
    void fractionalArgumentsAreNotSilentlyTruncatedToIntegers() throws Exception {
        String name = CountingTools.class.getMethod("count", int.class).getParameters()[0].getName();
        assertFalse(executor.execute("count", "{\"" + name + "\":2.9}").success());
        assertEquals(0, tools.calls.get());
        assertTrue(executor.execute("count", "{\"" + name + "\":2}").success());
        assertEquals(1, tools.calls.get());
    }

    @Test
    void toolLookupIsIndependentOfTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var localExecutor = new ActionExecutor(ToolParser.parse(List.of(tools)));
            assertTrue(localExecutor.execute("FINISH", "{}").success());
            assertTrue(localExecutor.isTerminal("FINISH"));
            assertTrue(executor.execute("finish", "{}").success());
            assertTrue(executor.isTerminal("finish"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void legacyTaggedParserRejectsDuplicateKeysAndTrailingContent() {
        var parser = new ActionParser();
        assertThrows(RuntimeException.class, () -> parser.parse(
                "<tool_call>{\"name\":\"finish\",\"arguments\":{}} {}</tool_call>"));
        assertThrows(RuntimeException.class, () -> parser.parse(
                "<tool_call>{\"name\":\"first\",\"name\":\"finish\",\"arguments\":{}}</tool_call>"));
        assertEquals("finish", parser.parse(
                "<tool_call>{\"name\":\"finish\",\"arguments\":{}}</tool_call>").name());
    }

    public static class CountingTools {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(description = "Finish", terminal = true)
        public String finish() { calls.incrementAndGet(); return "done"; }
        @Tool(description = "Use an integer")
        public int count(@Param(description = "Integer") int count) { calls.incrementAndGet(); return count; }
    }
}
