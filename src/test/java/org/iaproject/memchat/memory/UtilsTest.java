package org.iaproject.memchat.memory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UtilsTest {
    @Test
    void tokenizationNormalizesCaseAccentsAndStopWords() {
        var tokens = Utils.tokenize("Les préférences de Mohamed");

        assertEquals(2, tokens.size());
        assertTrue(tokens.contains("preferences"));
        assertTrue(tokens.contains("mohamed"));
    }

    @Test
    void serializesValuesAsJson() {
        assertEquals("{\"success\":true}", Utils.json(Map.of("success", true)));
    }

    @Test
    void createsStandardErrors() {
        assertTrue(Utils.error("invalid").contains("invalid"));
        assertTrue(Utils.notFound("id-1").contains("id-1"));
    }
}
