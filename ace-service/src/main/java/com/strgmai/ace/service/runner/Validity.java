package com.strgmai.ace.service.runner;

import java.util.*;

/** Port of run_bench.py's validity(): INVALID = recorded, never ranked. A phase or task that
 *  reaches its wall or token budget is a RESULT (over_budget) in BOTH modes — the two arms of a
 *  harness comparison must be censored the same way; the RUN is invalid only for harness/infra
 *  reasons: crashes, a dead proxy, upstream errors, a `length` death with no artifact. */
public final class Validity {
    private Validity() {}

    public static Map<String, Object> validity(final Map<String, Object> manifest, final Map<String, Object> facts, final double maxErrorRate) {
        final List<String> reasons = new ArrayList<>();
        final boolean orch = "orchestrated".equals(manifest.get("mode"));
        for (Map<String, Object> ph : phases(manifest, "phases")) {
            Object rc = ph.get("rc");
            final boolean overTok = Boolean.TRUE.equals(ph.get("token_budget_exhausted"));
            if (rc != null && !rc.equals(0) && !rc.equals(124) && !overTok)
                reasons.add(ph.get("id") + ": agent exited rc=" + rc);
            if ("length".equals(ph.get("finish")) && !Boolean.TRUE.equals(ph.get("artifact_present")))
                reasons.add(ph.get("id") + ": session ended on a `length` finish with no artifact (stop-policy artefact)");
        }
        for (Map<String, Object> t : phases(manifest, "tasks"))
            if (t.get("rc") != null && !t.get("rc").equals(0) && !t.get("rc").equals(124) && !Boolean.TRUE.equals(t.get("token_budget_exhausted")))
                reasons.add(t.get("id") + ": agent exited rc=" + t.get("rc"));
        // #294: a "proxy_rcs" dead-proxy check used to sit here - removed. Nothing ever wrote that
        // manifest key (RecordingProxy is an in-JVM thread-based relay, not a forked subprocess
        // with its own exit code - there was never a real "rc" for it to report), so the loop only
        // ever iterated an empty list and could never fire. "A dead proxy" per this class's own
        // javadoc is already covered in spirit by the error-rate check just below: a proxy that
        // genuinely died mid-run shows up as upstream_errors/502s in facts, not as a process code.
        int requests = ((Number) facts.getOrDefault("requests", 0)).intValue();   // (int) would CCE on a Long from JSON deserialization
        final long errors = ((Number) facts.getOrDefault("errors", 0)).longValue() + ((Number) facts.getOrDefault("upstream_errors", 0)).longValue();
        if (requests > 0 && (double) errors / requests > maxErrorRate)
            reasons.add(errors + "/" + requests + " requests errored");
        if (requests == 0 && !phases(manifest, "phases").isEmpty()) reasons.add("no LLM requests recorded");
        if (((Number) facts.getOrDefault("foreign_workspace_refs", 0)).intValue() > 0)
            reasons.add(facts.get("foreign_workspace_refs") + " request(s) referenced another run's workspace");
        if (orch) {
            final Map<String, Object> plan = manifest.get("plan") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            if (plan.get("error") != null) reasons.add("plan unparseable: " + String.valueOf(plan.get("error")).substring(0, Math.min(80, String.valueOf(plan.get("error")).length())));
            else if (manifest.get("step") == null && phases(manifest, "tasks").stream().noneMatch(t -> "INTEGRATION".equals(t.get("id"))))
                reasons.add("integration task never ran");
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", reasons.isEmpty());
        out.put("reasons", reasons);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> phases(final Map<String, Object> manifest, final String key) {
        return manifest.get(key) instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
