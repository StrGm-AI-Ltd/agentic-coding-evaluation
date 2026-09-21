package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FmtTest {

    @Test
    void pct_nullAndFormats() {
        assertEquals("–", Fmt.pct(null));
        assertEquals("53.2", Fmt.pct(53.249));
        assertEquals("0.0", Fmt.pct(0.0));
        assertEquals("100.0", Fmt.pct(100.0));
    }

    @Test
    void points_nullQuestionMarks() {
        assertEquals("?/?", Fmt.points(null, null));
        assertEquals("18/20", Fmt.points(18, 20));
        assertEquals("?/20", Fmt.points(null, 20));
    }

    @Test
    void duration_hoursAndMinutes() {
        assertEquals("1h02m", Fmt.duration(3725.0));
        assertEquals("0h00m", Fmt.duration(59.9));
        assertEquals("0h01m", Fmt.duration(60.0));
        assertEquals("–", Fmt.duration(null));
    }

    @Test
    void count_localeIndependentAndNull() {
        assertEquals("–", Fmt.count(null));
        assertEquals("1,234", Fmt.count(1234L));
    }

    @Test
    void when_trimsIsoTimestamp() {
        assertEquals("2026-09-15 23:57", Fmt.when("2026-09-15T23:57:00Z"));
        assertEquals("2026-09-15 23:57", Fmt.when("2026-09-15 23:57:12.532324+00"));
        assertEquals("–", Fmt.when(null));
        assertEquals("short", Fmt.when("short"));
    }

    @Test
    void num_integralVsFractional() {
        assertEquals("3", Fmt.num(3.0));
        assertEquals("3.5", Fmt.num(3.5));
        assertEquals("–", Fmt.num(null));
    }

    @Test
    void json_nullMissingAndPrettyPrints() {
        assertEquals("–", Fmt.json(null));
        assertEquals("–", Fmt.json(tools.jackson.databind.node.JsonNodeFactory.instance.missingNode()));
        final var printed = Fmt.json(Json.MAPPER.readTree("{\"a\":1}"));
        assertEquals("""
                {
                  "a" : 1
                }""", printed);
    }

    /**
     * The 2026-09-16 provenance crash: Jackson 3's asText() throws on non-textual
     * nodes, so Fmt.textOr must never throw — whatever the node holds.
     */
    @Test
    void textOr_neverThrowsOnAnyNodeShape() {
        assertEquals("plain", Fmt.textOr(Json.MAPPER.readTree("\"plain\""), "–"));
        assertEquals("42", Fmt.textOr(Json.MAPPER.readTree("42"), "–"), "numbers stringify");
        assertEquals("true", Fmt.textOr(Json.MAPPER.readTree("true"), "–"), "booleans stringify");
        assertEquals("{\"a\":1}", Fmt.textOr(Json.MAPPER.readTree("{\"a\":1}"), "–"),
                "objects fall back to their JSON form instead of throwing");
        assertEquals("[1,2]", Fmt.textOr(Json.MAPPER.readTree("[1,2]"), "–"));
        assertEquals("–", Fmt.textOr(Json.MAPPER.readTree("null"), "–"));
        assertEquals("–", Fmt.textOr(tools.jackson.databind.node.JsonNodeFactory.instance.missingNode(), "–"));
        assertEquals("–", Fmt.textOr(null, "–"));
    }

    /** The service emits three timestamp shapes — all must sort chronologically. */
    @Test
    void parseTime_handlesTheServiceFormats() {
        assertEquals(java.time.OffsetDateTime.parse("2026-09-16T00:09:04.439132+01:00"),
                Fmt.parseTime("2026-09-16T00:09:04.439132+01:00"), "jobs/experiments: ISO with offset");
        assertEquals(java.time.OffsetDateTime.parse("2026-09-15T23:57:00Z"),
                Fmt.parseTime("2026-09-15T23:57:00Z"), "ISO Z");
        assertEquals(java.time.OffsetDateTime.parse("2026-09-15T03:02:30Z"),
                Fmt.parseTime("2026-09-15 03:02:30"), "runs: SQL-style naive, treated as UTC");
        assertNull(Fmt.parseTime(null));
        assertNull(Fmt.parseTime("  "));
        assertNull(Fmt.parseTime("not a time"));
    }

    /** Near-miss shapes a future regex edit could break. */
    @Test
    void parseTime_dateOnlyYieldsNull() {
        assertNull(Fmt.parseTime("2026-09-15"), "no time component is not a sortable instant");
    }

    @Test
    void parseTime_sqlStyleOffsetWithoutColonNormalizes() {
        assertEquals(java.time.OffsetDateTime.parse("2026-09-15T03:02:30+01:00"),
                Fmt.parseTime("2026-09-15 03:02:30+01"), "two-digit offsets gain the :00");
    }

    @Test
    void parseTime_secondAndMinutePrecisionParse() {
        assertEquals(java.time.OffsetDateTime.parse("2026-09-15T03:02:00Z"),
                Fmt.parseTime("2026-09-15 03:02"), "minute precision still sorts");
    }

    @Test
    void comparingTime_sortsChronologicallyAcrossFormatsWithNullsLast() {
        record Row(String t) {
        }
        final var rows = java.util.List.of(
                new Row(null),
                new Row("2026-09-15 03:02:30"),       // 03:02Z — naive SQL style
                new Row("2026-09-14T23:00:00+01:00"),   // 22:00Z — the earliest despite the later local hour
                new Row("2026-09-15T02:00:00Z"));      // 02:00Z
        final var sorted = rows.stream().sorted(Fmt.comparingTime(Row::t)).map(Row::t).toList();
        assertEquals(java.util.Arrays.asList(
                "2026-09-14T23:00:00+01:00",
                "2026-09-15T02:00:00Z",
                "2026-09-15 03:02:30",
                null), sorted);
    }
}
