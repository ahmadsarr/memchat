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
import java.util.logging.Logger;

public class AgentLoop implements AutoCloseable {
    private final static Logger log = Logger.getLogger(AgentLoop.class.getName());
    private final Object lifecycleMonitor = new Object();
    private boolean running;
    private boolean closed;
    private final int maxStep;
    private final LLmApiClient client;
    private final ObjectMapper mapper;
    private final ActionExecutor actionExecutor;
    private final MemoryManager memoryManager;
    private final AgentProcessor agentProcessor;
    private final AgentEventDispatcher eventDispatcher;

    public AgentLoop(
            int maxStep,
            LLmApiClient client,
            MemoryManager memoryManager,
            Object... tools
    ) {
        if (maxStep < 1) {
            throw new IllegalArgumentException("maxStep must be positive");
        }
        this.maxStep = maxStep;
        this.client = Objects.requireNonNull(client);
        this.memoryManager = Objects.requireNonNull(memoryManager);
        this.mapper = new ObjectMapper();
        this.actionExecutor = new ActionExecutor(ToolParser.parse(List.of(tools)));
        this.agentProcessor = new AgentProcessor(memoryManager, actionExecutor, mapper);
        this.agentProcessor.setEventPublisher(this::publish);
        this.eventDispatcher = new AgentEventDispatcher();
    }


    public AutoCloseable subscribe(AgentListener listener) {
        synchronized (lifecycleMonitor) {
            requireOpen();
            return eventDispatcher.subscription(listener);
        }
    }

    public void run(String instruction) throws InterruptedException, JsonProcessingException {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction must not be empty");
        }
        synchronized (lifecycleMonitor) {
            requireOpen();
            if (running) {
                throw new IllegalStateException("Agent is already running");
            }
            running = true;
        }
        try {
            checkInterrupted();
            addMessage(ChatMessage.user(instruction));
            for (int i = 0; i < maxStep; i++) {
                checkInterrupted();
                List<ChatMessage> context = getContext();
                checkInterrupted();
                ChatResponse response = client.complete(context, actionExecutor.getToolsDescriptions());
                checkInterrupted();
                AgentDecision decision = agentProcessor.process(response);
                checkInterrupted();
                if (decision == AgentDecision.COMPLETE) {
                    eventDispatcher.awaitPendingEvents();
                    return;
                }
                if (i + 1 < maxStep) {
                    Thread.sleep(100);
                }
            }
            publish(new AgentEvent(AgentEvent.Type.SYSTEM, "Maximum steps reached"));
            eventDispatcher.awaitPendingEvents();
        } finally {
            synchronized (lifecycleMonitor) {
                running = false;
            }
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Agent execution interrupted");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Agent is closed");
        }
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

    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            if (closed) {
                return;
            }
            if (running) {
                throw new IllegalStateException("Interrupt and finish the active run before closing the agent");
            }
            closed = true;
        }
        eventDispatcher.close();
    }

}
