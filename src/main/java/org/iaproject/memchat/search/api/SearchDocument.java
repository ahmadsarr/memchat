package org.iaproject.memchat.search.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SearchDocument(String id, String content, Map<String, String> metadata) {
    public SearchDocument {
        metadata = metadata == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
