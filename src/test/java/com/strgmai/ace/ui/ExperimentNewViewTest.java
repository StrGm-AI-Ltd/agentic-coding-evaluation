package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 2026-09-16 harness_effect bug, pinned: the single model picker is a shared
 * field (one parent only) that must be visible for every template except model_ab.
 */
class ExperimentNewViewTest {

    @Test
    void modelPickerVisibleForEveryTemplateExceptTheABPair() {
        assertTrue(ExperimentNewView.modelPickerVisible("harness_effect"),
                "harness_effect needs the model under test — the reported bug");
        assertTrue(ExperimentNewView.modelPickerVisible("agent_ab"),
                "agent_ab also tests a single model (A/B is the harness)");
        assertFalse(ExperimentNewView.modelPickerVisible("model_ab"),
                "model_ab specifies model_a and model_b instead");
        assertTrue(ExperimentNewView.modelPickerVisible(null),
                "no template (unreachable in practice) defaults to visible");
    }
}
