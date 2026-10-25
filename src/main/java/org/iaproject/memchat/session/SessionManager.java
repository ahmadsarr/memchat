package org.iaproject.memchat.session;

import org.iaproject.memchat.UserInteraction;
import org.iaproject.memchat.memory.ArchivalMemoryRepository;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager implements AutoCloseable {
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Path storageRoot;

    public SessionManager() {
        this(Path.of("sessions"));
    }

    public SessionManager(Path storageRoot) {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot");
    }

    public Session create(AgentConfig config) {
        Session session = new Session(config, storageRoot);
        sessions.put(session.id(), session);
        return session;
    }

    public Session create(String baseUrl, String model, String apiKey, int maxSteps,
                          ArchivalMemoryRepository archive, UserInteraction userInteraction) {
        return create(new AgentConfig(baseUrl, model, apiKey, maxSteps, archive, null, userInteraction));
    }

    public Optional<Session> find(UUID sessionId) {
        return Optional.ofNullable(sessions.get(Objects.requireNonNull(sessionId, "sessionId")));
    }

    public Session require(UUID sessionId) {
        return find(sessionId).orElseThrow(() ->
                new IllegalArgumentException("Unknown session: " + sessionId));
    }

    public boolean close(UUID sessionId) {
        Session session = sessions.remove(Objects.requireNonNull(sessionId, "sessionId"));
        if (session == null) {
            return false;
        }
        session.close();
        return true;
    }

    public int size() {
        return sessions.size();
    }

    @Override
    public void close() {
        sessions.values().forEach(Session::close);
        sessions.clear();
    }
}
