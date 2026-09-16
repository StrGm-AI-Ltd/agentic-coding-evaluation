package com.agentbench.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.util.*;

/** Port of metrics/stats.py (the statistical core): a single run is one draw from a distribution
 *  of unknown variance. Bootstrap 90% CIs, an UNPAIRED one-sided permutation test for "A > B", and
 *  comparability ENFORCED: runs are pooled only if they share the task, the oracle, the contract,
 *  the schema version, mode and budgets; a model A/B compares SETUPS — window-derived knobs are
 *  properties of the setup, printed as differences, never a refusal. */
public final class StatsService {
    private final ObjectMapper json = new ObjectMapper();

    public StatsService() {}

    public record RunSummary(String dir, String task, String model, String mode, Double functional, Double score,
                             Double partial, Double agentResult, boolean valid, List<String> reasons, boolean contended, boolean poolable,
                             int implWall, int implTokens, Map<String, Object> key, List<String> statusOrder) {}

    public static final List<String> KEY_FIELDS = List.of("task", "step", "registry", "run_oracle", "contract", "task_prompt",
            "mode", "parallel", "plan_source", "handoff_notes", "system_rules", "length_policy", "budgets_wall", "budgets_tokens",
            "harness_sha", "harness_version", "tools_sha", "system_prompt_sha", "sampler",
            "usable_context", "derived", "harness", "reasoning_policy", "docker_window");
    public static final List<String> MODEL_AB_EXEMPT = List.of("usable_context", "derived", "budgets_tokens", "system_prompt_sha");
    public static final List<String> SHARED_WITH_MODEL_AB = List.of("task", "step", "registry", "run_oracle", "contract", "task_prompt");

    /** port of load(): oracle.json + manifest.json -> a comparable run summary */
    public RunSummary load(Path runDir) throws Exception {
        JsonNode o = json.readTree(runDir.resolve("oracle.json").toFile());
        JsonNode m = Files.exists(runDir.resolve("manifest.json")) ? json.readTree(runDir.resolve("manifest.json").toFile()) : json.createObjectNode();
        Map<String, Object> key = new LinkedHashMap<>();
        JsonNode prov = o.path("provenance");
        for (String k : KEY_FIELDS) {
            String v = switch (k) {
                case "task" -> o.path("task").asText(null);
                case "registry", "run_oracle", "contract", "task_prompt" -> prov.path(k).isMissingNode() ? null : prov.path(k).asText(null);
                case "mode" -> m.path("mode").asText("monolithic");
                case "plan_source" -> m.path("plan_source").asText("agent");
                case "harness_version" -> m.path("provenance").path("harness_version").asText(null);
                case "harness_sha" -> m.path("provenance").path("runner").asText(null);
                case "budgets_wall" -> m.path("budgets").path("wall_sec").isMissingNode() ? null : m.path("budgets").path("wall_sec").toString();
                case "budgets_tokens" -> m.path("budgets").path("completion_tokens").isMissingNode() ? null : m.path("budgets").path("completion_tokens").toString();
                case "sampler" -> m.path("journal_facts").path("sampler_effective").isMissingNode() ? null : m.path("journal_facts").path("sampler_effective").toString();
                case "system_prompt_sha" -> m.path("journal_facts").path("system_prompt_sha").asText(null);
                case "usable_context" -> m.path("usable_context").isMissingNode() ? null : m.path("usable_context").toString();
                case "derived" -> m.path("derived").isMissingNode() ? null : m.path("derived").toString();
                default -> m.has(k) ? m.get(k).toString() : null;
            };
            key.put(k, v);
        }
        boolean poolable = o.path("schema_version").asInt(-1) == com.agentbench.config.BenchProperties.RESULT_SCHEMA
                && !o.path("weighted_score_pct").isNull() && o.path("weighted_score_pct") != null && o.has("weighted_score_pct");
        return new RunSummary(runDir.toString(), o.path("task").asText(),
                m.path("provenance").path("model").asText(null), m.path("mode").asText("monolithic"),
                o.path("functional_score_pct").isMissingNode() || o.path("functional_score_pct").isNull() ? null : o.path("functional_score_pct").asDouble(),
                o.path("weighted_score_pct").isMissingNode() || o.path("weighted_score_pct").isNull() ? null : o.path("weighted_score_pct").asDouble(),
                o.path("partial_score_pct").isMissingNode() || o.path("partial_score_pct").isNull() ? null : o.path("partial_score_pct").asDouble(),
                m.path("leaderboard").path("agent_result_pct").isMissingNode() || m.path("leaderboard").path("agent_result_pct").isNull() ? null : m.path("leaderboard").path("agent_result_pct").asDouble(),
                m.path("validity").path("valid").asBoolean(true),
                new ArrayList<>(), m.path("contention").path("docker_up").asBoolean(false),
                poolable && m.path("validity").path("valid").asBoolean(true),
                m.path("budgets").path("implementation_wall_sec").asInt(0),
                m.path("budgets").path("implementation_tokens").asInt(0), key,
                o.path("results").findValuesAsText("id"));
    }

    /** port of filter_runs: refuse to pool non-comparable runs, name the culprit */
    public List<RunSummary> filterRuns(List<RunSummary> runs, boolean allowPartial, boolean includeInvalid, String label) {
        for (String k : KEY_FIELDS) {
            Set<String> vals = new HashSet<>();
            runs.forEach(r -> vals.add(String.valueOf(r.key().get(k))));
            if (vals.size() > 1)
                throw new IllegalArgumentException(label + ": runs are not comparable; differing component: " + k + " = " + vals);
        }
        Set<String> models = new HashSet<>();
        runs.forEach(r -> models.add(String.valueOf(r.model())));
        if (models.size() > 1) throw new IllegalArgumentException(label + ": runs mix models " + models + "; one model per side");
        List<RunSummary> kept = new ArrayList<>();
        for (RunSummary r : runs) {
            List<String> why = new ArrayList<>();
            if (r.score() == null && !allowPartial) why.add("partial (docker skipped/infra)");
            if (!r.valid() && !includeInvalid) why.add("invalid");
            if (!why.isEmpty()) System.out.println("  excluded " + Path.of(r.dir()).getFileName() + ": " + String.join("; ", why));
            else kept.add(r);
        }
        return kept;
    }

    /** port of boot_ci: bootstrap CI for the mean (seeded, reproducible) */
    public static double[] bootCi(List<Double> xs, int n, double alpha, long seed) {
        List<Double> clean = xs.stream().filter(Objects::nonNull).toList();
        if (clean.isEmpty()) return new double[]{Double.NaN, Double.NaN};
        if (clean.size() < 2) return new double[]{clean.get(0), clean.get(0)};
        Random rnd = new Random(seed);
        List<Double> means = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < clean.size(); j++) s += clean.get(rnd.nextInt(clean.size()));
            means.add(s / clean.size());
        }
        Collections.sort(means);
        return new double[]{round1(means.get((int) (n * alpha / 2))), round1(means.get((int) (n * (1 - alpha / 2))))};
    }

    /** port of permutation_test: UNPAIRED ONE-SIDED two-sample permutation test; p-value for mean(a) > mean(b) */
    public static double permutationTest(double[] a, double[] b, int n, long seed) {
        Random rnd = new Random(seed);
        double obs = mean(a) - mean(b);
        double[] pool = new double[a.length + b.length];
        System.arraycopy(a, 0, pool, 0, a.length);
        System.arraycopy(b, 0, pool, a.length, b.length);
        int ge = 0;
        for (int i = 0; i < n; i++) {
            for (int j = pool.length - 1; j > 0; j--) { int k = rnd.nextInt(j + 1); double t = pool[j]; pool[j] = pool[k]; pool[k] = t; }
            double da = 0; for (int j = 0; j < a.length; j++) da += pool[j];
            double db = 0; for (int j = a.length; j < pool.length; j++) db += pool[j];
            if (da / a.length - db / b.length >= obs - 1e-12) ge++;
        }
        return (double) ge / n;
    }

    /** port of the harness-effect budget matching: the total WORK budget must be matched (P-1) */
    public static void requireMatchedBudgets(RunSummary a, RunSummary b, boolean allowMismatch) {
        String msg = "implementation budgets A=" + a.implWall() + "s/" + a.implTokens() + " tok vs B=" + b.implWall() + "s/" + b.implTokens() + " tok";
        if (Math.abs(a.implWall() - b.implWall()) > 0.02 * Math.max(a.implWall(), b.implWall())
                || Math.abs(a.implTokens() - b.implTokens()) > 0.02 * Math.max(a.implTokens(), b.implTokens())) {
            if (!allowMismatch) throw new IllegalArgumentException("harness-effect comparison refused: " + msg + " are not matched (pass allow-budget-mismatch to compare anyway, confounded)");
            System.out.println("  WARNING: " + msg + " are NOT matched; the harness effect is confounded with budget");
        } else System.out.println("  matched budgets: " + msg);
    }

    /** the compare verdict: diff, CI, one-sided p, and the minimum-detectable-difference warning */
    public Map<String, Object> compare(List<Double> a, List<Double> b, String metric) {
        double obs = mean(a.stream().mapToDouble(Double::doubleValue).toArray()) - mean(b.stream().mapToDouble(Double::doubleValue).toArray());
        double p = permutationTest(a.stream().mapToDouble(Double::doubleValue).toArray(), b.stream().mapToDouble(Double::doubleValue).toArray(), 10000, 0);
        Random rnd = new Random(0);
        List<Double> ds = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            double sa = 0, sb = 0;
            for (int j = 0; j < a.size(); j++) sa += a.get(rnd.nextInt(a.size()));
            for (int j = 0; j < b.size(); j++) sb += b.get(rnd.nextInt(b.size()));
            ds.add(sa / a.size() - sb / b.size());
        }
        Collections.sort(ds);
        double lo = round1(ds.get((int) (4000 * 0.05))), hi = round1(ds.get((int) (4000 * 0.95)));
        int mdd = Math.min(a.size(), b.size()) < 6 ? 20 : 12;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", metric);
        out.put("diff", round1(obs));
        out.put("ci90", List.of(lo, hi));
        out.put("p", Math.round(p * 1000) / 1000.0);
        out.put("one_sided", true);
        out.put("verdict", p < 0.10 ? "A > B is supported (p<0.10)" : "NOT supported at alpha=0.10");
        out.put("note", "with k=" + Math.min(a.size(), b.size()) + " per side, differences under ~" + mdd + " points should not be claimed");
        return out;
    }

    static double mean(double[] xs) { double s = 0; for (double x : xs) s += x; return s / xs.length; }
    static double round1(double x) { return Math.round(x * 10) / 10.0; }
}
