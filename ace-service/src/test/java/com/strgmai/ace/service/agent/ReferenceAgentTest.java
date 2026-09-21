package com.strgmai.ace.service.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** reasoningKind() maps a session name to a DEFAULT_REASONING key; this pins the mapping so a
 *  refactor can't silently drop a phase back to the "implement" fallback (which is exactly the bug
 *  this class was fixed for: reasoning effort was computed nowhere and never sent to the model). */
class ReferenceAgentTest {

    @Test
    void phaseIdsMapToTheirReasoningKind() {
        assertEquals("definition", ReferenceAgent.reasoningKind("p0_definition"));
        assertEquals("plan", ReferenceAgent.reasoningKind("p1_plan"));
        assertEquals("plan", ReferenceAgent.reasoningKind("PARALLEL_PLAN"));
        assertEquals("implement", ReferenceAgent.reasoningKind("p2_implementation"));
        assertEquals("implement", ReferenceAgent.reasoningKind("implement"));
        assertEquals("implement", ReferenceAgent.reasoningKind("T3"));   // plain task ids default to implement
        assertEquals("integrate", ReferenceAgent.reasoningKind("INTEGRATION"));
        assertEquals("review", ReferenceAgent.reasoningKind("REVIEW"));
        assertEquals("review", ReferenceAgent.reasoningKind("TRAJECTORY_REVIEW"));
    }

    @Test
    void suffixedContinuationsMapByTheirOwnSuffixOrTheBasePhase() {
        assertEquals("status", ReferenceAgent.reasoningKind("T3-wrapup"));
        assertEquals("handoff", ReferenceAgent.reasoningKind("T3-handoff"));
        assertEquals("fix", ReferenceAgent.reasoningKind("W1-fix"));
        assertEquals("implement", ReferenceAgent.reasoningKind("T3-continue"));
        assertEquals("definition", ReferenceAgent.reasoningKind("p0_definition-continue"));
    }

    @Test
    void everyKindResolvesToAConfiguredEffort() {
        for (String name : new String[] {"p0_definition", "p1_plan", "p2_implementation", "INTEGRATION",
                "REVIEW", "TRAJECTORY_REVIEW", "T3-wrapup", "T3-handoff", "W1-fix"}) {
            final String effort = ReferenceAgent.DEFAULT_REASONING.getOrDefault(ReferenceAgent.reasoningKind(name), "medium");
            assertEquals(true, effort.equals("high") || effort.equals("medium") || effort.equals("low"),
                    name + " resolved to an unexpected effort: " + effort);
        }
    }
}
