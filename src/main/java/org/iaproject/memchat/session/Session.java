package org.iaproject.memchat.session;

import org.iaproject.memchat.UserInteraction;
import org.iaproject.memchat.agent.AgentBuilder;
import org.iaproject.memchat.agent.AgentListener;
import org.iaproject.memchat.agent.AgentLoop;
import org.iaproject.memchat.agent.AgentRunResult;
import org.iaproject.memchat.memory.ArchivalMemoryRepository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public final class Session implements AutoCloseable {
    private final AgentLoop agentLoop;
    private final UUID sessionId;
    private final LocalDateTime createdAt;
    private final Object lifecycleLock = new Object();
    private LocalDateTime lastUpdate;
    private boolean runActive;
    private Thread activeRunThread;
    private boolean closing;
    private boolean closed;

    public Session(String baseUrl, String model, String apiKey, int maxSteps, ArchivalMemoryRepository archive, UserInteraction console) {
        this(archive == null
                ? AgentConfig.isolated(baseUrl, model, apiKey, maxSteps, console)
                : AgentConfig.withSharedRepositories(baseUrl, model, apiKey, maxSteps,
                archive, null, console));
    }

    public Session(String baseUrl, String model, String apiKey, int maxSteps, ArchivalMemoryRepository archive,
                   UserInteraction console, Path storageRoot) {
        this(archive == null
                ? AgentConfig.isolated(baseUrl, model, apiKey, maxSteps, console)
                : AgentConfig.withSharedRepositories(baseUrl, model, apiKey, maxSteps,
                archive, null, console), storageRoot);
    }

    public Session(AgentConfig config) {
        this(config, Path.of("sessions"));
    }

    public Session(AgentConfig config, Path storageRoot) {
        Objects.requireNonNull(config, "config");
        this.sessionId = UUID.randomUUID();
        Path storageDirectory = Objects.requireNonNull(storageRoot, "storageRoot").resolve(sessionId.toString());
        ArchivalMemoryRepository archive = Objects.requireNonNull(
                config.archivalMemoryFactory().create(sessionId, storageDirectory),
                "archival memory factory returned null");
        var recall = Objects.requireNonNull(
                config.recallStorageFactory().create(sessionId, storageDirectory),
                "recall storage factory returned null");
        this.agentLoop = AgentBuilder.builder()
                .baseUrl(config.baseUrl())
                .model(config.model())
                .apiKey(config.apiKey())
                .maxSteps(config.maxSteps())
                .archivalMemoryRepository(archive)
                .recallStorageRepository(recall)
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
        beginRun();
        try {
            AgentRunResult result = agentLoop.run(instruction);
            lastUpdate = LocalDateTime.now();
            return result;
        } finally {
            endRun();
        }
    }

    public AutoCloseable subscribe(AgentListener listener) {
        synchronized (lifecycleLock) {
            requireAcceptingOperations();
            return agentLoop.subscribe(listener);
        }
    }

    public AgentLoop.State state() {
        synchronized (lifecycleLock) {
            return agentLoop.state();
        }
    }

    public boolean recoveryRequired() {
        synchronized (lifecycleLock) {
            return agentLoop.recoveryRequired();
        }
    }

    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            if (closing) {
                if (activeRunThread == Thread.currentThread()) {
                    throw new IllegalStateException("Cannot close a session from its active run");
                }
                while (!closed) {
                    try {
                        lifecycleLock.wait();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            if (activeRunThread == Thread.currentThread()) {
                throw new IllegalStateException("Cannot close a session from its active run");
            }
            closing = true;
            while (runActive) {
                try {
                    lifecycleLock.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }

        try {
            agentLoop.close();
        } finally {
            synchronized (lifecycleLock) {
                closed = true;
                lifecycleLock.notifyAll();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void beginRun() {
        synchronized (lifecycleLock) {
            requireAcceptingOperations();
            if (runActive) {
                throw new IllegalStateException("A run is already in progress");
            }
            runActive = true;
            activeRunThread = Thread.currentThread();
        }
    }

    private void endRun() {
        synchronized (lifecycleLock) {
            runActive = false;
            activeRunThread = null;
            lifecycleLock.notifyAll();
        }
    }

    private void requireAcceptingOperations() {
        if (closed) {
            throw new IllegalStateException("Session is CLOSED");
        }
        if (closing) {
            throw new IllegalStateException("Session is CLOSING");
        }
    }
}
