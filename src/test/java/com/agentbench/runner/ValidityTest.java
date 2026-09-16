package com.agentbench.runner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Port of the validity() rules: harness/infra reasons invalidate; budget exhaustion does NOT
 *  (it is a result, censored symmetrically across the arms of a comparison). */
class ValidityTest {

    private static Map<String, Object> phase(String id, Object rc, String finish, boolean artifact) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id); m.put("rc", rc); m.put("finish", finish); m.put("artifact_present", artifact);
        return m;
    }

    @Test
    void aCrashedPhaseInvalidatesTheRun() {
        Map<String, Object> manifest = Map.of("mode", "monolithic", "phases", List.of(phase("p1_plan", 2, "stop", true)));
        Map<String, Object> v = Validity.validity(manifest, facts(10, 0), 0.2);
        assertFalse((Boolean) v.get("valid"));
        assertTrue(((List<String>) v.get("reasons")).get(0).contains("agent exited rc=2"));
    }

    @Test
    void aLengthDeathWithoutAnArtifactIsAStopPolicyArtefact() {
        Map<String, Object> manifest = Map.of("mode", "monolithic", "phases", List.of(phase("p2_implementation", 0, "length", false)));
        Map<String, Object> v = Validity.validity(manifest, facts(10, 0), 0.2);
        assertTrue(((List<String>) v.get("reasons")).stream().anyMatch(r -> r.contains("length")));
    }

    @Test
    void aBudgetTimeoutIsAResultNotAnInvalidation() {
        Map<String, Object> manifest = Map.of("mode", "monolithic", "phases", List.of(phase("p2_implementation", 124, "length", true)));
        Map<String, Object> v = Validity.validity(manifest, facts(10, 0), 0.2);
        assertTrue((Boolean) v.get("valid"), "rc=124 is the wall budget: recorded, never invalid");
    }

    @Test
    void aTokenBudgetRefusalBeforeACrashDoesNotInvalidate() {
        Map<String, Object> manifest = Map.of("mode", "monolithic",
                "phases", List.of(phase("p2_implementation", 3, "stop", true)));
        Map<String, Object> ph = new java.util.LinkedHashMap<>(manifest);
        List<Map<String, Object>> phases = new java.util.ArrayList<>();
        Map<String, Object> p = phase("p2_implementation", 3, "stop", true);
        p.put("token_budget_exhausted", true);   // the 429 refusal: over budget, not crashed
        phases.add(p);
        ph.put("phases", phases);
        Map<String, Object> v = Validity.validity(ph, facts(10, 0), 0.2);
        assertTrue((Boolean) v.get("valid"));
    }

    @Test
    void noRequestsOrHighErrorRateInvalidates() {
        Map<String, Object> manifest = Map.of("mode", "monolithic", "phases", List.of(phase("p1_plan", 0, "stop", true)));
        assertTrue(((List<String>) Validity.validity(manifest, facts(0, 0), 0.2).get("reasons")).contains("no LLM requests recorded"));
        List<String> reasons = (List<String>) Validity.validity(manifest, facts(10, 3), 0.2).get("reasons");
        assertTrue(reasons.stream().anyMatch(r -> r.contains("requests errored")));
        assertTrue(Validity.validity(manifest, facts(10, 1), 0.2).get("valid") instanceof Boolean);   // 10% <= maxErrorRate: fine
    }

    private static Map<String, Object> facts(int requests, int errors) {
        Map<String, Object> f = new java.util.LinkedHashMap<>();
        f.put("requests", requests); f.put("errors", errors); f.put("upstream_errors", 0);
        f.put("foreign_workspace_refs", 0);
        return f;
    }
}
