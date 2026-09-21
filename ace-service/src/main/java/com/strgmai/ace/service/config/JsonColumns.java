package com.strgmai.ace.service.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Postgres returned jsonb columns as a typed PGobject that a single global Jackson serializer
 *  (formerly PGobjectJsonSerializer) rewrote as real nested JSON. SQLite has no such type - these
 *  columns are plain TEXT, so jOOQ/JDBC hands back a plain String, and Jackson would otherwise
 *  serialize it as a JSON STRING (escaped, double-encoded) instead of a nested object. This does the
 *  same job at the query-result boundary instead of the serializer layer: parse the known JSON-holding
 *  columns into a JsonNode before the row leaves the service layer. */
public final class JsonColumns {
    private JsonColumns() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> KEYS = Set.of(
            "argv", "params", "comparison", "manifest", "oracle", "metrics", "validity_reasons", "detail");

    public static Map<String, Object> parse(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        for (String k : KEYS)
            if (out.get(k) instanceof String s && !s.isBlank())
                try { out.put(k, MAPPER.readTree(s)); } catch (Exception ignore) { /* leave as raw text */ }
        return out;
    }

    public static List<Map<String, Object>> parseAll(List<Map<String, Object>> rows) {
        return rows.stream().map(JsonColumns::parse).toList();
    }
}
