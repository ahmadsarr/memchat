package org.iaproject.memchat.session;

import org.iaproject.memchat.UserInteraction;
import org.iaproject.memchat.memory.ArchivalMemoryRepository;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager implements AutoCloseable {
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Path storageRoot;
    private boolean closed;

    public SessionManager() {
        this(Path.of("sessions"));
    }

    public SessionManager(Path storageRoot) {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot");
    }

    public synchronized Session create(AgentConfig config) {
        if (closed) {
            throw new IllegalStateException("Session manager is closed");
        }
        Session session = new Session(config, storageRoot);
        sessions.put(session.id(), session);
        return session;
    }

    public Session create(String baseUrl, String model, String apiKey, int maxSteps,
                          ArchivalMemoryRepository archive, UserInteraction userInteraction) {
        return create(archive == null
                ? AgentConfig.isolated(baseUrl, model, apiKey, maxSteps, userInteraction)
                : AgentConfig.withSharedRepositories(baseUrl, model, apiKey, maxSteps,
                archive, null, userInteraction));
    }

    public synchronized Optional<Session> find(UUID sessionId) {
        return Optional.ofNullable(sessions.get(Objects.requireNonNull(sessionId, "sessionId")));
    }

    public synchronized Session require(UUID sessionId) {
        return find(sessionId).orElseThrow(() ->
                new IllegalArgumentException("Unknown session: " + sessionId));
    }

    public boolean close(UUID sessionId) {
        Session session;
        synchronized (this) {
            session = sessions.remove(Objects.requireNonNull(sessionId, "sessionId"));
        }
        if (session == null) {
            return false;
        }
        session.close();
        return true;
    }

    public synchronized int size() {
        return sessions.size();
    }

    @Override
    public void close() {
        List<Session> sessionsToClose;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            sessionsToClose = List.copyOf(sessions.values());
            sessions.clear();
        }
        sessionsToClose.forEach(Session::close);
    }
}
