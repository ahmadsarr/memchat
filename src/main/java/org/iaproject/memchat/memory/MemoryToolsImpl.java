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
            Search long-term archival memory for facts relevant to the user's query.
            Use this before storing information to check for existing or duplicate facts.
            Results include the memory ID, stored content, and relevance score.
            """)
    public String archivalSearch(
            @Param(description = "Specific keywords or a short description of the fact to find")
            String query,
            @Param(description = "Maximum number of matching memories to return, from 1 to 20")
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
            Store a durable fact, preference, goal, or other information that should
            remain available across future conversations. Search first and do not store
            temporary remarks, duplicates, or unsupported assumptions. Returns the new ID.
            """)
    public String archivalStore(
            @Param(description = "Clear, self-contained information to preserve for future conversations")
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
            Replace the complete content of an existing archival memory.
            Use this when a stored fact has changed or needs correction. Preserve
            all still-valid details and provide the memory ID returned by search or get.
            """)
    public String archivalUpdate(
            @Param(description = "ID of the archival memory to replace")
            String id,
            @Param(description = "Complete updated content, including all facts to keep")
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
            Permanently delete an archival memory by its ID.
            Use only when the user explicitly requests deletion or the stored fact
            has been confirmed invalid. Never delete based on uncertainty alone.
            """)
    public String archivalDelete(
            @Param(description = "ID of the archival memory to permanently delete")
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
            Retrieve the exact content of an archival memory by its unique ID.
            Use this before updating or deleting a memory when its current content
            must be verified.
            """)
    public String getArchive(
            @Param(description = "Unique ID of the archival memory to retrieve")
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
    @Tool(description = """
            Search recent conversation history for information from the current
            or previous exchanges. Use this when the answer may exist in recent
            context but is not part of long-term archival memory.
            """)
    public String recallSearch(
            @Param(description = "Keywords or a short description of the recent information to find") String query,
            @Param(description = "Maximum number of results to return, from 1 to 20") int limit
    ) {
        LOGGER.info("Recall search started (query=" + query + ", limit=" + limit + ")");
        List<Memory.RecallSearchResult> results = memoryManager.searchRecall(query, limit);
        return json(Map.of(
                "success", true,
                "count", results.size(),
                "memories", results
        ));
    }

    @Override
    @Tool(description = """
            Append important information to a named working-memory section.
            Use this for compact, active context that should remain available
            during the current task.
            """)
    public String coreAppend(
            @Param(description = "Name of the working-memory section") String section,
            @Param(description = "Important context to add to that section") String content
    ) {
        memoryManager.appendCore(section, content);
        return json(Map.of("success", true, "operation", "core_append", "section", section));
    }

    @Override
    @Tool(description = """
            Replace the contents of a working-memory section with the latest
            authoritative information. Use this to correct or refresh active context.
            """)
    public String coreReplace(
            @Param(description = "Name of the working-memory section") String section,
            @Param(description = "Complete replacement content for that section") String content
    ) {
        memoryManager.replaceCore(section, content);
        return json(Map.of("success", true, "operation", "core_replace", "section", section));
    }

    @Override
    @Tool(description = """
            Remove a working-memory section when its information is obsolete
            or no longer relevant to the current task.
            """)
    public String coreRemove(
            @Param(description = "Name of the working-memory section to remove") String section
    ) {
        memoryManager.removeCore(section);
        return json(Map.of("success", true, "operation", "core_remove", "section", section));
    }



}
