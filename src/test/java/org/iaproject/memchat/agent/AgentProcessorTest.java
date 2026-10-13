package org.iaproject.memchat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.action.ActionExecutor;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.tools.Param;
import org.iaproject.memchat.tools.Tool;
import org.iaproject.memchat.tools.ToolParser;
import org.junit.jupiter.api.Test;

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
    }

    @Test
    void terminalToolProducesCompleteDecision() throws Exception {
        var processor = processor();
        List<AgentEvent> events = new ArrayList<>();
        processor.setEventPublisher(events::add);

        AgentDecision decision = processor.process(new LLmApiClient.ChatResponse(
                "",
                List.of(new LLmApiClient.ToolCall(
                        "call-1", "finish", "{\"arg0\":\"done\"}"
                ))
        ));

        assertEquals(AgentDecision.COMPLETE, decision);
        assertEquals(new AgentEvent(AgentEvent.Type.AGENT_MESSAGE, "done"), events.getLast());
    }

    @Test
    void nonTerminalToolProducesContinueDecision() throws Exception {
        var processor = processor();

        AgentDecision decision = processor.process(new LLmApiClient.ChatResponse(
                "",
                List.of(new LLmApiClient.ToolCall(
                        "call-1", "continueTask", "{\"arg0\":\"step\"}"
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
