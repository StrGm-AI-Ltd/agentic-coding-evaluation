package com.agentbench.ui;

import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;

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

    /**
     * Parses the service's timestamp shapes — ISO with offset ("2026-09-16T00:09:04.439132+01:00",
     * "…Z"), SQL-style ("2026-09-15 03:02:30[.frac][+off]", naive treated as UTC) — for sorting;
     * null/blank/unparseable yield null.
     */
    public static OffsetDateTime parseTime(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        String t = timestamp.trim().replace(' ', 'T').replaceAll("([+-]\\d{2})$", "$1:00");
        try {
            return OffsetDateTime.parse(t);
        } catch (Exception ignored) {
            // fall through to the naive form
        }
        try {
            return LocalDateTime.parse(t).atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Sort comparator for a timestamp column: chronological across the service's formats, absent last. */
    public static <T> Comparator<T> comparingTime(Function<T, String> timeGetter) {
        return Comparator.comparing(timeGetter.andThen(Fmt::parseTime),
                Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /** Sort comparator for any nullable comparable column value; absent values last. */
    public static <T, V extends Comparable<? super V>> Comparator<T> nullsLast(Function<T, V> valueGetter) {
        return Comparator.comparing(valueGetter, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
