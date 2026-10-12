package org.iaproject.memchat.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.UserInteraction;
import org.iaproject.memchat.action.ActionExecutor;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.LLmApiClient.*;
import org.iaproject.memchat.memory.MemoryFullException;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.tools.ToolParser;

import java.util.Objects;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AgentLoop {
    private final int maxStep;
    private final LLmApiClient client;
    private final ObjectMapper mapper;
    private final ActionExecutor actionExecutor;
    private final MemoryManager memoryManager;
    private final AgentProcessor agentProcessor;
    private final List<AgentListener> listeners = new CopyOnWriteArrayList<>();

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
        this.agentProcessor.setEventPublisher(this::publish);
    }

    public AutoCloseable subscribe(AgentListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void run(String instruction)
            throws InterruptedException, JsonProcessingException {

        addMessage(ChatMessage.user(instruction));
        List<ChatMessage> context;
        for (int i = 0; i < maxStep; i++) {

            context = getContext();
            ChatResponse response = client.complete(context, actionExecutor.getToolsDescriptions());
            STATE state = agentProcessor.process(response);
            if (state == STATE.STOP)
                return;
            Thread.sleep(100);
        }
        publish(new AgentEvent(
                AgentEvent.Type.SYSTEM,
                "Maximum steps reached"
        ));
    }

    private List<ChatMessage> getContext() {
        List<ChatMessage> context;
        try {
            context = memoryManager.context();
        } catch (MemoryFullException e) {
            memoryManager.compact();
            context = memoryManager.context();
        }
        return context;
    }

    private void addMessage(ChatMessage message) {
        if (memoryManager != null) {
            memoryManager.addMessage(message);
        }
    }

    private void publish(AgentEvent event) {
        listeners.forEach(listener -> listener.onEvent(event));
    }

}
