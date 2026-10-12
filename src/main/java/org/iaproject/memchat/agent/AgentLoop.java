package org.iaproject.memchat.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.action.ActionExecutor;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.LLmApiClient.*;
import org.iaproject.memchat.memory.MemoryFullException;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.tools.ToolParser;
import org.iaproject.memchat.tools.ToolResult;

import java.util.List;

public class AgentLoop {
    private final int maxStep;
    private final LLmApiClient client;
    private final ObjectMapper mapper;
    private final ActionExecutor actionExecutor;
    private final MemoryManager memoryManager;
    private final AgentProcessor agentProcessor;

    public enum STATE {STOP, NEXT}


    public AgentLoop(int maxStep, LLmApiClient client, Object... tools) {
        this(maxStep, client, null, tools);
    }

    public AgentLoop(
            int maxStep,
            LLmApiClient client,
            MemoryManager memoryManager,
            Object... tools
    ) {
        this.maxStep = maxStep;
        this.client = client;
        this.memoryManager = memoryManager;
        this.mapper = new ObjectMapper();
        this.actionExecutor = new ActionExecutor(ToolParser.parse(List.of(tools)));
        this.agentProcessor = new AgentProcessor(memoryManager, actionExecutor, mapper);
    }

    public void run(String instruction)
            throws InterruptedException, JsonProcessingException {

        addMessage(ChatMessage.user(instruction));
        List<ChatMessage> context;
        for (int i = 0; i < maxStep; i++) {

            try {
                context = memoryManager.context();
            } catch (MemoryFullException e) {
                memoryManager.compact();
                context = memoryManager.context();
            }
            ChatResponse response = client.complete(
                    context,
                    actionExecutor.getToolsDescriptions()
            );

            STATE state = agentProcessor.process(response);
            if (state == STATE.STOP)
                return;
            Thread.sleep(100);
        }

        System.out.println("Maximum steps reached");
    }

    private void addMessage(ChatMessage message) {
        if (memoryManager != null) {
            memoryManager.addMessage(message);
        }
    }

}
