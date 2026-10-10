package com.strgmai.ace.service.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #286, found live: "detail" (CheckResult's free-text message, no JSON default unlike the real
 *  JSON columns) used to be in KEYS - parse() tried MAPPER.readTree(s) on it unconditionally,
 *  throwing-and-logging a WARN for most non-trivial detail strings, and silently retyping a detail
 *  that happened to parse as valid JSON (a bare "404", "true", "null") from a string into a
 *  number/boolean/null instead. */
class JsonColumnsTest {

    @Test
    void detailStaysAPlainStringEvenWhenItLooksLikeJson() {
        final var out = JsonColumns.parse(Map.of("detail", "404", "manifest", "{\"a\":1}"));
        assertEquals("404", out.get("detail"), "detail must never be reinterpreted as a JSON number/boolean/null");
        assertTrue(out.get("detail") instanceof String);
    }

    @Test
    void detailWithProseNeverThrowsOrLogsAJsonParseWarning() {
        final var out = JsonColumns.parse(Map.of("detail", "expected 3 services, found 2 (gateway, order-service)"));
        assertEquals("expected 3 services, found 2 (gateway, order-service)", out.get("detail"));
    }

    @Test
    void realJsonColumnsAreStillParsedIntoNestedObjects() {
        final var out = JsonColumns.parse(Map.of("manifest", "{\"run_id\":\"r1\"}"));
        assertTrue(out.get("manifest") instanceof com.fasterxml.jackson.databind.JsonNode,
                "a real JSON column must still be parsed into a JsonNode, not left as a raw string");
        assertEquals("r1", ((com.fasterxml.jackson.databind.JsonNode) out.get("manifest")).path("run_id").asText());
    }
}
