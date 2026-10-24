package org.iaproject.memchat.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.action.ActionExecutor;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.tools.ToolResult;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

public class AgentProcessor {

    private final MemoryManager memoryManager;
    private final ActionExecutor actionExecutor;
    private final ObjectMapper mapper;
    private boolean recoveryRequired;
    private Consumer<AgentEvent> eventPublisher = event -> {};

    public AgentProcessor(MemoryManager memoryManager, ActionExecutor actionExecutor, ObjectMapper mapper) {
        this.memoryManager = memoryManager;
        this.actionExecutor = actionExecutor;
        this.mapper = mapper;
    }

    public void setEventPublisher(Consumer<AgentEvent> eventPublisher) {
        this.eventPublisher = eventPublisher == null ? event -> {} : eventPublisher;
    }

    public AgentDecision process(LLmApiClient.ChatResponse response) throws JsonProcessingException {
        AgentStepResult step = processResult(response, () -> false);
        if (step.decision() == AgentDecision.COMPLETE) {
            publish(AgentEvent.Type.AGENT_MESSAGE, step.finalResponse());
        }
        return step.decision();
    }

    boolean recoveryRequired() { return recoveryRequired; }

    AgentStepResult processResult(LLmApiClient.ChatResponse response, BooleanSupplier cancelled)
            throws JsonProcessingException {
        if (response == null) {
            throw new IllegalStateException("The LLM returned no response");
        }
        if (!hasToolCall(response.toolCalls())) {
            if (response.content() == null || response.content().isBlank()) {
                throw new IllegalStateException("The LLM returned no answer or tool call");
            }
            recoveryRequired = true;
            addMessage(LLmApiClient.ChatMessage.assistant(response.content()));
            recoveryRequired = false;
            return new AgentStepResult(AgentDecision.COMPLETE, response.content());
        }

        if (response.toolCalls().size() != 1) {
            throw new IllegalStateException(
                    "The agent must return exactly one tool call per response"
            );
        }

        LLmApiClient.ToolCall call = response.toolCalls().getFirst();
        if (call == null || call.id() == null || call.id().isBlank()
                || call.name() == null || call.name().isBlank()) {
            throw new IllegalStateException("A tool call requires a non-empty id and name");
        }

        recoveryRequired = true;
        addMessage(LLmApiClient.ChatMessage.assistant(
                response.content(),
                List.of(call)
        ));

        // Even a cancelled call needs a matching result once recorded in the conversation.
        ToolResult result;
        long startedAt = System.nanoTime();
        if (cancelled.getAsBoolean()) {
            result = ToolResult.failure(call.name(), "Cancelled before tool execution");
        } else {
            eventPublisher.accept(new AgentEvent(AgentEvent.Type.TOOL_EXECUTION, call.name(), null, null,
                    new AgentEvent.ToolExecution(call.id(), call.name(), null, -1)));
            result = actionExecutor.execute(call.name(), call.arguments());
        }
        eventPublisher.accept(new AgentEvent(AgentEvent.Type.TOOL_COMPLETED, call.name(), null, null,
                new AgentEvent.ToolExecution(call.id(), call.name(), result,
                        java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis())));

        addMessage(LLmApiClient.ChatMessage.tool(
                call.id(),
                mapper.writeValueAsString(result)
        ));

        recoveryRequired = false;
        if (result.success() && actionExecutor.isTerminal(call.name())) {
            return new AgentStepResult(AgentDecision.COMPLETE, result.result());
        }

        return new AgentStepResult(AgentDecision.CONTINUE, null);
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
