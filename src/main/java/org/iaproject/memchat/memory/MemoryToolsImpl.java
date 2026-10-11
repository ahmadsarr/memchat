package org.iaproject.memchat.memory;

import java.util.*;
import org.iaproject.memchat.tools.MemoryTools;
import org.iaproject.memchat.tools.Param;
import org.iaproject.memchat.tools.Tool;

import java.util.logging.Logger;

import static org.iaproject.memchat.memory.Utils.*;

public class MemoryToolsImpl implements MemoryTools {

    private final MemoryManager memoryManager;
    private static final Logger LOGGER = Logger.getLogger(MemoryToolsImpl.class.getName());

    public MemoryToolsImpl(MemoryManager memoryManager) {

        this.memoryManager = memoryManager;
    }


    @Override
    @Tool(description = """
            Search persistent memory for information relevant to a query.
            Use when answering questions about previously stored knowledge
            or before storing potentially duplicate facts.
            Returns matching memories with their IDs, content and scores.
            """)
    public String archivalSearch(
            @Param(description = "Search query")
            String query,
            @Param(description = "Maximum number of results, between 1 and 20")
            int limit
    ) {
        LOGGER.info("Memory search started (query=" + query + ", limit=" + limit + ")");

        List<Memory.MemorySearchResult> results = memoryManager.searchArchive(query, limit);

        LOGGER.info("Memory search completed (results=" + results.size() + ")");

        return json(Map.of(
                "success", true,
                "count", results.size(),
                "memories", results
        ));
    }

    @Override
    @Tool(description = """
            Store new, durable, reusable information in memory.
            Search first to avoid duplicates.
            Returns the ID of the created memory.
            """)
    public String archivalStore(
            @Param(description = "Knowledge to persist")
            String content
    ) {
        LOGGER.info("Memory store started (contentLength=" + (content == null ? 0 : content.length()) + ")");

        String id = memoryManager.archive(content);

        return json(Map.of(
                "success", true,
                "id", id,
                "operation", "store"
        ));
    }

    @Override
    @Tool(description = """
            Update an existing memory by its ID.
            Preserve important information when replacing content.
            Returns the updated memory.
            """)
    public String archivalUpdate(
            @Param(description = "Existing memory ID")
            String id,
            @Param(description = "Complete replacement content")
            String content
    ) {
        LOGGER.info("Memory update started (id=" + id + ")");


        memoryManager.update(id, content);
        LOGGER.info("Memory updated (id=" + id + ")");

        return json(Map.of(
                "success", true,
                "id", id,
                "operation","updated"
        ));
    }

    @Override
    @Tool(description = """
            Delete a memory by its ID.
            Use only when deletion is explicitly requested
            or the memory is confirmed invalid.
            """)
    public String archivalDelete(
            @Param(description = "Memory ID to delete")
            String id
    ) {
        LOGGER.info("Memory delete started (id=" + id + ")");

        memoryManager.delete(id);

        LOGGER.info("Memory deleted (id=" + id + ")");

        return json(Map.of(
                "success", true,
                "id", id,
                "operation", "deleted"
        ));
    }

    @Override
    @Tool(description = """
            Retrieve an existing memory by its unique ID.
            Returns the memory ID and content.
            """)
    public String getArchive(
            @Param(description = "Memory ID to retrieve")
            String id
    ) {
        LOGGER.info("Memory get started (id=" + id + ")");

        if (id == null || id.isBlank()) {
            return error("Memory ID is required");
        }

        String content = memoryManager.getById(id);

        if (content == null) {
            return notFound(id);
        }

        LOGGER.info("Memory retrieved (id=" + id + ")");

        return json(Map.of(
                "success", true,
                "id", id,
                "content", content
        ));
    }

    @Override
    @Tool(description = "Search recent conversation memory.")
    public String recallSearch(String query, int limit) {
        LOGGER.info("Recall search started (query=" + query + ", limit=" + limit + ")");
        List<Memory.RecallSearchResult> results = memoryManager.searchRecall(query, limit);
        return json(Map.of(
                "success", true,
                "count", results.size(),
                "memories", results
        ));
    }

    @Override
    @Tool(description = "Append content to a working-memory section.")
    public String coreAppend(
            @Param(description = "Working-memory section") String section,
            @Param(description = "Content to append") String content
    ) {
        memoryManager.appendCore(section, content);
        return json(Map.of("success", true, "operation", "core_append", "section", section));
    }

    @Override
    @Tool(description = "Replace content in a working-memory section.")
    public String coreReplace(
            @Param(description = "Working-memory section") String section,
            @Param(description = "Replacement content") String content
    ) {
        memoryManager.replaceCore(section, content);
        return json(Map.of("success", true, "operation", "core_replace", "section", section));
    }

    @Override
    @Tool(description = "Remove a working-memory section.")
    public String coreRemove(
            @Param(description = "Working-memory section") String section
    ) {
        memoryManager.removeCore(section);
        return json(Map.of("success", true, "operation", "core_remove", "section", section));
    }



}
