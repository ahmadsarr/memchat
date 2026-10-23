package org.iaproject.memchat.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.action.ActionExecutor;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.LLmApiClient.*;
import org.iaproject.memchat.memory.MemoryFullException;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.tools.ToolParser;

import java.util.Objects;
import java.util.List;
import java.util.Collection;

public class AgentLoop {
    private final int maxStep;
    private final LLmApiClient client;
    private final ObjectMapper mapper;
    private final ActionExecutor actionExecutor;
    private final MemoryManager memoryManager;
    private final AgentProcessor agentProcessor;
    private final AgentEventDispatcher eventDispatcher;
    private final List<AutoCloseable> resources;
    private boolean closed;

    public AgentLoop(
            int maxStep,
            LLmApiClient client,
            MemoryManager memoryManager,
            Object... tools
    ) {
        this(maxStep, client, memoryManager, List.of(), tools);
    }

    public AgentLoop(
            int maxStep,
            LLmApiClient client,
            MemoryManager memoryManager,
            Collection<? extends AutoCloseable> resources,
            Object... tools
    ) {
        this.maxStep = maxStep;
        this.client = Objects.requireNonNull(client);
        this.memoryManager = Objects.requireNonNull(memoryManager);
        this.resources = List.copyOf(resources);
        this.mapper = new ObjectMapper();
        this.actionExecutor = new ActionExecutor(ToolParser.parse(List.of(tools)));
        this.agentProcessor = new AgentProcessor(memoryManager, actionExecutor, mapper);
        this.agentProcessor.setEventPublisher(this::publish);
        this.eventDispatcher = new AgentEventDispatcher();
    }

    //

    public AutoCloseable subscribe(AgentListener listener) {
        return eventDispatcher.subscription(listener);
    }

    public void run(String instruction) throws InterruptedException, JsonProcessingException {

        addMessage(ChatMessage.user(instruction));
        List<ChatMessage> context;
        for (int i = 0; i < maxStep; i++) {

            context = getContext();
            ChatResponse response = client.complete(context, actionExecutor.getToolsDescriptions());
            AgentDecision decision = agentProcessor.process(response);
            if (decision == AgentDecision.COMPLETE) {
                eventDispatcher.awaitPendingEvents();
                return;
            }
            Thread.sleep(100);
        }
        publish(new AgentEvent(
                AgentEvent.Type.SYSTEM,
                "Maximum steps reached"
        ));
        eventDispatcher.awaitPendingEvents();
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
        memoryManager.addMessage(message);
    }

    private void publish(AgentEvent event) {
        eventDispatcher.publish(event);
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        eventDispatcher.close();
        RuntimeException failure = null;
        for (AutoCloseable resource : resources.reversed()) {
            try {
                resource.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("Unable to close agent resources", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

}
