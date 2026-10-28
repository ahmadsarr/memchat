package org.iaproject.memchat.agent;

import org.iaproject.memchat.ConsoleUserInteraction;
import org.iaproject.memchat.UserInteraction;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.OpenAICompatibleClient;
import org.iaproject.memchat.memory.ArchivalMemoryRepository;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.memory.MemoryToolsImpl;
import org.iaproject.memchat.memory.RecallStorageRepository;
import org.iaproject.memchat.memory.UserMemoryRepository;
import org.iaproject.memchat.memory.UserMemory;
import org.iaproject.memchat.tools.UserPreferencesTools;
import org.iaproject.memchat.tools.UserMemoryTools;
import org.iaproject.memchat.memory.impl.ArchivalMemoryImpl;
import org.iaproject.memchat.memory.impl.UserMemoryImpl;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;
import org.iaproject.memchat.memory.impl.RecallStorageImpl;
import org.iaproject.memchat.memory.strategy.OpenAICompactStrategy;
import org.iaproject.memchat.tools.UserInteractionTools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AgentBuilder {
    private UserMemory userMemory;
    private String baseUrl = "http://127.0.0.1:11434/v1";
    private String model;
    private String apiKey = "ollama";
    private LLmApiClient client;
    private MemoryManager memoryManager;
    private UserInteraction userInteraction = new ConsoleUserInteraction();
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

    public AgentBuilder userMemoryRepository(UserMemoryRepository repository) {
        return userMemory(new UserMemoryImpl(repository));
    }

    /** Share one memory instance across all agents belonging to the same user. */
    public AgentBuilder userPreferencesMemory(UserMemory memory) {
        return userMemory(memory);
    }

    public AgentBuilder userMemory(UserMemory memory) {
        this.userMemory = Objects.requireNonNull(memory);
        return this;
    }

    public AgentLoop build() {
        if (memoryManager != null && userMemory != null) {
            throw new IllegalStateException("Configure user memory on the custom MemoryManager and register its tools explicitly");
        }
        LLmApiClient llmClient = client != null
                ? client
                : new OpenAICompatibleClient(
                require(baseUrl, "baseUrl"),
                require(model, "model"),
                apiKey
        );

        MemoryManager agentMemory = memoryManager;
        if (agentMemory == null) {
            var recall = new RecallStorageImpl(recallStorageRepository != null
                    ? recallStorageRepository : new FileRecallStorageRepository());
            var archive = new ArchivalMemoryImpl(archivalMemoryRepository != null
                    ? archivalMemoryRepository : new FileArchivalMemoryRepository());
            var compact = new OpenAICompactStrategy(llmClient);
            agentMemory = userMemory == null
                    ? new MemoryManager(recall, archive, compact)
                    : new MemoryManager(recall, archive, compact, userMemory);
        }

        List<Object> registeredTools = new ArrayList<>(tools);
        registeredTools.add(new MemoryToolsImpl(agentMemory));
        if (userMemory != null) {
            registeredTools.add(new UserPreferencesTools(userMemory));
            registeredTools.add(new UserMemoryTools(userMemory));
        }
        registeredTools.add(new UserInteractionTools(userInteraction));

        return new AgentLoop(
                maxSteps,
                llmClient,
                agentMemory,
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
