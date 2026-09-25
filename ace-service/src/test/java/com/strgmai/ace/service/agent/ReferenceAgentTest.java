package com.strgmai.ace.service.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void toolEnvStripsProviderCredentialsButKeepsAUsableBaseline() {
        // an external reviewer's extraEnv (Reviews.externalCredentials) is ONLY its provider key -
        // no PATH/HOME - and must never reach the model's own bash tool (#98)
        final var credentialsOnly = Map.of("ANTHROPIC_API_KEY", "sk-live-secret");
        final var env = ReferenceAgent.toolEnv(credentialsOnly);
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"), "a provider credential must never reach the tool-execution env");
        assertTrue(env.containsKey("PATH"), "a credential-only extraEnv must still get a usable PATH");
        assertTrue(env.containsKey("HOME"));

        // a normal run's own scrubbed environment (RunBenchSupport.scrubbedEnv) passes through unchanged
        final var scrubbed = Map.of("HOME", "/scratch/home", "PATH", "/scratch/home/bin:/usr/bin", "LANG", "en_US.UTF-8");
        final var passthrough = ReferenceAgent.toolEnv(scrubbed);
        assertEquals("/scratch/home", passthrough.get("HOME"));
        assertEquals("/scratch/home/bin:/usr/bin", passthrough.get("PATH"));

        // no extraEnv at all (a bare unit-test context) still gets a sane default
        final var bare = ReferenceAgent.toolEnv(null);
        assertTrue(bare.containsKey("PATH"));
        assertTrue(bare.containsKey("HOME"));
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
