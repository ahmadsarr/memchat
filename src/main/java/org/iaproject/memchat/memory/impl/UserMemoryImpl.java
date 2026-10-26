package org.iaproject.memchat.memory.impl;

import org.iaproject.memchat.memory.*;
import java.time.Clock;
import java.util.*;

/** Bounded persistent memory; synchronized updates publish only committed snapshots. */
public final class UserMemoryImpl implements UserMemory {
    public static final int MAX_ENTRIES = 1_000;
    public static final int MAX_TOTAL_CHARACTERS = 250_000;
    public static final int MAX_PROFILE_CHARACTERS = 4_000;
    private final UserMemoryRepository repository;
    private final Clock clock;
    private Map<String, UserMemoryEntry> entries;

    public UserMemoryImpl(UserMemoryRepository repository) { this(repository, Clock.systemUTC()); }

    public UserMemoryImpl(UserMemoryRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        try {
            var loaded = repository.load();
            validate(loaded);
            entries = snapshot(loaded);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unable to restore user memory", e);
        }
    }

    @Override public synchronized Map<String, UserMemoryEntry> entries() { return entries; }

    @Override
    public synchronized void store(String key, UserMemoryEntry.Type type, String content, String source) {
        validateKey(key);
        var old = entries.get(key);
        if (old != null && old.type() != type)
            throw new IllegalArgumentException("Entry type cannot be changed by store; confirm a hypothesis explicitly or remove the entry first");
        String now = clock.instant().toString();
        var entry = new UserMemoryEntry(type, content, source, old == null ? now : old.createdAt(), now);
        var updated = new LinkedHashMap<>(entries);
        updated.put(key, entry);
        commit(updated);
    }

    @Override
    public synchronized void confirm(String key, UserMemoryEntry.Type confirmedType, String source) {
        validateKey(key);
        var old = entries.get(key);
        if (old == null || old.type() != UserMemoryEntry.Type.HYPOTHESIS)
            throw new IllegalArgumentException("Only an existing hypothesis can be confirmed");
        if (confirmedType != UserMemoryEntry.Type.FACT && confirmedType != UserMemoryEntry.Type.PREFERENCE)
            throw new IllegalArgumentException("Confirm as FACT or PREFERENCE");
        var updated = new LinkedHashMap<>(entries);
        updated.put(key, new UserMemoryEntry(confirmedType, old.content(), source, old.createdAt(), clock.instant().toString()));
        commit(updated);
    }

    @Override public synchronized void remove(String key) {
        validateKey(key);
        if (!entries.containsKey(key)) return;
        var updated = new LinkedHashMap<>(entries);
        updated.remove(key);
        commit(updated);
    }

    @Override
    public synchronized List<Match> search(String query, int limit) {
        if (query == null || query.isBlank() || query.length() > 500)
            throw new IllegalArgumentException("Search query must contain 1 to 500 characters");
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("Limit must be between 1 and 20");
        var words = Utils.tokenize(query);
        return entries.entrySet().stream().map(e -> {
            var tokens = Utils.tokenize(e.getKey() + " " + e.getValue().content());
            int score = (int) words.stream().filter(tokens::contains).count();
            return new Match(e.getKey(), e.getValue(), score);
        }).filter(m -> m.score() > 0)
                .sorted(Comparator.comparingInt(Match::score).reversed().thenComparing(Match::key))
                .limit(limit).toList();
    }

    @Override
    public synchronized Map<String, UserMemoryEntry> profile() {
        Map<String, UserMemoryEntry> profile = new LinkedHashMap<>();
        entries.entrySet().stream().filter(e -> e.getValue().type() == UserMemoryEntry.Type.PREFERENCE)
                .sorted(Map.Entry.comparingByKey()).forEach(e -> {
                    profile.put(e.getKey(), e.getValue());
                    if (profile.size() > 50 || Utils.json(profile).length() > MAX_PROFILE_CHARACTERS)
                        profile.remove(e.getKey());
                });
        return snapshot(profile);
    }

    private void commit(Map<String, UserMemoryEntry> updated) {
        validate(updated);
        var committed = snapshot(updated);
        repository.save(committed);
        entries = committed;
    }

    private static Map<String, UserMemoryEntry> snapshot(Map<String, UserMemoryEntry> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static void validate(Map<String, UserMemoryEntry> values) {
        if (values == null || values.size() > MAX_ENTRIES) throw new IllegalArgumentException("Maximum 1000 user memories");
        values.forEach((key, entry) -> { validateKey(key); Objects.requireNonNull(entry, "entry"); });
        var preferences = values.entrySet().stream()
                .filter(e -> e.getValue().type() == UserMemoryEntry.Type.PREFERENCE).toList();
        if (preferences.size() > 50 || preferences.stream()
                .mapToInt(e -> e.getKey().length() + e.getValue().content().length()).sum() > 4_000)
            throw new IllegalArgumentException("Preference budget exceeded (50 entries, 4000 key/content characters)");
        if (Utils.json(values).length() > MAX_TOTAL_CHARACTERS)
            throw new IllegalArgumentException("User memory exceeds 250000 characters");
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[a-z][a-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("Invalid user memory key (lowercase, maximum 64 characters)");
    }
}
