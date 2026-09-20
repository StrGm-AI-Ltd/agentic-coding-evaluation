package com.strgmai.ace.service.oracle;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Ports of the Scorer invariants from run_oracle.py's docstring: each one closes a scoring exploit. */
class ScorerTest {

    @Test
    void everyExpectedIdGetsExactlyOneRecordMissingIsFailNeverAnAbsence() {
        // a crashed checker emitted nothing for M3: the record is FAIL, the denominator keeps its weight
        Map<String, Object> rep = Scorer.score(List.of(
                CheckResult.pass(CheckId.M1, "ok"), CheckResult.pass(CheckId.M2, "ok"),
                CheckResult.fail(CheckId.M3, "checker missing/crashed: no record emitted"), CheckResult.pass(CheckId.M4, "ok")));
        assertEquals(4, rep.get("checks_reported"));
        assertEquals(3, rep.get("passed"));
        assertEquals(3, rep.get("points_got"));
        assertEquals(4, rep.get("denominator"));
        assertEquals(75.0, ((Number) rep.get("weighted_score_pct")).doubleValue());   // 3 of 4 weight-1 points
    }

    @Test
    void notAttemptedCountsAsFail() {
        Map<String, Object> rep = Scorer.score(List.of(CheckResult.notAttempted(CheckId.M1, "no sources")));
        assertEquals(1, rep.get("not_attempted"));
        assertEquals(0, rep.get("points_got"));
        assertEquals(1, rep.get("denominator"));
    }

    @Test
    void skippedAndInfraAreExcludedFromTheDenominatorButBlockTheHeadline() {
        Map<String, Object> rep = Scorer.score(List.of(
                CheckResult.pass(CheckId.M1, "ok"),
                CheckResult.skipped(CheckId.B1, "docker unavailable"),
                CheckResult.pass(CheckId.B2, "ok")));
        assertNull(rep.get("weighted_score_pct"));                 // the headline is BLOCKED
        assertNull(rep.get("functional_score_pct"));
        assertNotNull(rep.get("partial_score_pct"));
        assertTrue(((String) rep.get("note")).startsWith("PARTIAL: 1 check(s) unscored (B1)"));
        assertEquals(1 + 3 + 3, rep.get("full_denominator"));          // B1's weight only leaves the counted denominator
        assertEquals(1 + 3, rep.get("denominator"));                   // counted: M1 + B2
    }

    @Test
    void theFunctionalScoreIsOverTheFChecksOnly() {
        Map<String, Object> rep = Scorer.score(List.of(
                CheckResult.pass(CheckId.F1, "ok"), CheckResult.fail(CheckId.F2, "no"),
                CheckResult.pass(CheckId.S1, "ok"), CheckResult.pass(CheckId.M1, "ok")));
        assertEquals(List.of("F1", "F2"), rep.get("functional_ids"));
        assertEquals(5, rep.get("functional_points_got"));
        assertEquals(10, rep.get("functional_denominator"));
        assertEquals(50.0, ((Number) ((Map<?, ?>) rep).get("functional_score_pct")).doubleValue());
    }

    @Test
    void aRungWithoutFChecksHasNoFunctionalScore() {
        Map<String, Object> rep = Scorer.score(List.of(CheckResult.pass(CheckId.S1, "ok")));
        assertNull(rep.get("functional_score_pct"));
        assertNotNull(rep.get("weighted_score_pct"));
    }

    @Test
    void weightsComeFromTheRegistry() {
        assertEquals(5, CheckId.weightOf(CheckId.F7));
        assertEquals(1, CheckId.weightOf(CheckId.M1));
        assertEquals(2, CheckId.weightOf(CheckId.P2));
        assertEquals("bespoke", CheckId.categoryOf(CheckId.F7));
        assertEquals(9, CheckId.functional().size());
    }

    @Test
    void byCategoryAggregatesPassFailUnscored() {
        Map<String, Object> rep = Scorer.score(List.of(
                CheckResult.pass(CheckId.S1, "ok"), CheckResult.fail(CheckId.S2, "no"), CheckResult.skipped(CheckId.M1, "x")));
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> byCat = (Map<String, Map<String, Object>>) rep.get("by_category");
        assertEquals(1, byCat.get("structure").get("pass"));        // S1 passed, S2 failed
        assertEquals(1, byCat.get("lint").get("unscored"));
        assertEquals(1, byCat.get("structure").get("weight_got"));
    }
}
