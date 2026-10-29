package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** #551: the Params pill row should call out every model-identifying flag in green, ahead of the
 *  existing numeric-vs-text default coloring. */
class JobDetailPrototypeViewTest {

    @Test
    void modelFlagsRenderGreenAheadOfTheNumericDefault() {
        assertTrue(theme("--model=nebius/nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B").contains(Badges.SUCCESS));
        assertTrue(theme("--judge-model=gpt-5").contains(Badges.SUCCESS));
        assertTrue(theme("--reviewer-model=gpt-5").contains(Badges.SUCCESS));
        assertTrue(theme("--trajectory-reviewer-model=gpt-5").contains(Badges.SUCCESS));
    }

    @Test
    void nonModelFlagsKeepTheExistingColoring() {
        assertTrue(theme("--compaction-trigger=40000").contains(Badges.WARNING));
        assertTrue(theme("--harness=ref").contains(Badges.CONTRAST));
        assertTrue(theme("--self-review").contains(Badges.CONTRAST));
    }

    private static String theme(final String arg) {
        return JobDetailPrototypeView.paramBadge(arg).getElement().getAttribute("theme");
    }
}
