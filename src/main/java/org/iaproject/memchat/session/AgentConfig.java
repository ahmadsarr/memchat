package org.iaproject.memchat.session;

import org.iaproject.memchat.UserInteraction;
import org.iaproject.memchat.memory.ArchivalMemoryRepository;
import org.iaproject.memchat.memory.RecallStorageRepository;

import java.util.Objects;

public record AgentConfig(
        String baseUrl,
        String model,
        String apiKey,
        int maxSteps,
        ArchivalMemoryRepository archivalMemoryRepository,
        RecallStorageRepository recallStorageRepository,
        UserInteraction userInteraction
) {
    public AgentConfig {
        requireText(baseUrl, "baseUrl");
        requireText(model, "model");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        Objects.requireNonNull(userInteraction, "userInteraction");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
