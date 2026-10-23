package org.iaproject.memchat.agent;

import org.iaproject.memchat.ConsoleUserInteraction;
import org.iaproject.memchat.UserInteraction;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.OpenAICompatibleClient;
import org.iaproject.memchat.llm.ApiClientObservation;
import org.iaproject.memchat.llm.Slf4jApiClientObservation;
import org.iaproject.memchat.memory.ArchivalMemoryRepository;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.memory.MemoryToolsImpl;
import org.iaproject.memchat.memory.RecallStorageRepository;
import org.iaproject.memchat.memory.impl.*;
import org.iaproject.memchat.memory.strategy.OpenAICompactStrategy;
import org.iaproject.memchat.tools.UserInteractionTools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AgentBuilder {
    private String baseUrl = "http://127.0.0.1:11434/v1";
    private String model;
    private String apiKey = "ollama";
    private LLmApiClient client;
    private MemoryManager memoryManager;
    private UserInteraction userInteraction = new ConsoleUserInteraction();
    private ApiClientObservation observation = new Slf4jApiClientObservation();
    private int maxSteps = 50;
    private final List<Object> tools = new ArrayList<>();
    private RecallStorageRepository recallStorageRepository;
    private ArchivalMemoryRepository archivalMemoryRepository;

    private AgentBuilder() {

    }

    public static AgentBuilder builder() {
        return new AgentBuilder();
    }

    public AgentBuilder baseUrl(String baseUrl) {
        this.baseUrl = Objects.requireNonNull(baseUrl);
        return this;
    }

    public AgentBuilder model(String model) {
        this.model = Objects.requireNonNull(model);
        return this;
    }

    public AgentBuilder apiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }

    public AgentBuilder client(LLmApiClient client) {
        this.client = Objects.requireNonNull(client);
        return this;
    }

    public AgentBuilder memoryManager(MemoryManager memoryManager) {
        this.memoryManager = Objects.requireNonNull(memoryManager);
        return this;
    }

    public AgentBuilder userInteraction(UserInteraction userInteraction) {
        this.userInteraction = Objects.requireNonNull(userInteraction);
        return this;
    }

    public AgentBuilder observation(ApiClientObservation observation) {
        this.observation = Objects.requireNonNull(observation);
        return this;
    }

    public AgentBuilder maxSteps(int maxSteps) {
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
        return this;
    }

    public AgentBuilder tool(Object tool) {
        tools.add(Objects.requireNonNull(tool));
        return this;
    }

    public AgentBuilder tools(Object... tools) {
        for (Object tool : tools) {
            tool(tool);
        }
        return this;
    }

    public AgentBuilder archivalMemoryRepository(ArchivalMemoryRepository repo) {
        this.archivalMemoryRepository = Objects.requireNonNull(repo);
        return this;
    }

    public AgentBuilder recallStorageRepository(RecallStorageRepository repo) {
        this.recallStorageRepository = Objects.requireNonNull(repo);
        return this;
    }

    public AgentLoop build() {
        LLmApiClient llmClient = client != null
                ? client
                : new OpenAICompatibleClient(
                require(baseUrl, "baseUrl"),
                require(model, "model"),
                apiKey,
                observation
        );

        MemoryManager agentMemory = memoryManager;
        AutoCloseable archivalResource = null;
        if (agentMemory == null) {
            ArchivalMemoryRepository selectedArchivalRepository = archivalMemoryRepository;
            if (selectedArchivalRepository == null) {
                try {
                    selectedArchivalRepository = new LuceneArchivalMemoryRepository();
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Unable to open Lucene archival memory", exception);
                }
            }
            if (selectedArchivalRepository instanceof AutoCloseable closeable) {
                archivalResource = closeable;
            }
            agentMemory = new MemoryManager(
                    new RecallStorageImpl(recallStorageRepository != null
                            ? recallStorageRepository : new FileRecallStorageRepository()),
                    new ArchivalMemoryImpl(selectedArchivalRepository),
                    new OpenAICompactStrategy(llmClient)
            );
        }

        List<Object> registeredTools = new ArrayList<>(tools);
        registeredTools.add(new MemoryToolsImpl(agentMemory));
        registeredTools.add(new UserInteractionTools(userInteraction));

        return new AgentLoop(
                maxSteps,
                llmClient,
                agentMemory,
                archivalResource == null ? List.of() : List.of(archivalResource),
                registeredTools.toArray()
        );
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

}
