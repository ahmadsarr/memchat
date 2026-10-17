package org.iaproject.memchat.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.action.ActionExecutor;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.tools.ToolResult;

import java.util.List;
import java.util.function.Consumer;

public class AgentProcessor {

    private final MemoryManager memoryManager;
    private final ActionExecutor actionExecutor;
    private final ObjectMapper mapper;
    private Consumer<AgentEvent> eventPublisher = event -> {
    };

    public AgentProcessor(MemoryManager memoryManager, ActionExecutor actionExecutor, ObjectMapper mapper) {
        this.memoryManager = memoryManager;
        this.actionExecutor = actionExecutor;
        this.mapper = mapper;
    }

    public void setEventPublisher(Consumer<AgentEvent> eventPublisher) {
        this.eventPublisher = eventPublisher == null ? event -> {
        } : eventPublisher;
    }

    public AgentDecision process(LLmApiClient.ChatResponse response) throws JsonProcessingException {
        if (response == null) {
            throw new IllegalStateException("The LLM returned no response");
        }
        if (!hasToolCall(response.toolCalls())) {
            if (response.content() == null || response.content().isBlank()) {
                throw new IllegalStateException("The LLM returned no answer or tool call");
            }
            addMessage(LLmApiClient.ChatMessage.assistant(response.content()));
            publish(AgentEvent.Type.AGENT_MESSAGE, response.content());
            return AgentDecision.COMPLETE;
        }

        if (response.toolCalls().size() != 1) {
            addMessage(LLmApiClient.ChatMessage.system("Error: The agent must return exactly one tool call per response"));
            return AgentDecision.CONTINUE;
        }

        LLmApiClient.ToolCall call = response.toolCalls().getFirst();
        if (call == null || call.id() == null || call.id().isBlank()
                || call.name() == null || call.name().isBlank()) {
            throw new IllegalStateException("A tool call requires a non-empty id and name");
        }

        addMessage(LLmApiClient.ChatMessage.assistant(
                response.content(),
                List.of(call)
        ));

        publish(AgentEvent.Type.TOOL_EXECUTION, call.name());

        ToolResult result = actionExecutor.execute(
                call.name(),
                call.arguments()
        );

        addMessage(LLmApiClient.ChatMessage.tool(
                call.id(),
                mapper.writeValueAsString(result)
        ));

        if (result.success() && actionExecutor.isTerminal(call.name())) {
            publish(AgentEvent.Type.AGENT_MESSAGE, result.result());
            return AgentDecision.COMPLETE;
        }

        return AgentDecision.CONTINUE;
    }

    public boolean hasToolCall(List<LLmApiClient.ToolCall> toolCalls) {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    private void addMessage(LLmApiClient.ChatMessage message) {
        if (memoryManager != null) {
            memoryManager.addMessage(message);
        }
    }

    private void publish(AgentEvent.Type type, String message) {
        eventPublisher.accept(new AgentEvent(type, message));
    }
}
