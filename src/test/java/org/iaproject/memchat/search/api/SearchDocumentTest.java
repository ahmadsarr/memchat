package org.iaproject.memchat.search.api;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SearchDocumentTest {
    @Test
    void metadataIsAnImmutableSnapshot() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("session", "first");
        var document = new SearchDocument("id", "content", metadata);
        metadata.put("session", "second");
        assertEquals("first", document.metadata().get("session"));
        assertThrows(UnsupportedOperationException.class,
                () -> document.metadata().put("session", "third"));
        assertThrows(UnsupportedOperationException.class,
                () -> document.metadata().entrySet().iterator().next().setValue("third"));
        assertEquals(new SearchDocument("id", "content", Map.of("session", "first")), document);
    }

    @Test
    void preservesNullMetadataAndExistingMapContents() {
        assertNull(new SearchDocument("id", "content", null).metadata());
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("nullable", null);
        assertEquals(metadata, new SearchDocument("id", "content", metadata).metadata());
        assertTrue(new SearchDocument("id", "content", Map.of()).metadata().isEmpty());
    }
}
