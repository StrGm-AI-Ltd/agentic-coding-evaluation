package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The sortable value behind the runs grid's composite % column. */
class RunsViewTest {

    @Test
    void effectiveScorePrefersWeightedOverPartial() {
        assertNull(RunsView.effectiveScore(ApiFixtures.withScores(ApiFixtures.run("r1"), null, null)));
        assertEquals(90.0, RunsView.effectiveScore(
                ApiFixtures.withScores(ApiFixtures.run("r1"), 90.0, 45.0)));
        assertEquals(45.0, RunsView.effectiveScore(
                ApiFixtures.withScores(ApiFixtures.run("r1"), null, 45.0)),
                "partial runs still sort by their partial score, not by null");
    }
}
