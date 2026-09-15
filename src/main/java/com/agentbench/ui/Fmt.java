package com.agentbench.ui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Small formatting helpers mirroring the Jinja2 macros in the original UI (_macros.html). */
public final class Fmt {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private Fmt() {
    }

    public static String pct(Double value) {
        return value == null ? "–" : String.format("%.1f", value);
    }

    public static String points(Integer got, Integer denominator) {
        return (got == null ? "?" : got) + "/" + (denominator == null ? "?" : denominator);
    }

    public static String duration(Double seconds) {
        if (seconds == null) {
            return "–";
        }
        long total = seconds.longValue();
        return (total / 3600) + "h" + String.format("%02d", (total % 3600) / 60) + "m";
    }

    public static String count(Long value) {
        return value == null ? "–" : String.format("%,d", value);
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
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }
}
