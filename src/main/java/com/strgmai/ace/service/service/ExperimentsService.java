package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** Port of service/experiments.py: arm -> argv construction for the batch experiment templates.
 *  The model_ab run ids carry the arm suffix — the fix from the Python review: two models whose
 *  names share their first 10 alphanumerics must not mint colliding run ids. */
@Service
public class ExperimentsService {
    public static final String RUNG = "L3p_point_in_time";

    private final JdbcTemplate jdbc;
    private final JobQueue queue;
    private final BenchProperties props;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final com.strgmai.ace.service.metrics.StatsService stats = new com.strgmai.ace.service.metrics.StatsService();

    public ExperimentsService(JdbcTemplate jdbc, JobQueue queue, BenchProperties props,
                              org.springframework.transaction.support.TransactionTemplate tx) {
        this.jdbc = jdbc; this.queue = queue; this.props = props; this.tx = tx;
    }

    public static String shortName(String model) {
        StringBuilder b = new StringBuilder();
        for (char c : model.toCharArray()) if (Character.isLetterOrDigit(c)) b.append(c);
        return b.length() > 10 ? b.substring(0, 10).toString() : b.toString();
    }

    public static String defaultTag() {   // SECOND resolution (the Python review fix): two experiments in one minute must not collide
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
    }

    public record ArmSpec(String arm, int repeat, RunSpec spec) {}

    public List<ArmSpec> plan(String template, Map<String, Object> params, int k) {
        String tag = defaultTag();
        List<ArmSpec> specs = new ArrayList<>();
        switch (template) {
            case "harness_effect" -> {
                String model = str(params.get("model"));
                int wall = num(params.getOrDefault("task_wall", 3600)), tokens = params.get("task_tokens") == null || "auto".equals(str(params.get("task_tokens"))) ? 60000 : num(params.get("task_tokens"));
                List<String> arms = params.get("arms") instanceof List<?> l ? (List<String>) l : List.of("orch", "mono");
                int n = taskCount();   // the monolithic impl budget = N x task budget from the reference plan (matched, P-1)
                String parallel = params.get("parallel") == null ? "3" : str(params.get("parallel"));
                Integer window = contextWindow(params, model);
                for (int i = 1; i <= k; i++)
                    for (String arm : arms)
                        specs.add(new ArmSpec(arm, i, new RunSpec(RUNG, model, null, "orchestrated".equals(armMode(arm)) ? "orchestrated" : "monolithic",
                                "reference", wall, tokens, arm.contains("mono") ? wall * n : null, arm.contains("mono") ? tokens * n : null,
                                "par".equals(arm) ? parallel : null, "mono+rules".equals(arm), false, false, null, false, true, window,
                                "he-" + tag + "-" + shortName(model) + "-" + arm.replace("+", "") + "-r" + i)));
            }
            case "model_ab" -> {
                String a = str(params.get("model_a")), b = str(params.get("model_b"));
                int wall = num(params.getOrDefault("task_wall", 3600));
                // per arm: A and B can be different-sized models, so the window fallback must resolve per model
                Integer windowA = contextWindow(params, a), windowB = contextWindow(params, b);
                for (int i = 1; i <= k; i++) {
                    // the arm suffix keeps A and B distinct; model-ab.sh always reviews both sides (self + trajectory)
                    specs.add(new ArmSpec("A", i, new RunSpec(RUNG, a, null, "orchestrated", "reference", wall, null, null, null, null, false,
                            true, true, str(params.get("reviewer_model")), false, true, windowA,
                            "ab-" + tag + "-" + shortName(a) + "-a-r" + i)));
                    specs.add(new ArmSpec("B", i, new RunSpec(RUNG, b, null, "orchestrated", "reference", wall, null, null, null, null, false,
                            true, true, str(params.get("reviewer_model")), false, true, windowB,
                            "ab-" + tag + "-" + shortName(b) + "-b-r" + i)));
                }
            }
            case "agent_ab" -> {
                String model = str(params.get("model"));
                int wall = num(params.getOrDefault("task_wall", 3600));
                String mode = params.get("mode") == null ? "orchestrated" : str(params.get("mode"));
                Integer window = contextWindow(params, model);
                for (int i = 1; i <= k; i++)
                    for (String agent : List.of("ref", "pi"))   // --harness=ref|pi: the flag the comparison is ABOUT
                        specs.add(new ArmSpec(agent, i, new RunSpec(RUNG, model, agent, mode, "reference",
                                "orchestrated".equals(mode) ? wall : null, null, "monolithic".equals(mode) ? wall * taskCount() : null,
                                "monolithic".equals(mode) ? 60000 * taskCount() : null, null, false, false, false, null, false, true, window,
                                "aa-" + tag + "-" + shortName(model) + "-" + agent + "-r" + i)));
            }
            default -> throw new IllegalArgumentException("unknown template " + template + "; known: harness_effect, model_ab, agent_ab");
        }
        Set<String> ids = new HashSet<>();
        specs.forEach(s -> { if (!ids.add(s.spec().runId())) throw new IllegalArgumentException("duplicate run id " + s.spec().runId()); });
        return specs;
    }

    /** the window THIS arm's model runs with. An explicit params.context_window always wins; otherwise,
     *  if the model server currently serves `model`, its max_model_len (the model's spec ceiling, not a
     *  promise this machine's memory sustains it) is pinned so the run skips the step-0 probe. Unset when
     *  neither resolves, leaving the probe to measure the real, memory-safe window itself. Resolved per
     *  model, never once per experiment: model_ab's two arms can be different-sized models. */
    Integer contextWindow(Map<String, Object> params, String model) {
        if (params.get("context_window") != null) return num(params.get("context_window"));
        return localModelSpecs().get(model);
    }

    /** id -> max_model_len for whatever the model server currently serves — the same /v1/models query
     *  Preflight's "target model served" check makes. Best-effort: an unreachable server means an empty
     *  map (the probe owns the window), never a crash. */
    Map<String, Integer> localModelSpecs() {
        Map<String, Integer> out = new LinkedHashMap<>();
        new com.strgmai.ace.service.runner.ContextProbe().models(props.endpoint(), props.apiKey() == null ? "" : props.apiKey())
                .forEach((id, m) -> { if (m.hasNonNull("max_model_len")) out.put(id, m.get("max_model_len").asInt()); });
        return out;
    }

    static String armMode(String arm) { return arm.startsWith("mono") ? "monolithic" : "orchestrated"; }

    /** tasks in the rung's reference plan + the fixed integration task (experiments.py task_count) */
    static int taskCount() {
        try {
            var plan = com.strgmai.ace.service.plan.PlanParser.parseFile(java.nio.file.Path.of(
                    str(System.getProperty("ace.repo_root", ".")), "task/REFERENCE_PLAN.md"));
            return plan.size() + 1;
        } catch (Exception e) { return 8; }
    }
    static String str(Object o) { return o == null ? null : String.valueOf(o); }
    static int num(Object o) { return o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o)); }

    /** the experiment row and its arms are one unit: an arm that fails to enqueue (a colliding run id,
     *  a results dir already on disk) must not leave an experiment behind that can never finish, so the
     *  whole sequence runs in one transaction and rolls back together. */
    public Map<String, Object> enqueue(String name, String template, Map<String, Object> params, int k, String resultsDir, String runnerSha, String oracleSha) {
        List<ArmSpec> specs = plan(template, params, k);
        return tx.execute(status -> {
            Map<String, Object> experiment = jdbc.queryForMap(
                    "INSERT INTO experiments (name, tag, template, params, k, pinned_runner_sha, pinned_oracle_sha) VALUES (?,?,?,?::jsonb,?,?,?) RETURNING *",
                    name, defaultTag(), template, toJson(params), k, runnerSha, oracleSha);
            long id = ((Number) experiment.get("id")).longValue();
            List<Object> jobs = new ArrayList<>();
            for (ArmSpec s : specs)
                jobs.add(queue.enqueue(s.spec(), 0, resultsDir, runnerSha, oracleSha, id, s.arm(), s.repeat()));
            Map<String, Object> out = new LinkedHashMap<>(experiment);
            out.put("jobs", jobs);
            return out;
        });
    }

    /** port of finalize_if_done: when every job of the experiment is terminal, compute the
     *  template's comparisons from the imported runs (the runs table is the source of truth) */
    public void finalizeIfDone(long experimentId) {
        Map<String, Object> exp = jdbc.queryForMap("SELECT * FROM experiments WHERE id = ?", experimentId);
        if (!"queued".equals(exp.get("status"))) return;
        List<Map<String, Object>> jobs = jdbc.queryForList("SELECT arm, run_id, status FROM jobs WHERE experiment_id = ? ORDER BY repeat, arm", experimentId);
        if (jobs.stream().anyMatch(j -> !RunSpec.TERMINAL.contains(j.get("status")))) return;
        Map<String, List<Path>> byArm = new LinkedHashMap<>();
        for (Map<String, Object> j : jobs)
            if ("succeeded".equals(j.get("status")))
                jdbc.queryForList("SELECT results_dir FROM runs WHERE run_id = ?", j.get("run_id")).stream().findFirst()
                        .ifPresent(r -> byArm.computeIfAbsent((String) j.get("arm"), x -> new ArrayList<>()).add(Path.of((String) r.get("results_dir"))));
        Map<String, Object> params = exp.get("params") instanceof String ps ? fromJson(ps) : new LinkedHashMap<String, Object>();
        String template = (String) exp.get("template");
        Map<String, Object> comparisons = new LinkedHashMap<>();
        for (String[] pair : templatePairs(template, params)) {
            String label = pair[0] + "_vs_" + pair[1];
            List<Path> a = byArm.getOrDefault(pair[0], List.of()), b = byArm.getOrDefault(pair[1], List.of());
            if (a.isEmpty() || b.isEmpty()) {
                comparisons.put(label, Map.of("error", "no succeeded, imported runs for arm " + (a.isEmpty() ? pair[0] : pair[1])));
                continue;
            }
            try {
                List<Double> fa = functional(a), fb = functional(b);
                comparisons.put(label, Map.of("result", stats.compare(fa, fb, "functional"), "printed", ""));
            } catch (Exception e) {   // stats refused (not comparable / nothing to pool): a result, not a crash
                comparisons.put(label, Map.of("refused", String.valueOf(e)));
            }
        }
        comparisons.put("arms", jobs.stream().map(j -> Map.of("arm", j.get("arm"), "status", j.get("status"))).toList());
        jdbc.update("UPDATE experiments SET status = 'finished', comparison = ?::jsonb WHERE id = ?", toJson(comparisons), experimentId);
    }

    /** the template's arm pairs (experiments.py: TEMPLATE_COMPARISONS) */
    List<String[]> templatePairs(String template, Map<String, Object> params) {
        if ("model_ab".equals(template)) return List.<String[]>of(new String[]{"A", "B"});
        if ("agent_ab".equals(template))
            return List.<String[]>of(new String[]{String.valueOf(params.getOrDefault("agents_a", "ref")), String.valueOf(params.getOrDefault("agents_b", "pi"))});
        List<String[]> pairs = new ArrayList<>(List.<String[]>of(new String[]{"orch", "mono"}));
        List<String> arms = params.get("arms") instanceof List<?> l ? (List<String>) l : List.of("orch", "mono");
        if (arms.contains("par")) pairs.add(new String[]{"par", "orch"});
        if (arms.contains("mono+rules")) { pairs.add(new String[]{"mono+rules", "mono"}); pairs.add(new String[]{"orch", "mono+rules"}); }
        return pairs;
    }

    List<Double> functional(List<Path> dirs) {
        List<Double> out = new ArrayList<>();
        for (Path d : dirs) {
            try {
                com.fasterxml.jackson.databind.JsonNode v = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(d.resolve("oracle.json").toFile()).path("functional_score_pct");
                if (v.isNumber()) out.add(v.asDouble());
            } catch (Exception ignore) {}
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no functional scores on one side");
        return out;
    }

    Map<String, Object> fromJson(String s) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Map.class); } catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private String toJson(Object o) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o); }
        catch (Exception e) { return "{}"; }
    }
}
