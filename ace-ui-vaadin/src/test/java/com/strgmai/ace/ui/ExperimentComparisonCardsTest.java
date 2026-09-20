package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The experiment's verdict cards (M3) — the twin of experiment_detail.html's Comparison. */
class ExperimentComparisonCardsTest {

    @Test
    void rendersDiffCiPAndSupportVerdict() {
        JsonNode comparison = Json.MAPPER.readTree("""
                {"orch_vs_mono": {
                   "result": {"compare": {"metric": "functional", "diff": 12.5,
                                          "ci90": [3.2, 21.4], "p": 0.041, "one_sided": true}},
                   "printed": "A - B = 12.5 ..."}}
                """);
        List<ExperimentDetailView.ComparisonCard> cards = ExperimentDetailView.comparisonCards(comparison);
        assertEquals(1, cards.size());
        assertEquals("orch vs mono", cards.get(0).label());
        assertEquals("good", cards.get(0).calloutKind(), "p < 0.10 is supported");
        assertEquals("A − B = 12.5 functional points · 90% CI [3.2, 21.4] · p = 0.041 · supported (p < 0.10)",
                cards.get(0).calloutText());
        assertEquals("A - B = 12.5 ...", cards.get(0).printed());
    }

    @Test
    void notSupportedIsMutedNotGood() {
        JsonNode comparison = Json.MAPPER.readTree("""
                {"a_vs_b": {"result": {"compare": {"metric": "score", "diff": 1.0,
                                                    "ci90": [-4.0, 6.0], "p": 0.6}}}}
                """);
        List<ExperimentDetailView.ComparisonCard> cards = ExperimentDetailView.comparisonCards(comparison);
        assertEquals("muted", cards.get(0).calloutKind());
        assertEquals("A − B = 1 score points · 90% CI [-4, 6] · p = 0.600 · not supported at α = 0.10",
                cards.get(0).calloutText());
    }

    @Test
    void refusedAndErrorCasesMatchTheJinjaPage() {
        JsonNode comparison = Json.MAPPER.readTree("""
                {"a_vs_b": {"refused": "k < 5", "printed": "refuse"},
                 "b_vs_c": {"error": "no succeeded, imported runs for arm 'b'"},
                 "c_vs_d": {"result": {"compare": null}}}
                """);
        List<ExperimentDetailView.ComparisonCard> cards = ExperimentDetailView.comparisonCards(comparison);
        assertEquals(3, cards.size());
        assertEquals("error", cards.get(0).calloutKind());
        assertEquals("stats.py refused: k < 5", cards.get(0).calloutText());
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
