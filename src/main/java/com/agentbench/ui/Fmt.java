package com.agentbench.ui;

import tools.jackson.databind.JsonNode;

import java.util.Locale;

/** Small formatting helpers mirroring the Jinja2 macros in the original UI (_macros.html). */
public final class Fmt {

    private Fmt() {
    }

    public static String pct(Double value) {
        return value == null ? "–" : String.format(Locale.ROOT, "%.1f", value);
    }

    public static String points(Integer got, Integer denominator) {
        return (got == null ? "?" : got) + "/" + (denominator == null ? "?" : denominator);
    }

    public static String duration(Double seconds) {
        if (seconds == null) {
            return "–";
        }
        long total = seconds.longValue();
        return (total / 3600) + "h" + String.format(Locale.ROOT, "%02d", (total % 3600) / 60) + "m";
    }

    public static String count(Long value) {
        return value == null ? "–" : String.format(Locale.ROOT, "%,d", value);
    }

    /** Compact "2026-09-15 23:57" from whatever string timestamp the service sent. */
    public static String when(String timestamp) {
        if (timestamp == null) {
            return "–";
        }
        String t = timestamp.replace('T', ' ').replace("Z", "");
        return t.length() > 16 ? t.substring(0, 16) : t;
    }

    public static String num(Double value) {
        if (value == null) {
            return "–";
        }
        return value == Math.floor(value) ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    public static String json(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "–";
        }
        try {
            return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    /**
     * Jackson 3-safe node text: unlike Jackson 2, JsonNode.asText() THROWS on
     * non-textual nodes, so never call it blindly — textual nodes return their
     * text, other scalars/containers their JSON form, absent/null the fallback.
     */
    public static String textOr(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }
}
