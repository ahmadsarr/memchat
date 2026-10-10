package org.iaproject.memchat.tools;

import java.util.*;
import java.util.stream.Collectors;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.text.Normalizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

public class MemoryToolsImpl implements MemoryTools {

    private final Map<String, String> memory = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Logger LOGGER = Logger.getLogger(MemoryToolsImpl.class.getName());
    private static final Path MEMORY_FILE = Path.of("memory.json");

    public MemoryToolsImpl() {
        try {
            loadMemory();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Set<String> STOP_WORDS = Set.of(
            // Français
            "le", "la", "les", "un", "une", "des", "du", "de",
            "d", "l", "au", "aux", "a", "en", "dans", "sur",
            "sous", "par", "pour", "avec", "sans", "chez",
            "et", "ou", "mais", "donc", "car", "ni",
            "ce", "cet", "cette", "ces", "ca", "cela",
            "je", "tu", "il", "elle", "nous", "vous", "ils",
            "elles", "me", "te", "se", "moi", "toi",
            "mon", "ma", "mes", "ton", "ta", "tes",
            "son", "sa", "ses", "notre", "nos", "votre", "vos",
            "leur", "leurs", "qui", "que", "quoi", "dont",
            "est", "sont", "etre", "ete", "etait",
            "ai", "avons", "avez", "ont",
            "avait", "avoir", "fait", "faire", "plus", "tres",
            "comme", "aussi", "alors", "encore",
            "tout", "tous", "toute", "toutes", "ici", "y",

            // Anglais
            "the", "an", "and", "or", "but", "of", "to",
            "in", "on", "at", "by", "for", "from", "with",
            "without", "about", "into", "over", "under",
            "is", "are", "was", "were", "be", "been", "being",
            "am", "have", "has", "had", "having",
            "do", "does", "did", "doing",
            "i", "you", "he", "she", "it", "we", "they",
            "him", "her", "us", "them", "my", "your",
            "his", "its", "our", "their", "this", "that",
            "these", "those", "who", "whom", "which", "what",
            "where", "when", "how", "as", "if", "then",
            "than", "so", "because", "while", "although",
            "also", "just", "very", "too",
            "can", "could", "would", "should", "will",
            "shall", "may", "might", "must",
            "some", "any", "all", "each", "every",
            "other", "more", "most"
    );

    private record MemoryEntry(String id, String content) {}

    private record SearchResult(
            String id,
            String content,
            long score
    ) {}

    @Override
    @Tool(description = """
            Search persistent memory for information relevant to a query.
            Use when answering questions about previously stored knowledge
            or before storing potentially duplicate facts.
            Returns matching memories with their IDs, content and scores.
            """)
    public String search(
            @Param(description = "Search query")
            String query,
            @Param(description = "Maximum number of results, between 1 and 20")
            int limit
    ) {
        LOGGER.info("Memory search started (query=" + query + ", limit=" + limit + ")");

        if (query == null || query.isBlank()) {
            return error("Search query cannot be empty");
        }

        if (limit < 1 || limit > 20) {
            return error("Limit must be between 1 and 20");
        }

        Set<String> keywords = tokenize(query);

        if (keywords.isEmpty()) {
            return json(Map.of(
                    "success", true,
                    "count", 0,
                    "memories", List.of()
            ));
        }

        List<SearchResult> results = memory.entrySet().stream()
                .map(entry -> {
                    Set<String> tokens = tokenize(entry.getValue());

                    long score = keywords.stream()
                            .filter(tokens::contains)
                            .count();

                    return new SearchResult(
                            entry.getKey(),
                            entry.getValue(),
                            score
                    );
                })
                .filter(result -> result.score() > 0)
                .sorted(
                        Comparator.comparingLong(SearchResult::score)
                                .reversed()
                )
                .limit(limit)
                .toList();

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
    public String store(
            @Param(description = "Knowledge to persist")
            String content
    ) {
        LOGGER.info("Memory store started (contentLength="
                + (content == null ? 0 : content.length()) + ")");

        if (content == null || content.isBlank()) {
            return error("Memory content cannot be empty");
        }

        String id = UUID.randomUUID().toString();
        String normalizedContent = content.trim();

        memory.put(id, normalizedContent);
        saveMemory();
        LOGGER.info("Memory stored (id=" + id + ")");

        return json(Map.of(
                "success", true,
                "id", id,
                "content", normalizedContent
        ));
    }

    @Override
    @Tool(description = """
            Update an existing memory by its ID.
            Preserve important information when replacing content.
            Returns the updated memory.
            """)
    public String update(
            @Param(description = "Existing memory ID")
            String id,
            @Param(description = "Complete replacement content")
            String content
    ) {
        LOGGER.info("Memory update started (id=" + id + ")");

        if (id == null || id.isBlank()) {
            return error("Memory ID is required");
        }

        if (content == null || content.isBlank()) {
            return error("Memory content cannot be empty");
        }

        if (!memory.containsKey(id)) {
            return notFound(id);
        }

        String normalizedContent = content.trim();
        memory.put(id, normalizedContent);
        saveMemory();
        LOGGER.info("Memory updated (id=" + id + ")");

        return json(Map.of(
                "success", true,
                "id", id,
                "content", normalizedContent
        ));
    }

    @Override
    @Tool(description = """
            Delete a memory by its ID.
            Use only when deletion is explicitly requested
            or the memory is confirmed invalid.
            """)
    public String delete(
            @Param(description = "Memory ID to delete")
            String id
    ) {
        LOGGER.info("Memory delete started (id=" + id + ")");

        if (id == null || id.isBlank()) {
            return error("Memory ID is required");
        }

        String removed = memory.remove(id);

        if (removed == null) {
            return notFound(id);
        }

        saveMemory();
        LOGGER.info("Memory deleted (id=" + id + ")");

        return json(Map.of(
                "success", true,
                "id", id,
                "deleted", true
        ));
    }

    @Override
    @Tool(description = """
            Retrieve an existing memory by its unique ID.
            Returns the memory ID and content.
            """)
    public String get(
            @Param(description = "Memory ID to retrieve")
            String id
    ) {
        LOGGER.info("Memory get started (id=" + id + ")");

        if (id == null || id.isBlank()) {
            return error("Memory ID is required");
        }

        String content = memory.get(id);

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
            List stored memories up to the specified limit.
            Use when no specific search query is known.
            """)
    public String list(
            @Param(description = "Maximum number of memories, between 1 and 20")
            int limit
    ) {
        LOGGER.info("Memory list started (limit=" + limit + ")");

        if (limit < 1 || limit > 20) {
            return error("Limit must be between 1 and 20");
        }

        List<MemoryEntry> entries = memory.entrySet().stream()
                .limit(limit)
                .map(entry -> new MemoryEntry(
                        entry.getKey(),
                        entry.getValue()
                ))
                .toList();

        LOGGER.info("Memory list completed (results=" + entries.size() + ")");

        return json(Map.of(
                "success", true,
                "count", entries.size(),
                "memories", entries
        ));
    }

    private Set<String> tokenize(String text) {
        String normalized = Normalizer.normalize(
                text.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}+", "");

        return Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isBlank())
                .filter(word -> !STOP_WORDS.contains(word))
                .collect(Collectors.toSet());
    }

    private String error(String message) {
        return json(Map.of(
                "success", false,
                "error", message
        ));
    }

    private String notFound(String id) {
        return json(Map.of(
                "success", false,
                "error", "Memory not found",
                "id", id
        ));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Memory serialization failed", e
            );
        }
    }

    private void loadMemory() throws IOException {
        if (!Files.exists(MEMORY_FILE)) {
            LOGGER.info("Memory file not found, starting with empty memory");
            Files.createFile(MEMORY_FILE);
            return;
        }

        try {
            memory.putAll(mapper.readValue(
                    MEMORY_FILE.toFile(),
                    new TypeReference<LinkedHashMap<String, String>>() {}
            ));
            LOGGER.info("Memory loaded (count=" + memory.size() + ")");
        } catch (IOException | RuntimeException e) {
            LOGGER.severe("Unable to load memory file: " + e.getMessage());
        }
    }

    private void saveMemory() {
        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(MEMORY_FILE.toFile(), memory);
            LOGGER.info("Memory saved (count=" + memory.size() + ")");
        } catch (IOException e) {
            LOGGER.severe("Unable to save memory file: " + e.getMessage());
        }
    }
}
