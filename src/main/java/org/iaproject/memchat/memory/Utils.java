package org.iaproject.memchat.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public class Utils {

    private static final ObjectMapper mapper = new ObjectMapper();
    public static final Set<String> STOP_WORDS = Set.of(
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

    public static String error(String message) {
        return json(Map.of(
                "success", false,
                "error", message
        ));
    }

    public static String notFound(String id) {
        return json(Map.of(
                "success", false,
                "error", "Memory not found",
                "id", id
        ));
    }

    public static String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Memory serialization failed", e
            );
        }
    }

    public static Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isBlank())
                .filter(word -> !STOP_WORDS.contains(word))
                .collect(Collectors.toSet());
    }
}
