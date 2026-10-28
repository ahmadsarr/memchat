package org.iaproject.memchat.memory;

import java.util.Map;

/** Complete snapshots for one profile. Save must be atomic; one owning memory instance. */
public interface UserMemoryRepository {
    Map<String, UserMemoryEntry> load();
    void save(Map<String, UserMemoryEntry> entries);
}
