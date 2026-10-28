package org.iaproject.memchat.tools;

import org.iaproject.memchat.memory.UserMemory;
import org.iaproject.memchat.memory.UserMemoryEntry;
import java.util.Map;
import java.util.Objects;
import static org.iaproject.memchat.memory.Utils.json;

public final class UserMemoryTools {
    private final UserMemory memory;
    public UserMemoryTools(UserMemory memory) { this.memory = Objects.requireNonNull(memory); }

    @Tool(description = "Search personal preferences, facts and hypotheses by keywords. Results include type, source and dates. Hypotheses are unconfirmed. Search before storing and before answering about the user.")
    public String userMemorySearch(
            @Param(description = "Keywords describing the personal information to retrieve") String query,
            @Param(description = "Maximum results, from 1 to 20") int limit) {
        return json(Map.of("success", true, "memories", memory.search(query, limit)));
    }

    @Tool(description = "Store or correct personal information using a stable key. Use PREFERENCE for explicit choices, FACT for supported personal facts, HYPOTHESIS for tentative inferences. Always cite the actual evidence in source; never invent sources or store secrets. Do not duplicate personal information in archival memory. Type changes require explicit confirmation or forgetting first.")
    public String userMemoryStore(
            @Param(description = "Stable lowercase identifier, maximum 64 characters") String key,
            @Param(description = "PREFERENCE, FACT or HYPOTHESIS (uppercase)") UserMemoryEntry.Type type,
            @Param(description = "Self-contained personal information, maximum 500 characters") String content,
            @Param(description = "Actual supporting user quote or source reference; for hypotheses include the reasoning, maximum 500 characters") String source) {
        memory.store(key, type, content, source);
        return json(Map.of("success", true, "key", key));
    }

    @Tool(description = "Retrieve a personal memory by its exact key, including its type, source and dates.")
    public String userMemoryGet(@Param(description = "Exact personal memory key") String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Key is required");
        var entry = memory.entries().get(key);
        return entry == null ? json(Map.of("success", false, "key", key, "error", "Not found"))
                : json(Map.of("success", true, "key", key, "entry", entry));
    }

    @Tool(description = "Promote an existing hypothesis only after explicit user confirmation. Keep its content and creation date; cite the confirming statement. Use userMemoryStore first if the content needs correction.")
    public String userMemoryConfirm(
            @Param(description = "Exact key of an existing HYPOTHESIS") String key,
            @Param(description = "FACT or PREFERENCE (uppercase)") UserMemoryEntry.Type confirmedType,
            @Param(description = "Actual user statement confirming this hypothesis, maximum 500 characters") String source) {
        memory.confirm(key, confirmedType, source);
        return json(Map.of("success", true, "key", key));
    }

    @Tool(description = "Forget a personal memory only when the user requests deletion or confirms it is invalid. This does not erase original conversation history from recall.")
    public String userMemoryForget(@Param(description = "Exact personal memory key to remove") String key) {
        memory.remove(key);
        return json(Map.of("success", true, "key", key));
    }
}
