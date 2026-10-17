package org.iaproject.memchat.search.api;

import java.util.Map;

public record SearchDocument(String id, String content, Map<String, String> metadata) {
}
