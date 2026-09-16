package com.agentbench.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        String printed = Fmt.json(Json.MAPPER.readTree("{\"a\":1}"));
        assertEquals("""
                {
                  "a" : 1
                }""", printed);
    }
}
