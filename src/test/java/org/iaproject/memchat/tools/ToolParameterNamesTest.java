package org.iaproject.memchat.tools;

import org.iaproject.memchat.action.ActionExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolParameterNamesTest {
    public static class NamedTool {
        @Tool(description = "Use named parameters")
        public String remember(@Param(description = "Section") String section,
                               @Param(description = "Content") String content) {
            return section + ":" + content;
        }
    }

    @Test
    void schemasAndInvocationUseTheDeclaredJavaParameterNames() {
        var executor = new ActionExecutor(ToolParser.parse(List.of(new NamedTool())));
        var schema = executor.getToolsDescriptions().getFirst();
        assertEquals(List.of("section", "content"), List.copyOf(schema.params().keySet()));
        var result = executor.execute("remember", "{\"section\":\"facts\",\"content\":\"Java\"}");
        assertTrue(result.success(), result.toString());
        assertEquals("facts:Java", result.result());
    }
}
