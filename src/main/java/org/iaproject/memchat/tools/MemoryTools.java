package org.iaproject.memchat.tools;

public interface MemoryTools {

    @Tool(description = """
            Search persistent memory for information relevant to a query.
            Use when answering questions that depend on previously
            stored knowledge or before storing potentially duplicate facts.
            Returns matching memories with their IDs and content.
            """)
    String search(
            @Param(description = "Semantic search query describing the information to retrieve.")
            String query,

            @Param(description = "Maximum number of results to return, between 1 and 20.")
            int limit
    );

    @Tool(description = """
            Store new, durable, and reusable information in persistent memory.
            Use only when the information is relevant and worth remembering.
            Search first to avoid duplicates.
            Do not store unsupported assumptions as verified facts.
            Returns the ID of the newly created memory.
            """)
    String store(
            @Param(description = "Clear, self-contained knowledge to persist for future use.")
            String content
    );

    @Tool(description = """
            Update an existing memory identified by its ID.
            Use when new information corrects, extends, or supersedes
            previously stored knowledge.
            Preserve important details and avoid unsupported changes.
            Returns the updated memory or an error.
            """)
    String update(
            @Param(description = "Unique ID of the existing memory to update.")
            String id,

            @Param(description = "Complete replacement content, including all facts that must be preserved.")
            String content
    );

    @Tool(description = """
            Delete a memory identified by its ID.
            Use when the user explicitly requests deletion or when
            the memory is confirmed to be invalid.
            Never delete a memory based only on uncertainty.
            Returns the deletion status.
            """)
    String delete(
            @Param(description = "Unique ID of the memory to permanently delete.")
            String id
    );

    @Tool(description = """
            Retrieve a specific memory by its unique ID.
            Use when the exact memory must be inspected before
            updating, deleting, or referencing it.
            Returns the memory ID and content, or a not-found error.
            """)
    String get(
            @Param(description = "Unique ID of the memory to retrieve.")
            String id
    );

    @Tool(description = """
            List existing memories up to the specified limit.
            Use to inspect available knowledge when no specific
            search query is known.
            Returns memory IDs and their content.
            Prefer search when looking for specific information.
            """)
    String list(
            @Param(description = "Maximum number of memories to return, between 1 and 20.")
            int limit
    );
}