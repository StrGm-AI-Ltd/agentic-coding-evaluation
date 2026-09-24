package com.strgmai.ace.service.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.util.*;

/** Port of metrics/collect.py: cost/efficiency metrics for ONE run, from the run's OWN journal.
 *  Leaderboard block: functional (primary), composite, wall, completion tokens, tokens/wall per
 *  FUNCTIONAL point - the last two only for full-denominator runs. agent_result_pct = composite x
 *  (1-wc-wt-wp) + wc x (100 - |review score - functional|) + wt x trajectory term + wp x
 *  parallelisation term. Steps block: definition/plan/each task (its attributed oracle checks at
 *  the final state)/integration/review/trajectory terms. */
public final class Collect {
    private Collect() {}
    static final ObjectMapper JSON = new ObjectMapper();

    public static Map<String, Object> collect(final Path runDir, final Map<String, Object> manifest) throws Exception {
        final var journal = Path.of(String.valueOf(manifest.getOrDefault("interactions", runDir.resolve("interactions.jsonl").toString())));
        final Map<String, Object> jf = (Map<String, Object>) manifest.getOrDefault("journal_facts", Map.of());
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", com.strgmai.ace.service.config.BenchProperties.RESULT_SCHEMA);
        final Map<String, Object> leaderboard = new LinkedHashMap<>();
        out.put("leaderboard", leaderboard);

        // ---- per-phase attribution by timestamp window (the journal is the ground truth) ----
        final Map<String, Object> phases = new LinkedHashMap<>();
        long totalSec = 0;
        for (Map<String, Object> ph : (List<Map<String, Object>>) manifest.getOrDefault("phases", List.of())) {
            final String a = String.valueOf(ph.get("start_iso")), b = String.valueOf(ph.get("end_iso"));
            final Map<String, Object> f = com.strgmai.ace.service.runner.JournalFacts.facts(journal.toString(), a, b, null, null, null);
            final long sec = ph.get("seconds") instanceof Number n ? Math.round(n.doubleValue()) : 0;
            totalSec += sec;
            phases.put(String.valueOf(ph.get("id")), Map.of("seconds", sec, "requests", f.get("requests"),
                    "completion_tokens", f.get("completion_tokens"), "over_budget", Boolean.TRUE.equals(ph.get("over_budget"))));
        }
        out.put("phases", phases);
        // manifest.phases only ever holds p0_definition/p1_plan - EVERY task's actual execution
        // time (sequential tasks, INTEGRATION, parallel waves) lives in manifest.tasks/
        // manifest.waves instead, which this never read: every orchestrated run's reported wall
        // time was 0. A wave-task entry in manifest.tasks (tagged "parallel_wave") is skipped here
        // and counted via its wave's own wall-clock "seconds" below instead - its constituent
        // tasks run concurrently, so summing their individual durations would double-count real time.
        for (Map<String, Object> t : (List<Map<String, Object>>) manifest.getOrDefault("tasks", List.of()))
            if (!t.containsKey("parallel_wave"))
                totalSec += t.get("seconds") instanceof Number n ? Math.round(n.doubleValue()) : 0;
        for (Map<String, Object> w : (List<Map<String, Object>>) manifest.getOrDefault("waves", List.of()))
            totalSec += w.get("seconds") instanceof Number n ? Math.round(n.doubleValue()) : 0;
        leaderboard.put("total_wall_sec", totalSec);
        leaderboard.put("completion_tokens", jf.get("completion_tokens"));
        leaderboard.put("avg_latency_sec", jf.get("avg_latency_sec"));
        leaderboard.put("avg_first_byte_ms", jf.get("avg_first_byte_ms"));

        // oracle.json powers the scores
        final JsonNode o = JSON.readTree(runDir.resolve("oracle.json").toFile());
        final Double functional = o.path("functional_score_pct").isNumber() ? o.path("functional_score_pct").asDouble() : null;
        final Double composite = o.path("weighted_score_pct").isNumber() ? o.path("weighted_score_pct").asDouble() : null;
        final Double partial = o.path("partial_score_pct").isNumber() ? o.path("partial_score_pct").asDouble() : null;
        leaderboard.put("functional_score_pct", functional);
        leaderboard.put("weighted_score_pct", composite);
        leaderboard.put("partial_score_pct", partial);
        final int fGot = o.path("functional_points_got").asInt(0);
        if (fGot > 0 && totalSec > 0) {   // tokens per FUNCTIONAL point - only for full-denominator runs
            leaderboard.put("wall_sec_per_functional_point", round1(totalSec / (double) fGot));
            leaderboard.put("completion_tokens_per_functional_point", round1(((Number) jf.getOrDefault("completion_tokens", 0)).longValue() / (double) fGot));
        }

        // ---- agent_result_pct: the composite blended with the calibration terms ----
        final Map<String, Object> review = (Map<String, Object>) manifest.getOrDefault("review_config", Map.of());
        final Map<String, Object> traj = (Map<String, Object>) manifest.getOrDefault("trajectory_review_config", Map.of());
        final Map<String, Object> par = (Map<String, Object>) manifest.getOrDefault("parallel_plan_config", Map.of());
        final double wc = num(review.getOrDefault("weight", 0.1)), wt = num(traj.getOrDefault("weight", 0.1)), wp = num(par.getOrDefault("weight", 0.1));
        final Map<String, Object> sr = (Map<String, Object>) manifest.getOrDefault("self_review", Map.of());
        final Map<String, Object> tr = (Map<String, Object>) manifest.getOrDefault("trajectory_review", Map.of());
        final Map<String, Object> pp = (Map<String, Object>) manifest.getOrDefault("parallel_plan", Map.of());
        if (sr.get("score") instanceof Number s) {
            // LinkedHashMap: calibration_gap may legitimately be null (functional pct absent), and Map.of would NPE
            final Map<String, Object> e = new LinkedHashMap<>();
            e.put("score", s.doubleValue());
            e.put("calibration_gap", functional == null ? null : round1(Math.abs(s.doubleValue() - functional)));
            leaderboard.put("self_review", e);
        }
        if (tr.get("score") instanceof Number s) {
            // instanceof guard (not a bare cast): a non-Map trajectory_review degrades gracefully;
            // LinkedHashMap because the calibration_gap may be null, which Map.of would reject
            final boolean hasObj = manifest.get("trajectory_review") instanceof Map<?, ?> tm && tm.get("objective_index_pct") instanceof Number oi;
            final Map<String, Object> e = new LinkedHashMap<>();
            e.put("score", s.doubleValue());
            e.put("calibration_gap", hasObj ? round1(Math.abs(s.doubleValue() - ((Number) ((Map<?, ?>) manifest.get("trajectory_review")).get("objective_index_pct")).doubleValue())) : null);
            leaderboard.put("trajectory_review", e);
        }
        if (pp.get("evaluation") instanceof Map<?, ?> ev) leaderboard.put("parallel_plan", Map.of("term", parallelTerm(manifest)));
        if (composite != null) {
            final double base = composite * (1 - wc - wt - wp);
            double codeTerm = sr.get("score") instanceof Number s && functional != null
                    ? wc * (100 - Math.abs(s.doubleValue() - functional)) : 0;   // a missing/unparseable review scores 0 at full weight
            // "direct": the reviewer's own score IS the term (judged as a quality signal on its own
            // terms). "calibration" (default): rewarded for AGREEING with the harness's own objective
            // trajectory index, not for the raw score - guard with instanceof (a raw cast would CCE
            // on a non-Map, NPE on a missing key) before fetching
            final boolean trajDirect = "direct".equals(traj.get("use"));
            double trajTerm = 0;
            if (tr.get("score") instanceof Number s) {
                if (trajDirect) trajTerm = wt * s.doubleValue();
                else if (tr.get("objective_index_pct") instanceof Number oi) trajTerm = wt * (100 - Math.abs(s.doubleValue() - oi.doubleValue()));
            }
            final double parTerm = wp * parallelTerm(manifest);
            leaderboard.put("agent_result_pct", round1(base + codeTerm + trajTerm + parTerm));
            leaderboard.put("agent_result_terms", Map.of("base", round1(base), "code", round1(codeTerm), "trajectory", round1(trajTerm), "parallel", round1(parTerm)));
        }
        out.put("steps", steps(manifest, o));
        return out;
    }

    /** the parallelisation term: 0.3 x validity + 0.3 x parallelism captured + 0.4 x (1 - friction) */
    static double parallelTerm(final Map<String, Object> manifest) {
        final Map<String, Object> pp = (Map<String, Object>) manifest.get("parallel_plan");
        if (pp == null) return 0;
        final Map<String, Object> ev = (Map<String, Object>) pp.get("evaluation");
        if (ev == null || !Boolean.TRUE.equals(ev.get("valid"))) return 0;
        final double parallelism = num(ev.get("parallelism_pct"));
        final List<Map<String, Object>> waves = (List<Map<String, Object>>) manifest.getOrDefault("waves", List.of());
        final double fixTokens = waves.stream().mapToDouble(w -> num(w.get("fix_tokens"))).sum();
        final double allTokens = ((List<?>) manifest.getOrDefault("tasks", List.of())).stream().mapToDouble(t -> num(((Map<String, Object>) t).get("token_budget"))).sum();
        final double friction = 0.4 * (allTokens > 0 ? fixTokens / allTokens : 0);
        // closed form of 0.3 x validity(=100, guaranteed by the valid check above) + 0.3 x parallelism + 0.4 x (100 x (1-friction))
        return 30 + 0.3 * parallelism + 0.4 * (100 * (1 - friction));
    }

    /** per-step scores: definition/plan from their oracle checks, each task from its attributed
     *  `checks` at the final state, INTEGRATION = the unclaimed implementation checks */
    static Map<String, Object> steps(final Map<String, Object> manifest, final JsonNode oracle) {
        final Map<String, Object> steps = new LinkedHashMap<>();
        final Map<String, Double> byCheck = new LinkedHashMap<>();
        for (JsonNode r : oracle.path("results"))
            if (r.path("status").asText("").equals("PASS")) byCheck.put(r.path("id").asText(), 1.0);
            else byCheck.put(r.path("id").asText(), 0.0);
        for (Map<String, Object> ph : (List<Map<String, Object>>) manifest.getOrDefault("phases", List.of())) {
            final String id = String.valueOf(ph.get("id"));
            if (id.startsWith("p0")) steps.put("definition", scoreOf(byCheck, List.of("P1")) ? 100.0 : 0.0);
            if (id.startsWith("p1")) steps.put("plan", scoreOf(byCheck, List.of("P2")) ? 100.0 : 0.0);
        }
        final Set<String> claimed = new LinkedHashSet<>();
        List<Map<String, Object>> planTasks = manifest.get("plan") instanceof Map<?, ?> pl && pl.get("tasks") instanceof List<?> tl
                ? (List<Map<String, Object>>) tl : List.of();
        for (Map<String, Object> t : planTasks) {
            final List<String> checks = (List<String>) t.get("checks");
            if (checks == null || checks.isEmpty()) continue;
            claimed.addAll(checks);
            steps.put(String.valueOf(t.get("id")), Map.of("score_pct", round1(100.0 * checks.stream().filter(c -> byCheck.getOrDefault(c, 0.0) > 0).count() / checks.size()),
                    "points", List.of(checks.stream().filter(c -> byCheck.getOrDefault(c, 0.0) > 0).count(), checks.size())));
        }
        final List<String> rest = byCheck.keySet().stream().filter(c -> c.matches("[SBM].*") && !claimed.contains(c)).toList();
        if (!rest.isEmpty()) steps.put("INTEGRATION", Map.of("score_pct", round1(100.0 * rest.stream().filter(c -> byCheck.get(c) > 0).count() / rest.size()),
                "points", List.of(rest.stream().filter(c -> byCheck.get(c) > 0).count(), rest.size())));
        return steps;
    }

    static boolean scoreOf(final Map<String, Double> byCheck, final List<String> ids) {
        return ids.stream().allMatch(c -> byCheck.getOrDefault(c, 0.0) > 0);
    }

    static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    static double round1(double x) { return java.math.BigDecimal.valueOf(x).setScale(1, java.math.RoundingMode.HALF_EVEN).doubleValue(); }
}
