package com.agentbench.metrics;

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

    public static Map<String, Object> collect(Path runDir, Map<String, Object> manifest) throws Exception {
        Path journal = Path.of(String.valueOf(manifest.getOrDefault("interactions", runDir.resolve("interactions.jsonl").toString())));
        Map<String, Object> jf = (Map<String, Object>) manifest.getOrDefault("journal_facts", Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", com.agentbench.config.BenchProperties.RESULT_SCHEMA);
        Map<String, Object> leaderboard = new LinkedHashMap<>();
        out.put("leaderboard", leaderboard);

        // ---- per-phase attribution by timestamp window (the journal is the ground truth) ----
        Map<String, Object> phases = new LinkedHashMap<>();
        long totalSec = 0;
        for (Map<String, Object> ph : (List<Map<String, Object>>) manifest.getOrDefault("phases", List.of())) {
            String a = String.valueOf(ph.get("start_iso")), b = String.valueOf(ph.get("end_iso"));
            Map<String, Object> f = com.agentbench.runner.JournalFacts.facts(journal.toString(), a, b, null, null, null);
            long sec = ph.get("seconds") instanceof Number n ? Math.round(n.doubleValue()) : 0;
            totalSec += sec;
            phases.put(String.valueOf(ph.get("id")), Map.of("seconds", sec, "requests", f.get("requests"),
                    "completion_tokens", f.get("completion_tokens"), "over_budget", Boolean.TRUE.equals(ph.get("over_budget"))));
        }
        out.put("phases", phases);
        leaderboard.put("total_wall_sec", totalSec);
        leaderboard.put("completion_tokens", jf.get("completion_tokens"));

        // oracle.json powers the scores
        JsonNode o = JSON.readTree(runDir.resolve("oracle.json").toFile());
        Double functional = o.path("functional_score_pct").isNumber() ? o.path("functional_score_pct").asDouble() : null;
        Double composite = o.path("weighted_score_pct").isNumber() ? o.path("weighted_score_pct").asDouble() : null;
        Double partial = o.path("partial_score_pct").isNumber() ? o.path("partial_score_pct").asDouble() : null;
        leaderboard.put("functional_score_pct", functional);
        leaderboard.put("weighted_score_pct", composite);
        leaderboard.put("partial_score_pct", partial);
        int fGot = o.path("functional_points_got").asInt(0);
        if (fGot > 0 && totalSec > 0) {   // tokens per FUNCTIONAL point - only for full-denominator runs
            leaderboard.put("wall_sec_per_functional_point", round1(totalSec / (double) fGot));
            leaderboard.put("completion_tokens_per_functional_point", round1(((Number) jf.getOrDefault("completion_tokens", 0)).longValue() / (double) fGot));
        }

        // ---- agent_result_pct: the composite blended with the calibration terms ----
        Map<String, Object> review = (Map<String, Object>) manifest.getOrDefault("review_config", Map.of());
        Map<String, Object> traj = (Map<String, Object>) manifest.getOrDefault("trajectory_review_config", Map.of());
        Map<String, Object> par = (Map<String, Object>) manifest.getOrDefault("parallel_plan_config", Map.of());
        double wc = num(review.getOrDefault("weight", 0.1)), wt = num(traj.getOrDefault("weight", 0.1)), wp = num(par.getOrDefault("weight", 0.1));
        Map<String, Object> sr = (Map<String, Object>) manifest.getOrDefault("self_review", Map.of());
        Map<String, Object> tr = (Map<String, Object>) manifest.getOrDefault("trajectory_review", Map.of());
        Map<String, Object> pp = (Map<String, Object>) manifest.getOrDefault("parallel_plan", Map.of());
        if (sr.get("score") instanceof Number s) leaderboard.put("self_review", Map.of("score", s.doubleValue(),
                "calibration_gap", functional == null ? null : round1(Math.abs(s.doubleValue() - functional))));
        if (tr.get("score") instanceof Number s) leaderboard.put("trajectory_review", Map.of("score", s.doubleValue(),
                "calibration_gap", manifest.get("trajectory_review") instanceof Map<?, ?> tm && tm.get("objective_index_pct") instanceof Number oi
                        ? round1(Math.abs(s.doubleValue() - oi.doubleValue())) : null));
        if (pp.get("evaluation") instanceof Map<?, ?> ev) leaderboard.put("parallel_plan", Map.of("term", parallelTerm(manifest)));
        if (composite != null) {
            double base = composite * (1 - wc - wt - wp);
            double codeTerm = sr.get("score") instanceof Number s && functional != null
                    ? wc * (100 - Math.abs(s.doubleValue() - functional)) : 0;   // a missing/unparseable review scores 0 at full weight
            double trajTerm = tr.get("score") instanceof Number s && ((Map<?, ?>) manifest.get("trajectory_review")).get("objective_index_pct") instanceof Number oi
                    ? wt * (100 - Math.abs(s.doubleValue() - oi.doubleValue())) : 0;
            double parTerm = wp * parallelTerm(manifest);
            leaderboard.put("agent_result_pct", round1(base + codeTerm + trajTerm + parTerm));
            leaderboard.put("agent_result_terms", Map.of("base", round1(base), "code", round1(codeTerm), "trajectory", round1(trajTerm), "parallel", round1(parTerm)));
        }
        out.put("steps", steps(manifest, o));
        return out;
    }

    /** the parallelisation term: 0.3 x validity + 0.3 x parallelism captured + 0.4 x (1 - friction) */
    static double parallelTerm(Map<String, Object> manifest) {
        Map<String, Object> pp = (Map<String, Object>) manifest.get("parallel_plan");
        if (pp == null) return 0;
        Map<String, Object> ev = (Map<String, Object>) pp.get("evaluation");
        if (ev == null || !Boolean.TRUE.equals(ev.get("valid"))) return 0;
        double parallelism = num(ev.get("parallelism_pct"));
        List<Map<String, Object>> waves = (List<Map<String, Object>>) manifest.getOrDefault("waves", List.of());
        double fixTokens = waves.stream().mapToDouble(w -> num(w.get("fix_tokens"))).sum();
        double allTokens = ((List<?>) manifest.getOrDefault("tasks", List.of())).stream().mapToDouble(t -> num(((Map<String, Object>) t).get("token_budget"))).sum();
        double friction = 0.4 * (allTokens > 0 ? fixTokens / allTokens : 0);
        return 0.3 * 100 + 0.3 * parallelism + 0.4 * (100 * (1 - friction)) - 0.3 * 100 + 0.3 * 100 - 0.3 * 100 + 30;   // = 30 + 0.3*par + 0.4*(100-friction*100)
    }

    /** per-step scores: definition/plan from their oracle checks, each task from its attributed
     *  `checks` at the final state, INTEGRATION = the unclaimed implementation checks */
    static Map<String, Object> steps(Map<String, Object> manifest, JsonNode oracle) {
        Map<String, Object> steps = new LinkedHashMap<>();
        Map<String, Double> byCheck = new LinkedHashMap<>();
        for (JsonNode r : oracle.path("results"))
            if (r.path("status").asText("").equals("PASS")) byCheck.put(r.path("id").asText(), 1.0);
            else byCheck.put(r.path("id").asText(), 0.0);
        for (Map<String, Object> ph : (List<Map<String, Object>>) manifest.getOrDefault("phases", List.of())) {
            String id = String.valueOf(ph.get("id"));
            if (id.startsWith("p0")) steps.put("definition", scoreOf(byCheck, List.of("P1")) ? 100.0 : 0.0);
            if (id.startsWith("p1")) steps.put("plan", scoreOf(byCheck, List.of("P2")) ? 100.0 : 0.0);
        }
        Set<String> claimed = new LinkedHashSet<>();
        List<Map<String, Object>> planTasks = manifest.get("plan") instanceof Map<?, ?> pl && pl.get("tasks") instanceof List<?> tl
                ? (List<Map<String, Object>>) tl : List.of();
        for (Map<String, Object> t : planTasks) {
            List<String> checks = (List<String>) t.get("checks");
            if (checks == null || checks.isEmpty()) continue;
            claimed.addAll(checks);
            steps.put(String.valueOf(t.get("id")), Map.of("score_pct", round1(100.0 * checks.stream().filter(c -> byCheck.getOrDefault(c, 0.0) > 0).count() / checks.size()),
                    "points", List.of(checks.stream().filter(c -> byCheck.getOrDefault(c, 0.0) > 0).count(), checks.size())));
        }
        List<String> rest = byCheck.keySet().stream().filter(c -> c.matches("[SBM].*") && !claimed.contains(c)).toList();
        if (!rest.isEmpty()) steps.put("INTEGRATION", Map.of("score_pct", round1(100.0 * rest.stream().filter(c -> byCheck.get(c) > 0).count() / rest.size()),
                "points", List.of(rest.stream().filter(c -> byCheck.get(c) > 0).count(), rest.size())));
        return steps;
    }

    static boolean scoreOf(Map<String, Double> byCheck, List<String> ids) {
        return ids.stream().allMatch(c -> byCheck.getOrDefault(c, 0.0) > 0);
    }

    static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    static double round1(double x) { return java.math.BigDecimal.valueOf(x).setScale(1, java.math.RoundingMode.HALF_EVEN).doubleValue(); }
}
