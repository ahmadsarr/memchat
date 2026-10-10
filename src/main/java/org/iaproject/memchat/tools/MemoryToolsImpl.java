package org.iaproject.memchat.tools;

import java.util.*;
import java.util.stream.Collectors;

public class MemoryToolsImpl implements MemoryTools {
    private Map<String, String> memory = new HashMap<>();
    private static final Set<String> FRENCH_STOP_WORDS = Set.of(
            "le", "la", "les", "un", "une", "des", "du", "de",
            "d", "l", "au", "aux", "à", "en", "dans", "sur",
            "sous", "par", "pour", "avec", "sans", "chez",
            "et", "ou", "mais", "donc", "car", "ni",
            "ce", "cet", "cette", "ces", "ça", "cela",
            "je", "tu", "il", "elle", "nous", "vous", "ils",
            "elles", "me", "te", "se", "moi", "toi",
            "mon", "ma", "mes", "ton", "ta", "tes",
            "son", "sa", "ses", "notre", "nos", "votre", "vos",
            "leur", "leurs",
            "qui", "que", "quoi", "dont", "où",
            "est", "sont", "être", "été", "était",
            "ai", "as", "avons", "avez", "ont",
            "a", "avait", "avoir",
            "fait", "faire", "plus", "très",
            "comme", "aussi", "alors", "encore",
            "tout", "tous", "toute", "toutes",
            "ici", "là", "y"
    );
    private static final Set<String> ENGLISH_STOP_WORDS = Set.of(
            "the", "a", "an", "and", "or", "but",
            "of", "to", "in", "on", "at", "by",
            "for", "from", "with", "without",
            "about", "into", "over", "under",
            "is", "are", "was", "were", "be",
            "been", "being", "am",
            "have", "has", "had", "having",
            "do", "does", "did", "doing",
            "i", "you", "he", "she", "it", "we", "they",
            "me", "him", "her", "us", "them",
            "my", "your", "his", "its", "our", "their",
            "this", "that", "these", "those",
            "who", "whom", "which", "what",
            "where", "when", "how",
            "as", "if", "then", "than",
            "so", "because", "while", "although",
            "also", "just", "very", "too",
            "can", "could", "would", "should",
            "will", "shall", "may", "might",
            "must", "some", "any", "all",
            "each", "every", "other", "more", "most"
    );

    @Override
    @Tool(description = """
            Search persistent memory for information relevant to a query.
            Use when answering questions that depend on previously
            stored knowledge or before storing potentially duplicate facts.
            Returns matching memories with their IDs and content.
            """)
    public String search(
            @Param(description = "Semantic search query describing the information to retrieve.")
            String query,

            @Param(description = "Maximum number of results to return, between 1 and 20.")
            int limit
    ) {
        if (query == null || query.isBlank() || limit <= 0) {
            return "[]";
        }

        Set<String> keywords = Arrays.stream(query.toLowerCase(Locale.ROOT)
                        .split("\\W+"))
                .filter(word -> !word.isBlank())
                .filter(word -> !ENGLISH_STOP_WORDS.contains(word) && !FRENCH_STOP_WORDS.contains(word))
                .collect(Collectors.toSet());

        if (keywords.isEmpty()) {
            return "[]";
        }

        return memory.entrySet().stream()
                .map(entry -> {
                    String content = entry.getValue().toLowerCase(Locale.ROOT);

                    long score = keywords.stream()
                            .filter(content::contains)
                            .count();

                    return new SearchResult(
                            entry.getKey(),
                            entry.getValue(),
                            score
                    );
                })
                .filter(result -> result.score() > 0)
                .sorted(Comparator.comparingLong(SearchResult::score).reversed())
                .limit(limit)
                .map(result -> """
                        ID: %s
                        Content: %s
                        Score: %d
                        """.formatted(
                        result.id(),
                        result.content(),
                        result.score()
                ))
                .collect(Collectors.joining("\n"));
    }

    private record SearchResult(
            String id,
            String content,
            long score
    ) {
    }

    @Tool(description = """
            Store new, durable, and reusable information in persistent memory.
            Use only when the information is relevant and worth remembering.
            Search first to avoid duplicates.
            Do not store unsupported assumptions as verified facts.
            Returns the ID of the newly created memory.
            """)
    public String store(
            @Param(description = "Clear, self-contained knowledge to persist for future use.")
            String content
    ) {
        return "";
    }

    @Tool(description = """
            Update an existing memory identified by its ID.
            Use when new information corrects, extends, or supersedes
            previously stored knowledge.
            Preserve important details and avoid unsupported changes.
            Returns the updated memory or an error.
            """)
    public String update(
            @Param(description = "Unique ID of the existing memory to update.")
            String id,

            @Param(description = "Complete replacement content, including all facts that must be preserved.")
            String content
    ) {
        return "";
    }

    @Tool(description = """
            Delete a memory identified by its ID.
            Use when the user explicitly requests deletion or when
            the memory is confirmed to be invalid.
            Never delete a memory based only on uncertainty.
            Returns the deletion status.
            """)
    public String delete(
            @Param(description = "Unique ID of the memory to permanently delete.")
            String id
    ) {
        return "";
    }

    @Tool(description = """
            Retrieve a specific memory by its unique ID.
            Use when the exact memory must be inspected before
            updating, deleting, or referencing it.
            Returns the memory ID and content, or a not-found error.
            """)
    public String get(
            @Param(description = "Unique ID of the memory to retrieve.")
            String id
    ) {
        return "";
    }

    @Tool(description = """
            List existing memories up to the specified limit.
            Use to inspect available knowledge when no specific
            search query is known.
            Returns memory IDs and their content.
            Prefer search when looking for specific information.
            """)
    public String list(
            @Param(description = "Maximum number of memories to return, between 1 and 20.")
            int limit
    ) {
        return "";
    }
}
