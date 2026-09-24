package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.config.JsonColumns;
import com.strgmai.ace.service.jooq.tables.records.ExperimentsRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static com.strgmai.ace.service.jooq.Tables.EXPERIMENTS;
import static com.strgmai.ace.service.jooq.Tables.JOBS;
import static com.strgmai.ace.service.jooq.Tables.RUNS;

/** Port of service/experiments.py: arm -> argv construction for the batch experiment templates.
 *  The model_ab run ids carry the arm suffix — the fix from the Python review: two models whose
 *  names share their first 10 alphanumerics must not mint colliding run ids. */
@Service
public class ExperimentsService {
    private static final Logger log = LoggerFactory.getLogger(ExperimentsService.class);
    public static final String RUNG = "L3p_point_in_time";

    private final DSLContext dsl;
    private final JobQueue queue;
    private final BenchProperties props;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final com.strgmai.ace.service.metrics.StatsService stats = new com.strgmai.ace.service.metrics.StatsService();

    public ExperimentsService(DSLContext dsl, JobQueue queue, BenchProperties props,
                              org.springframework.transaction.support.TransactionTemplate tx) {
        this.dsl = dsl; this.queue = queue; this.props = props; this.tx = tx;
    }

    public static String shortName(String model) {
        final var b = new StringBuilder();
        for (char c : model.toCharArray()) if (Character.isLetterOrDigit(c)) b.append(c);
        return b.length() > 10 ? b.substring(0, 10).toString() : b.toString();
    }

    public static String defaultTag() {   // SECOND resolution (the Python review fix): two experiments in one minute must not collide
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
    }

    public record ArmSpec(String arm, int repeat, RunSpec spec) {}

    public List<ArmSpec> plan(String template, Map<String, Object> params, final int k) {
        final String tag = defaultTag();
        final List<ArmSpec> specs = new ArrayList<>();
        switch (template) {
            case "harness_effect" -> {
                final String model = str(params.get("model"));
                final int wall = num(params.getOrDefault("task_wall", 3600)), tokens = params.get("task_tokens") == null || "auto".equals(str(params.get("task_tokens"))) ? 60000 : num(params.get("task_tokens"));
                final List<String> arms = params.get("arms") instanceof List<?> l ? (List<String>) l : List.of("orch", "mono");
                int n = taskCount();   // the monolithic impl budget = N x task budget from the reference plan (matched, P-1)
                final String parallel = params.get("parallel") == null ? "3" : str(params.get("parallel"));
                final Integer window = contextWindow(params, model);
                final Integer firstTokenTimeout = firstTokenTimeout(params);
                final Integer compactionTrigger = compactionTrigger(params);
                final Integer reviewWallSec = reviewWallSec(params);
                // opt-in, unlike model_ab: harness_effect's own comparison is functional score, so a
                // reviewer isn't forced on every arm - only when the form actually named one
                final String reviewerModel = str(params.get("reviewer_model"));
                final boolean review = reviewerModel != null;
                final boolean noProbe = noContextProbe(params);
                final boolean blind = reviewBlind(params);
                final String trajReviewerModel = trajectoryReviewerModel(params, reviewerModel);
                final Double reviewWeight = reviewWeight(params), trajectoryWeight = trajectoryWeight(params);
                final String trajectoryUse = trajectoryUse(params);
                for (int i = 1; i <= k; i++)
                    for (String arm : arms)
                        specs.add(new ArmSpec(arm, i, new RunSpec(RUNG, model, null, "orchestrated".equals(armMode(arm)) ? "orchestrated" : "monolithic",
                                "reference", wall, tokens, arm.contains("mono") ? wall * n : null, arm.contains("mono") ? tokens * n : null,
                                "par".equals(arm) ? parallel : null, "mono+rules".equals(arm), review, review, reviewerModel, false, true, noProbe, false, window, firstTokenTimeout, compactionTrigger, reviewWallSec,
                                blind, trajReviewerModel, reviewWeight, trajectoryWeight, trajectoryUse,
                                "he-" + tag + "-" + shortName(model) + "-" + arm.replace("+", "") + "-r" + i)));
            }
            case "model_ab" -> {
                final String a = str(params.get("model_a")), b = str(params.get("model_b"));
                final int wall = num(params.getOrDefault("task_wall", 3600));
                // per arm: A and B can be different-sized models, so the window fallback must resolve per model
                final Integer windowA = contextWindow(params, a), windowB = contextWindow(params, b);
                final Integer firstTokenTimeout = firstTokenTimeout(params);
                final Integer compactionTrigger = compactionTrigger(params);
                final Integer reviewWallSec = reviewWallSec(params);
                final boolean noProbe = noContextProbe(params);
                final boolean blind = reviewBlind(params);
                final String reviewerModel = str(params.get("reviewer_model"));
                final String trajReviewerModel = trajectoryReviewerModel(params, reviewerModel);
                final Double reviewWeight = reviewWeight(params), trajectoryWeight = trajectoryWeight(params);
                final String trajectoryUse = trajectoryUse(params);
                for (int i = 1; i <= k; i++) {
                    // the arm suffix keeps A and B distinct; model-ab.sh always reviews both sides (self + trajectory)
                    specs.add(new ArmSpec("A", i, new RunSpec(RUNG, a, null, "orchestrated", "reference", wall, null, null, null, null, false,
                            true, true, reviewerModel, false, true, noProbe, false, windowA, firstTokenTimeout, compactionTrigger, reviewWallSec,
                            blind, trajReviewerModel, reviewWeight, trajectoryWeight, trajectoryUse,
                            "ab-" + tag + "-" + shortName(a) + "-a-r" + i)));
                    specs.add(new ArmSpec("B", i, new RunSpec(RUNG, b, null, "orchestrated", "reference", wall, null, null, null, null, false,
                            true, true, reviewerModel, false, true, noProbe, false, windowB, firstTokenTimeout, compactionTrigger, reviewWallSec,
                            blind, trajReviewerModel, reviewWeight, trajectoryWeight, trajectoryUse,
                            "ab-" + tag + "-" + shortName(b) + "-b-r" + i)));
                }
            }
            case "agent_ab" -> {
                final String model = str(params.get("model"));
                final int wall = num(params.getOrDefault("task_wall", 3600));
                final String mode = params.get("mode") == null ? "orchestrated" : str(params.get("mode"));
                final Integer window = contextWindow(params, model);
                final Integer firstTokenTimeout = firstTokenTimeout(params);
                final Integer compactionTrigger = compactionTrigger(params);
                final Integer reviewWallSec = reviewWallSec(params);
                // opt-in, same reasoning as harness_effect: agent_ab's own comparison is ref vs pi, not review score
                final String reviewerModel = str(params.get("reviewer_model"));
                final boolean review = reviewerModel != null;
                final boolean noProbe = noContextProbe(params);
                final boolean blind = reviewBlind(params);
                final String trajReviewerModel = trajectoryReviewerModel(params, reviewerModel);
                final Double reviewWeight = reviewWeight(params), trajectoryWeight = trajectoryWeight(params);
                final String trajectoryUse = trajectoryUse(params);
                for (int i = 1; i <= k; i++)
                    for (String agent : List.of("ref", "pi"))   // --harness=ref|pi: the flag the comparison is ABOUT
                        specs.add(new ArmSpec(agent, i, new RunSpec(RUNG, model, agent, mode, "reference",
                                "orchestrated".equals(mode) ? wall : null, null, "monolithic".equals(mode) ? wall * taskCount() : null,
                                "monolithic".equals(mode) ? 60000 * taskCount() : null, null, false, review, review, reviewerModel, false, true, noProbe, false, window, firstTokenTimeout, compactionTrigger, reviewWallSec,
                                blind, trajReviewerModel, reviewWeight, trajectoryWeight, trajectoryUse,
                                "aa-" + tag + "-" + shortName(model) + "-" + agent + "-r" + i)));
            }
            default -> throw new IllegalArgumentException("unknown template " + template + "; known: harness_effect, model_ab, agent_ab");
        }
        final Set<String> ids = new HashSet<>();
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

    /** an explicit params.first_token_timeout wins; unset leaves the run on ReferenceAgent's own default (180s). */
    Integer firstTokenTimeout(Map<String, Object> params) {
        return params.get("first_token_timeout") == null ? null : num(params.get("first_token_timeout"));
    }

    /** an explicit params.compaction_trigger wins (0 disables compaction entirely); unset leaves the
     *  run on the operator's configured default (application.yml: ace.compaction-trigger, 28000). */
    Integer compactionTrigger(Map<String, Object> params) {
        return params.get("compaction_trigger") == null ? null : num(params.get("compaction_trigger"));
    }

    /** an explicit params.review_wall_sec wins; unset leaves reviewer sessions on Reviews' own default (900s) */
    Integer reviewWallSec(Map<String, Object> params) {
        return params.get("review_wall_sec") == null ? null : num(params.get("review_wall_sec"));
    }

    static boolean noContextProbe(Map<String, Object> params) {
        return Boolean.TRUE.equals(params.get("no_context_probe"));
    }

    static boolean reviewBlind(Map<String, Object> params) {
        return Boolean.TRUE.equals(params.get("review_blind"));
    }

    /** an explicit params.trajectory_reviewer_model wins; unset defaults to the SAME reviewer as
     *  self-review, not Reviews' own silent fallback to the run's own local model - a run naming
     *  one reviewer presumably wants that reviewer for both jobs, not a divergent default for one of them. */
    static String trajectoryReviewerModel(Map<String, Object> params, String reviewerModel) {
        final String v = str(params.get("trajectory_reviewer_model"));
        return v != null ? v : reviewerModel;
    }

    /** an explicit params.review_weight wins; unset leaves Collect's own 0.1 default */
    Double reviewWeight(Map<String, Object> params) {
        return params.get("review_weight") == null ? null : numD(params.get("review_weight"));
    }

    /** an explicit params.trajectory_weight wins; unset leaves Collect's own 0.1 default */
    Double trajectoryWeight(Map<String, Object> params) {
        return params.get("trajectory_weight") == null ? null : numD(params.get("trajectory_weight"));
    }

    /** an explicit params.trajectory_use wins ("calibration" or "direct"); unset leaves Collect's
     *  own "calibration" default */
    static String trajectoryUse(Map<String, Object> params) {
        return str(params.get("trajectory_use"));
    }

    /** id -> max_model_len for whatever the model server currently serves — the same /v1/models query
     *  Preflight's "target model served" check makes. Best-effort: an unreachable server means an empty
     *  map (the probe owns the window), never a crash. */
    Map<String, Integer> localModelSpecs() {
        final Map<String, Integer> out = new LinkedHashMap<>();
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
        } catch (Exception e) {
            // this feeds task_wall/task_tokens budget multipliers for every monolithic arm - a
            // silent wrong fallback here silently mis-budgets every experiment created
            log.warn("could not read/parse task/REFERENCE_PLAN.md, falling back to a task count of 8: {}", e.toString());
            return 8;
        }
    }
    static String str(Object o) { return o == null ? null : String.valueOf(o); }
    static int num(Object o) { return o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o)); }
    static double numD(Object o) { return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o)); }

    /** the experiment row and its arms are one unit: an arm that fails to enqueue (a colliding run id,
     *  a results dir already on disk) must not leave an experiment behind that can never finish, so the
     *  whole sequence runs in one transaction and rolls back together. */
    public Map<String, Object> enqueue(final String name, String template, Map<String, Object> params, final int k, final String resultsDir, final String runnerSha, final String oracleSha) {
        final List<ArmSpec> specs = plan(template, params, k);
        return tx.execute(status -> {
            ExperimentsRecord rec = dsl.insertInto(EXPERIMENTS)
                    .set(EXPERIMENTS.ID, UUID.randomUUID())   // no AUTOINCREMENT on a UUID PK - assigned here
                    .set(EXPERIMENTS.NAME, name)
                    .set(EXPERIMENTS.TAG, defaultTag())
                    .set(EXPERIMENTS.TEMPLATE, template)
                    .set(EXPERIMENTS.PARAMS, toJson(params))
                    .set(EXPERIMENTS.K, k)
                    .set(EXPERIMENTS.PINNED_RUNNER_SHA, runnerSha)
                    .set(EXPERIMENTS.PINNED_ORACLE_SHA, oracleSha)
                    .returning()
                    .fetchOne();
            final UUID id = rec.getId();
            final List<Object> jobs = new ArrayList<>();
            for (ArmSpec s : specs)
                jobs.add(queue.enqueue(s.spec(), 0, resultsDir, runnerSha, oracleSha, id, s.arm(), s.repeat()));
            final Map<String, Object> out = JsonColumns.parse(rec.intoMap());
            out.put("jobs", jobs);
            return out;
        });
    }

    /** port of finalize_if_done: when every job of the experiment is terminal, compute the
     *  template's comparisons from the imported runs (the runs table is the source of truth) */
    public void finalizeIfDone(final UUID experimentId) {
        final ExperimentsRecord exp = dsl.selectFrom(EXPERIMENTS).where(EXPERIMENTS.ID.eq(experimentId)).fetchOne();
        if (exp == null || !"queued".equals(exp.getStatus())) return;
        var jobs = dsl.select(JOBS.ARM, JOBS.RUN_ID, JOBS.STATUS).from(JOBS)
                .where(JOBS.EXPERIMENT_ID.eq(experimentId)).orderBy(JOBS.REPEAT, JOBS.ARM).fetch();
        if (jobs.stream().anyMatch(j -> !RunSpec.TERMINAL.contains(j.get(JOBS.STATUS)))) return;
        // issue #18: every job cancelled (none ever succeeded or failed) means there is nothing to
        // compare - the experiment itself is cancelled, not "finished" with an empty comparison
        if (jobs.stream().allMatch(j -> "cancelled".equals(j.get(JOBS.STATUS)))) {
            dsl.update(EXPERIMENTS).set(EXPERIMENTS.STATUS, "cancelled").where(EXPERIMENTS.ID.eq(experimentId)).execute();
            return;
        }
        final Map<String, List<Path>> byArm = new LinkedHashMap<>();
        for (var j : jobs)
            if ("succeeded".equals(j.get(JOBS.STATUS)))
                Optional.ofNullable(dsl.select(RUNS.RESULTS_DIR).from(RUNS).where(RUNS.RUN_ID.eq(j.get(JOBS.RUN_ID))).fetchOne())
                        .ifPresent(r -> byArm.computeIfAbsent(j.get(JOBS.ARM), x -> new ArrayList<>()).add(Path.of(r.value1())));
        final Map<String, Object> params = exp.getParams() instanceof String ps ? fromJson(ps) : new LinkedHashMap<String, Object>();
        final String template = exp.getTemplate();
        final Map<String, Object> comparisons = new LinkedHashMap<>();
        for (String[] pair : templatePairs(template, params)) {
            final String label = pair[0] + "_vs_" + pair[1];
            final List<Path> a = byArm.getOrDefault(pair[0], List.of()), b = byArm.getOrDefault(pair[1], List.of());
            if (a.isEmpty() || b.isEmpty()) {
                comparisons.put(label, Map.of("error", "no succeeded, imported runs for arm " + (a.isEmpty() ? pair[0] : pair[1])));
                continue;
            }
            try {
                final List<Double> fa = functional(a), fb = functional(b);
                comparisons.put(label, Map.of("result", stats.compare(fa, fb, "functional"), "printed", ""));
            } catch (Exception e) {   // stats refused (not comparable / nothing to pool): a result, not a crash
                comparisons.put(label, Map.of("refused", String.valueOf(e)));
            }
        }
        comparisons.put("arms", jobs.stream().map(j -> Map.of("arm", j.get(JOBS.ARM), "status", j.get(JOBS.STATUS))).toList());
        dsl.update(EXPERIMENTS).set(EXPERIMENTS.STATUS, "finished").set(EXPERIMENTS.COMPARISON, toJson(comparisons))
                .where(EXPERIMENTS.ID.eq(experimentId)).execute();
    }

    /** the template's arm pairs (experiments.py: TEMPLATE_COMPARISONS) */
    List<String[]> templatePairs(String template, Map<String, Object> params) {
        if ("model_ab".equals(template)) return List.<String[]>of(new String[]{"A", "B"});
        if ("agent_ab".equals(template))
            return List.<String[]>of(new String[]{String.valueOf(params.getOrDefault("agents_a", "ref")), String.valueOf(params.getOrDefault("agents_b", "pi"))});
        final List<String[]> pairs = new ArrayList<>(List.<String[]>of(new String[]{"orch", "mono"}));
        final List<String> arms = params.get("arms") instanceof List<?> l ? (List<String>) l : List.of("orch", "mono");
        if (arms.contains("par")) pairs.add(new String[]{"par", "orch"});
        if (arms.contains("mono+rules")) { pairs.add(new String[]{"mono+rules", "mono"}); pairs.add(new String[]{"orch", "mono+rules"}); }
        return pairs;
    }

    List<Double> functional(List<Path> dirs) {
        final List<Double> out = new ArrayList<>();
        for (Path d : dirs) {
            try {
                com.fasterxml.jackson.databind.JsonNode v = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(d.resolve("oracle.json").toFile()).path("functional_score_pct");
                if (v.isNumber()) out.add(v.asDouble());
            } catch (Exception e) {
                // a run silently dropped here still lets the comparison "succeed", just on a
                // smaller/skewed sample - with no record anywhere that data was excluded
                log.warn("could not read functional_score_pct from {}/oracle.json, excluding it from the comparison: {}", d, e.toString());
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no functional scores on one side");
        return out;
    }

    Map<String, Object> fromJson(String s) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Map.class); }
        catch (Exception e) {
            // falling back to {} silently drops the experiment's own params (e.g. agents_a/agents_b
            // for agent_ab), which templatePairs() reads - a wrong comparison pairing with no trace
            log.warn("could not parse stored experiment params, treating as empty: {}", e.toString());
            return new LinkedHashMap<>();
        }
    }

    private String toJson(final Object o) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o); }
        catch (Exception e) {
            // this stores the experiment's own comparison result - silently storing "{}" here loses
            // the actual A/B comparison the caller just computed
            log.warn("could not serialize {} for storage, storing as empty: {}", o, e.toString());
            return "{}";
        }
    }
}
