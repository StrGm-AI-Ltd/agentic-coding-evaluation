package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** #192: ExperimentParams.build names the offending field as a "key: message" prefix for
     *  most of its checks (intOr/putIfPresent/taskTokens) - this is what routes that error back
     *  to the field it's about instead of only a generic panel. */
    @Test
    void splitFieldErrorExtractsAKnownFieldKeyAndItsMessage() {
        assertArrayEquals(new String[]{"task_wall", "enter a whole number"},
                ExperimentNewView.splitFieldError("task_wall: enter a whole number", Set.of("task_wall")));
        assertArrayEquals(new String[]{"task_tokens", "enter a number, or 'auto'"},
                ExperimentNewView.splitFieldError("task_tokens: enter a number, or 'auto'", Set.of("task_tokens")));
    }

    @Test
    void splitFieldErrorIsNullWhenTheKeyIsntAKnownField() {
        assertNull(ExperimentNewView.splitFieldError("a model is required for every template", Set.of("model")),
                "no colon-prefix at all");
        assertNull(ExperimentNewView.splitFieldError("unknown_field: enter a whole number", Set.of("task_wall")),
                "has a colon prefix, but it doesn't name a real field");
    }

    @Test
    void splitFieldErrorIsNullForCrossFieldMessagesWithNoSingleFieldToBlame() {
        assertNull(ExperimentNewView.splitFieldError("model_ab needs both model_a and model_b",
                Set.of("model_a", "model_b")));
    }
}
