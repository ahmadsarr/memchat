package org.iaproject.memchat.session;

import java.nio.file.Path;
import java.util.UUID;

/** Creates a repository for one session. The factory is invoked once per session. */
@FunctionalInterface
public interface SessionRepositoryFactory<T> {
    T create(UUID sessionId, Path sessionDirectory);
}
