package org.iaproject.memchat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.action.ActionExecutor;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.tools.Param;
import org.iaproject.memchat.tools.Tool;
import org.iaproject.memchat.tools.ToolParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentProcessorTest {
    public static class TestTools {
        @Tool(description = "Complete the task", terminal = true)
        public String finish(@Param(description = "Answer") String answer) {
            return answer;
        }

        @Tool(description = "Continue the task")
        public String continueTask(@Param(description = "Value") String value) {
            return value;
        }

        @Tool(description = "Fail the tool")
        public String explode() {
            throw new IllegalStateException("tool exploded");
        }
    }

    @Test
    void terminalToolProducesCompleteDecision() throws Exception {
        var processor = processor();
        List<AgentEvent> events = new ArrayList<>();
        processor.setEventPublisher(events::add);

        AgentDecision decision = processor.process(new LLmApiClient.ChatResponse(
                "",
                List.of(new LLmApiClient.ToolCall(
                        "call-1", "finish", "{\"answer\":\"done\"}"
                ))
        ));

        assertEquals(AgentDecision.COMPLETE, decision);
        assertEquals(new AgentEvent(AgentEvent.Type.AGENT_MESSAGE, "done"), events.getLast());
        var completed = events.stream().filter(event -> event.type() == AgentEvent.Type.TOOL_COMPLETED)
                .findFirst().orElseThrow();
        assertTrue(completed.toolExecution().result().success());
        assertEquals("done", completed.toolExecution().result().result());
    }

    static java.util.stream.Stream<Arguments> failedCalls() {
        return java.util.stream.Stream.of(Arguments.of("missing", "{}"),
                Arguments.of("continueTask", "{\"wrong\":\"value\"}"),
                Arguments.of("continueTask", "{"), Arguments.of("explode", "{}"));
    }

    @ParameterizedTest
    @MethodSource("failedCalls")
    void publishesTheActualFailureWithoutFailingTheAgentDecision(String name, String arguments) throws Exception {
        var processor = processor();
        List<AgentEvent> events = new ArrayList<>();
        processor.setEventPublisher(events::add);
        var decision = processor.process(new LLmApiClient.ChatResponse(null,
                List.of(new LLmApiClient.ToolCall("failed-call", name, arguments))));
        assertEquals(AgentDecision.CONTINUE, decision);
        assertEquals(List.of(AgentEvent.Type.TOOL_EXECUTION, AgentEvent.Type.TOOL_COMPLETED),
                events.stream().map(AgentEvent::type).toList());
        var tool = events.getLast().toolExecution();
        assertEquals("failed-call", tool.callId());
        assertEquals(name, tool.name());
        assertFalse(tool.result().success());
        assertNotNull(tool.result().error());
        assertTrue(tool.durationMillis() >= 0);
    }

    @Test
    void nonTerminalToolProducesContinueDecision() throws Exception {
        var processor = processor();

        AgentDecision decision = processor.process(new LLmApiClient.ChatResponse(
                "",
                List.of(new LLmApiClient.ToolCall(
                        "call-1", "continueTask", "{\"value\":\"step\"}"
                ))
        ));

        assertEquals(AgentDecision.CONTINUE, decision);
    }

    @Test
    void responseWithoutToolProducesCompleteDecision() throws Exception {
        var processor = processor();

        AgentDecision decision = processor.process(
                new LLmApiClient.ChatResponse("final answer", List.of())
        );

        assertEquals(AgentDecision.COMPLETE, decision);
    }

    private static AgentProcessor processor() {
        ActionExecutor executor = new ActionExecutor(
                ToolParser.parse(List.of(new TestTools()))
        );
        return new AgentProcessor(null, executor, new ObjectMapper());
    }
}
