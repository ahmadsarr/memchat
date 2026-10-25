package org.iaproject.memchat.session;

import org.iaproject.memchat.UserInteraction;
import org.iaproject.memchat.memory.ArchivalMemoryRepository;
import org.iaproject.memchat.memory.RecallStorageRepository;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.FileRecallStorageRepository;

import java.util.Objects;

public record AgentConfig(
        String baseUrl,
        String model,
        String apiKey,
        int maxSteps,
        SessionRepositoryFactory<ArchivalMemoryRepository> archivalMemoryFactory,
        SessionRepositoryFactory<RecallStorageRepository> recallStorageFactory,
        UserInteraction userInteraction
) {
    public AgentConfig {
        requireText(baseUrl, "baseUrl");
        requireText(model, "model");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        Objects.requireNonNull(archivalMemoryFactory, "archivalMemoryFactory");
        Objects.requireNonNull(recallStorageFactory, "recallStorageFactory");
        Objects.requireNonNull(userInteraction, "userInteraction");
    }

    public static AgentConfig isolated(String baseUrl, String model, String apiKey, int maxSteps,
                                       UserInteraction userInteraction) {
        return new AgentConfig(
                baseUrl,
                model,
                apiKey,
                maxSteps,
                (sessionId, directory) ->
                        new FileArchivalMemoryRepository(directory.resolve("memory.json")),
                (sessionId, directory) ->
                        new FileRecallStorageRepository(directory.resolve("recall.json")),
                userInteraction
        );
    }

    public static AgentConfig withSharedRepositories(
            String baseUrl,
            String model,
            String apiKey,
            int maxSteps,
            ArchivalMemoryRepository archivalMemoryRepository,
            RecallStorageRepository recallStorageRepository,
            UserInteraction userInteraction
    ) {
        SessionRepositoryFactory<ArchivalMemoryRepository> archiveFactory = archivalMemoryRepository == null
                ? (sessionId, directory) -> new FileArchivalMemoryRepository(directory.resolve("memory.json"))
                : (sessionId, directory) -> archivalMemoryRepository;
        SessionRepositoryFactory<RecallStorageRepository> recallFactory = recallStorageRepository == null
                ? (sessionId, directory) -> new FileRecallStorageRepository(directory.resolve("recall.json"))
                : (sessionId, directory) -> recallStorageRepository;
        return new AgentConfig(
                baseUrl,
                model,
                apiKey,
                maxSteps,
                archiveFactory,
                recallFactory,
                userInteraction
        );
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
