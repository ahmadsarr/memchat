package org.iaproject.memchat.memory.strategy;

import java.util.List;
import java.util.Map;

public interface SemanticSearchStrategy {
    record Document(String id, String content, Map<String, String> metadata) {
    }

    void index(Document document);

    List<Document> search(String query);

    void remove(String documentId);

}
