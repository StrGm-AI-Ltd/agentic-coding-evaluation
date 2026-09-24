package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The experiment's verdict cards (M3) — the twin of experiment_detail.html's Comparison. */
class ExperimentComparisonCardsTest {

    /** Fixture shape fixed 2026-09-24: "result" is what ExperimentsService.finalizeIfDone() and
     *  StatsService.compare() actually produce - the compare() output FLAT under "result", not
     *  nested under an extra "compare" key. The old fixtures here tested a shape the service never
     *  wrote; every real finished experiment's comparison card silently fell through to "No
     *  comparison" (verified live against a real experiment before this fix). */
    @Test
    void rendersDiffCiPAndSupportVerdict() {
        final var comparison = Json.MAPPER.readTree("""
                {"orch_vs_mono": {
                   "result": {"metric": "functional", "diff": 12.5,
                              "ci90": [3.2, 21.4], "p": 0.041, "one_sided": true},
                   "printed": "A - B = 12.5 ..."}}
                """);
        final var cards = ExperimentDetailView.comparisonCards(comparison);
        assertEquals(1, cards.size());
        assertEquals("orch vs mono", cards.get(0).label());
        assertEquals("good", cards.get(0).calloutKind(), "p < 0.10 is supported");
        assertEquals("A − B = 12.5 functional points · 90% CI [3.2, 21.4] · p = 0.041 · supported (p < 0.10)",
                cards.get(0).calloutText());
        assertEquals("A - B = 12.5 ...", cards.get(0).printed());
        assertEquals(List.of(), cards.get(0).speedLines(), "no speed block in the fixture - no speed lines");
    }

    @Test
    void notSupportedIsMutedNotGood() {
        final var comparison = Json.MAPPER.readTree("""
                {"a_vs_b": {"result": {"metric": "score", "diff": 1.0,
                                        "ci90": [-4.0, 6.0], "p": 0.6}}}
                """);
        final var cards = ExperimentDetailView.comparisonCards(comparison);
        assertEquals("muted", cards.get(0).calloutKind());
        assertEquals("A − B = 1 score points · 90% CI [-4, 6] · p = 0.600 · not supported at α = 0.10",
                cards.get(0).calloutText());
    }

    /** The point of this change: speed metrics ride alongside the functional verdict, one line
     *  each, sorted by metric name for a stable render order. */
    @Test
    void speedMetricsRenderAsLinesUnderTheMainVerdict() {
        final var comparison = Json.MAPPER.readTree("""
                {"A_vs_B": {
                   "result": {"metric": "functional", "diff": 0.0, "ci90": [-5.0, 5.0], "p": 0.9},
                   "speed": {
                     "avg_latency_sec": {"metric": "avg_latency_sec", "diff": -45.2,
                                         "ci90": [-52.1, -38.0], "p": 0.012},
                     "avg_first_byte_ms": {"metric": "avg_first_byte_ms", "diff": 3.0,
                                           "ci90": [-1.0, 7.0], "p": 0.4}
                   }}}
                """);
        final var cards = ExperimentDetailView.comparisonCards(comparison);
        assertEquals(1, cards.size());
        assertEquals(List.of(
                "avg_first_byte_ms: A − B = 3 · 90% CI [-1, 7] · p = 0.400 · not supported at α = 0.10",
                "avg_latency_sec: A − B = -45.2 · 90% CI [-52.1, -38] · p = 0.012 · supported (p < 0.10)"),
                cards.get(0).speedLines(), "sorted by metric name: avg_first_byte_ms before avg_latency_sec");
    }

    /** "arms" is job-status bookkeeping the service writes alongside the per-pair comparisons, not
     *  itself a comparison - it must never render as a fake "no comparison" card (the live bug this
     *  whole fixture-shape fix was found while chasing). */
    @Test
    void armsKeyIsNeverRenderedAsACard() {
        final var comparison = Json.MAPPER.readTree("""
                {"A_vs_B": {"result": {"metric": "functional", "diff": 0.0, "ci90": [0.0, 0.0], "p": 1.0}},
                 "arms": [{"arm": "A", "status": "succeeded"}, {"arm": "B", "status": "succeeded"}]}
                """);
        final var cards = ExperimentDetailView.comparisonCards(comparison);
        assertEquals(1, cards.size());
        assertEquals("A vs B", cards.get(0).label());
        assertTrue(cards.stream().noneMatch(c -> "arms".equals(c.label())));
    }

    @Test
    void refusedAndErrorCasesMatchTheJinjaPage() {
        final var comparison = Json.MAPPER.readTree("""
                {"a_vs_b": {"refused": "k < 5", "printed": "refuse"},
                 "b_vs_c": {"error": "no succeeded, imported runs for arm 'b'"},
                 "c_vs_d": {"result": null}}
                """);
        final var cards = ExperimentDetailView.comparisonCards(comparison);
        assertEquals(3, cards.size());
        assertEquals("error", cards.get(0).calloutKind());
        assertEquals("StatsService refused: k < 5", cards.get(0).calloutText());
        assertEquals("warn", cards.get(1).calloutKind());
        assertEquals("no succeeded, imported runs for arm 'b'", cards.get(1).calloutText());
        assertEquals("warn", cards.get(2).calloutKind());
        assertEquals("No comparison: one side had no comparable runs after exclusions.",
                cards.get(2).calloutText());
    }

    @Test
    void degenerateComparisonObjects() {
        assertEquals(List.of(), ExperimentDetailView.comparisonCards(null));
        assertEquals(List.of(), ExperimentDetailView.comparisonCards(
                Json.MAPPER.readTree("{}")));
        assertEquals(List.of(), ExperimentDetailView.comparisonCards(
                Json.MAPPER.readTree("[1, 2]")));
    }
}
