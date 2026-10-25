package org.iaproject.memchat.session;

import org.iaproject.memchat.UserInteraction;
import org.iaproject.memchat.agent.AgentBuilder;
import org.iaproject.memchat.agent.AgentListener;
import org.iaproject.memchat.agent.AgentLoop;
import org.iaproject.memchat.agent.AgentRunResult;
import org.iaproject.memchat.memory.ArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public final class Session implements AutoCloseable {
    private final AgentLoop agentLoop;
    private final UUID sessionId;
    private final LocalDateTime createdAt;
    private LocalDateTime lastUpdate;

    public Session(String baseUrl, String model, String apiKey, int maxSteps, ArchivalMemoryRepository archive, UserInteraction console) {
        this(new AgentConfig(baseUrl, model, apiKey, maxSteps, archive, null, console));
    }

    public Session(String baseUrl, String model, String apiKey, int maxSteps, ArchivalMemoryRepository archive,
                   UserInteraction console, Path storageRoot) {
        this(new AgentConfig(baseUrl, model, apiKey, maxSteps, archive, null, console), storageRoot);
    }

    public Session(AgentConfig config) {
        this(config, Path.of("sessions"));
    }

    public Session(AgentConfig config, Path storageRoot) {
        Objects.requireNonNull(config, "config");
        this.sessionId = UUID.randomUUID();
        Path storageDirectory = Objects.requireNonNull(storageRoot, "storageRoot").resolve(sessionId.toString());
        this.agentLoop = AgentBuilder.builder()
                .baseUrl(config.baseUrl())
                .model(config.model())
                .apiKey(config.apiKey())
                .maxSteps(config.maxSteps())
                .archivalMemoryRepository(config.archivalMemoryRepository() == null
                        ? new FileArchivalMemoryRepository(storageDirectory.resolve("memory.json"))
                        : config.archivalMemoryRepository())
                .recallStorageRepository(config.recallStorageRepository() == null
                        ? new FileRecallStorageRepository(storageDirectory.resolve("recall.json"))
                        : config.recallStorageRepository())
                .userInteraction(config.userInteraction())
                .build();
        this.createdAt = lastUpdate = LocalDateTime.now();
    }

    public Session(AgentLoop agentLoop) {
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop");
        this.sessionId = UUID.randomUUID();
        this.createdAt = lastUpdate = LocalDateTime.now();
    }

    public UUID id() {
        return sessionId;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalDateTime lastUpdate() {
        return lastUpdate;
    }

    public AgentRunResult run(String instruction) {
        AgentRunResult result = agentLoop.run(instruction);
        lastUpdate = LocalDateTime.now();
        return result;
    }

    public AutoCloseable subscribe(AgentListener listener) {
        return agentLoop.subscribe(listener);
    }

    public AgentLoop.State state() {
        return agentLoop.state();
    }

    public boolean recoveryRequired() {
        return agentLoop.recoveryRequired();
    }

    @Override
    public void close() {
        agentLoop.close();
    }
}
