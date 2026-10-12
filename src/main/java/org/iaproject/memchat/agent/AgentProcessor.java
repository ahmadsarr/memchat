package org.iaproject.memchat.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.action.ActionExecutor;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.tools.ToolResult;

import java.util.List;

public class AgentProcessor {

    private final MemoryManager memoryManager;
    private final ActionExecutor actionExecutor;
    private final ObjectMapper mapper;

    public AgentProcessor(MemoryManager memoryManager, ActionExecutor actionExecutor, ObjectMapper mapper) {
        this.memoryManager = memoryManager;
        this.actionExecutor = actionExecutor;
        this.mapper = mapper;
    }

    public AgentLoop.STATE process(LLmApiClient.ChatResponse response) throws JsonProcessingException {
        if (!hasToolCall(response.toolCalls())) {
            addMessage(LLmApiClient.ChatMessage.assistant(response.content()));
            System.out.println("Agent: " + response.content());
            return AgentLoop.STATE.STOP;
        }

        if (response.toolCalls().size() != 1) {
            throw new IllegalStateException(
                    "The agent must return exactly one tool call per response"
            );
        }

        addMessage(LLmApiClient.ChatMessage.assistant(
                response.content(),
                response.toolCalls()
        ));

        LLmApiClient.ToolCall call = response.toolCalls().getFirst();

        System.out.println("Execute: " + call.name());

        ToolResult result = actionExecutor.execute(
                call.name(),
                call.arguments()
        );

        addMessage(LLmApiClient.ChatMessage.tool(
                call.id(),
                mapper.writeValueAsString(result)
        ));
        return AgentLoop.STATE.NEXT;
    }

    public boolean hasToolCall(List<LLmApiClient.ToolCall> toolCalls) {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    private void addMessage(LLmApiClient.ChatMessage message) {
        if (memoryManager != null) {
            memoryManager.addMessage(message);
        }
    }
}
