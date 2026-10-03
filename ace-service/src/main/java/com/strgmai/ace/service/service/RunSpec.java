package com.strgmai.ace.service.service;

import java.util.*;

/** Port of service/queue.py's RunSpec: the flags a queued run may carry, in argv order, with the
 *  same validation (known rung, positive budgets, bounded patterns). argv() rebuilds the CLI
 *  record stored on the job. */
public record RunSpec(String task, String model, String harness, String mode, String planSource, String phases, Integer taskWall, Integer taskTokens,
                      Integer implWall, Integer implTokens, Integer planTokens, Integer wallBudget, String parallel, String parallelPlan, Double parallelWeight,
                      boolean systemRules, boolean selfReview,
                      boolean trajectoryReview, String reviewerModel, boolean handoffNotes, boolean manageDocker,
                      boolean noContextProbe, boolean contextProbeFresh, boolean keepWorkspace, boolean skipDocker,
                      Integer contextWindow, Integer firstTokenTimeout, Integer compactionTrigger, Integer reviewWallSec,
                      boolean reviewBlind, String trajectoryReviewerModel, Double reviewWeight, Double trajectoryWeight,
                      String trajectoryUse, String javaHome, String runId,
                      Double temperature, Double topP, Integer topK, Double repetitionPenalty, Integer maxTokens, String reasoningEffort,
                      Integer parallelPlanWall, Integer handoffWall, Integer wrapupWall, Integer maxTurns, Integer reviewTokens,
                      Integer fixWall, Integer fixTokens, Integer parallelPlanTokens, Integer handoffTokens, Integer wrapupTokens,
                      Integer dockerMemoryMib, boolean dockerKeepWarm) {

    public static final String RUN_ID = "^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$";
    public static final List<String> REASONING_EFFORTS = List.of("none", "low", "medium", "high");
    /** the rung's own multi-phase sequence (RunBench.PHASES) - --phases picks a subset of these to
     *  actually run (queue.py's phases field), everything else is never a valid value here. */
    public static final Set<String> PHASES = Set.of("p0_definition", "p1_plan", "p2_implementation");

    public RunSpec {
        if (phases != null && !phases.isBlank())
            for (final String p : phases.split(","))
                if (!PHASES.contains(p)) throw new IllegalArgumentException("unknown phase: " + p + " (must be one of " + PHASES + ")");
        // implWall/implTokens/contextWindow/maxTokens are capacities, not spending budgets a run can
        // choose to leave uncapped (a 0-token generation or a 0-wide context window is meaningless) -
        // these still require a genuinely positive value. planTokens/wallBudget (queue.py: gt=0) are
        // the same class - a 0-token plan budget or a 0-second smoke-test wall is meaningless too.
        implWall = positive(implWall); implTokens = positive(implTokens);
        contextWindow = positive(contextWindow); maxTokens = positive(maxTokens);
        planTokens = positive(planTokens); wallBudget = positive(wallBudget);
        // a 0 or negative memory cap is just as meaningless as a 0-wide context window
        dockerMemoryMib = positive(dockerMemoryMib);
        // every other wall/token budget: 0 means unlimited (the system-wide "0 = no budget"
        // convention) rather than an error - was rejected outright before this, the same class of
        // bug as #90's ExperimentsService.taskCount() silent-fallback, just enforced as a hard 400
        // instead of a silent wrong value.
        taskWall = nonNegative(taskWall); taskTokens = nonNegative(taskTokens);
        firstTokenTimeout = nonNegative(firstTokenTimeout); reviewWallSec = nonNegative(reviewWallSec);
        reviewTokens = nonNegative(reviewTokens);
        // PARALLEL_PLAN/handoff/wrap-up walls (found live 2026-09-25): were fixed literals in
        // RunBench.java (600/300/300*scale) with no run-level control at all
        parallelPlanWall = nonNegative(parallelPlanWall); handoffWall = nonNegative(handoffWall); wrapupWall = nonNegative(wrapupWall);
        // the FIX step (merge-conflict repair after a broken parallel wave): was a fixed literal
        // (900s/20000 tokens) with no run-level control at all, same class of bug as the walls above
        fixWall = nonNegative(fixWall); fixTokens = nonNegative(fixTokens);
        // PARALLEL_PLAN/handoff/wrap-up TOKEN budgets (found live 2026-09-27): were fixed literals
        // (8000/3000/2000) with no run-level control, same class of bug as their walls above
        parallelPlanTokens = nonNegative(parallelPlanTokens); handoffTokens = nonNegative(handoffTokens); wrapupTokens = nonNegative(wrapupTokens);
        // #95: unlike every other phase/session budget, the turn cap used to be a hardcoded
        // ReferenceAgent-local constant (400) with no run-level override at all. Turn count, not a
        // token/time budget - 0 turns is meaningless, so this still requires a positive value.
        maxTurns = positive(maxTurns);
        // 0 is a legitimate value here (disables compaction) - unlike the budgets above, only reject negative
        if (compactionTrigger != null && compactionTrigger < 0) throw new IllegalArgumentException("compactionTrigger must be >= 0 (0 disables compaction): " + compactionTrigger);
        if (reviewWeight != null && (reviewWeight < 0 || reviewWeight > 1)) throw new IllegalArgumentException("reviewWeight must be within 0..1: " + reviewWeight);
        if (trajectoryWeight != null && (trajectoryWeight < 0 || trajectoryWeight > 1)) throw new IllegalArgumentException("trajectoryWeight must be within 0..1: " + trajectoryWeight);
        if (trajectoryUse != null && !trajectoryUse.isBlank() && !List.of("calibration", "direct").contains(trajectoryUse))
            throw new IllegalArgumentException("trajectoryUse must be calibration or direct: " + trajectoryUse);
        if (parallelPlan != null && !parallelPlan.isBlank() && !List.of("on", "off").contains(parallelPlan))
            throw new IllegalArgumentException("parallelPlan must be on or off: " + parallelPlan);
        if (parallelWeight != null && (parallelWeight < 0 || parallelWeight > 1))
            throw new IllegalArgumentException("parallelWeight must be within 0..1: " + parallelWeight);
        // queue.py's PATH pattern: no embedded NUL/CR/LF, 1-500 chars - this ends up in an env var and
        // on a shell command line (RunBenchSupport.scrubbedEnv), so the same injection-shaped values
        // Python already rejected stay rejected here too.
        if (javaHome != null && !javaHome.matches("[^\\x00\\r\\n]{1,500}"))
            throw new IllegalArgumentException("invalid java_home path: " + javaHome);
        if (runId != null && !runId.matches(RUN_ID)) throw new IllegalArgumentException("invalid run id: " + runId);
        if (mode != null && !List.of("monolithic", "orchestrated").contains(mode))
            throw new IllegalArgumentException("mode must be monolithic or orchestrated");
        if (planSource != null && !List.of("agent", "reference").contains(planSource))
            throw new IllegalArgumentException("plan_source must be agent or reference");
        // sampler knobs (#72): temperature/top_p/repetition_penalty are unbounded on the wire (oMLX's
        // own choice to reject or clamp), but a negative value is never meaningful for any of them
        if (temperature != null && temperature < 0) throw new IllegalArgumentException("temperature must be >= 0: " + temperature);
        if (topP != null && (topP <= 0 || topP > 1)) throw new IllegalArgumentException("topP must be within (0, 1]: " + topP);
        if (topK != null && topK < 1) throw new IllegalArgumentException("topK must be >= 1: " + topK);
        if (repetitionPenalty != null && repetitionPenalty < 0) throw new IllegalArgumentException("repetitionPenalty must be >= 0: " + repetitionPenalty);
        if (reasoningEffort != null && !reasoningEffort.isBlank() && !REASONING_EFFORTS.contains(reasoningEffort))
            throw new IllegalArgumentException("reasoningEffort must be one of " + REASONING_EFFORTS + ": " + reasoningEffort);
    }

    private static Integer positive(Integer v) { return v == null || v > 0 ? v : failPositive(v); }
    private static Integer failPositive(Integer v) { throw new IllegalArgumentException("must be positive: " + v); }
    /** for a budget where 0 is a legitimate value (unlimited - the system-wide "0 = no budget"
     *  convention): only negative is rejected. */
    private static Integer nonNegative(Integer v) { return v == null || v >= 0 ? v : failNonNegative(v); }
    private static Integer failNonNegative(Integer v) { throw new IllegalArgumentException("budgets must be >= 0 (0 = unlimited): " + v); }

    public static final Set<String> TERMINAL = Set.of("succeeded", "failed", "cancelled");

    public List<String> argv(final String resolvedRunId) {
        final List<String> args = new ArrayList<>(List.of("--run-id=" + resolvedRunId, "--task=" + task));
        if (model != null) args.add("--model=" + model);
        if (harness != null) args.add("--harness=" + harness);
        if (mode != null) args.add("--mode=" + mode);
        if (planSource != null) args.add("--plan-source=" + planSource);
        if (phases != null && !phases.isBlank()) args.add("--phases=" + phases);
        if (taskWall != null) args.add("--task-wall=" + taskWall);
        if (taskTokens != null) args.add("--task-tokens=" + taskTokens);
        if (implWall != null) args.add("--impl-wall=" + implWall);
        if (implTokens != null) args.add("--impl-tokens=" + implTokens);
        if (planTokens != null) args.add("--plan-tokens=" + planTokens);
        if (wallBudget != null) args.add("--wall-budget=" + wallBudget);
        if (parallel != null && !parallel.isBlank()) args.add("--parallel=" + parallel);
        if (parallelPlan != null && !parallelPlan.isBlank()) args.add("--parallel-plan=" + parallelPlan);
        if (parallelWeight != null) args.add("--parallel-weight=" + parallelWeight);
        if (systemRules) args.add("--system-rules");
        if (selfReview) args.add("--self-review");
        if (trajectoryReview) args.add("--trajectory-review");
        if (reviewerModel != null) args.add("--reviewer-model=" + reviewerModel);
        if (handoffNotes) args.add("--handoff-notes");
        if (manageDocker) args.add("--manage-docker");
        if (noContextProbe) args.add("--no-context-probe");
        if (contextProbeFresh) args.add("--context-probe-fresh");
        if (keepWorkspace) args.add("--keep-workspace");
        if (skipDocker) args.add("--skip-docker");
        // a pinned window: the run uses it as-is and skips step 0 (the probe exists to MEASURE one)
        if (contextWindow != null) args.add("--context-window=" + contextWindow);
        if (firstTokenTimeout != null) args.add("--first-token-timeout=" + firstTokenTimeout);
        if (compactionTrigger != null) args.add("--compaction-trigger=" + compactionTrigger);
        if (reviewWallSec != null) args.add("--review-wall-sec=" + reviewWallSec);
        if (reviewTokens != null) args.add("--review-tokens=" + reviewTokens);
        if (reviewBlind) args.add("--review-blind");
        if (trajectoryReviewerModel != null) args.add("--trajectory-reviewer-model=" + trajectoryReviewerModel);
        if (reviewWeight != null) args.add("--review-weight=" + reviewWeight);
        if (trajectoryWeight != null) args.add("--trajectory-weight=" + trajectoryWeight);
        if (trajectoryUse != null && !trajectoryUse.isBlank()) args.add("--trajectory-use=" + trajectoryUse);
        if (javaHome != null && !javaHome.isBlank()) args.add("--java-home=" + javaHome);
        if (temperature != null) args.add("--temperature=" + temperature);
        if (topP != null) args.add("--top-p=" + topP);
        if (topK != null) args.add("--top-k=" + topK);
        if (repetitionPenalty != null) args.add("--repetition-penalty=" + repetitionPenalty);
        if (maxTokens != null) args.add("--max-tokens=" + maxTokens);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) args.add("--reasoning-effort=" + reasoningEffort);
        if (parallelPlanWall != null) args.add("--parallel-plan-wall=" + parallelPlanWall);
        if (handoffWall != null) args.add("--handoff-wall=" + handoffWall);
        if (wrapupWall != null) args.add("--wrapup-wall=" + wrapupWall);
        if (maxTurns != null) args.add("--max-turns=" + maxTurns);
        if (fixWall != null) args.add("--fix-wall=" + fixWall);
        if (fixTokens != null) args.add("--fix-tokens=" + fixTokens);
        if (parallelPlanTokens != null) args.add("--parallel-plan-tokens=" + parallelPlanTokens);
        if (handoffTokens != null) args.add("--handoff-tokens=" + handoffTokens);
        if (wrapupTokens != null) args.add("--wrapup-tokens=" + wrapupTokens);
        if (dockerMemoryMib != null) args.add("--docker-memory-mib=" + dockerMemoryMib);
        if (dockerKeepWarm) args.add("--docker-keep-warm");
        return args;
    }

    public static String defaultRunId(final String prefix) {
        return prefix + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.now());
    }

    public static Builder builder() { return new Builder(); }

    /** #77/#78: a fluent, named way to build a RunSpec, so a caller sets fields by NAME instead of by
     *  matching a value's position in a 36-argument constructor call - the exact hazard that let a
     *  future field addition or reorder silently swap two same-typed fields with nothing short of a
     *  test on that specific field able to catch it. build() is the one place field ORDER still
     *  matters, immediately next to RunSpec's own field list, where the two are easiest to keep in
     *  sync. */
    public static final class Builder {
        private String task, model, harness, mode, planSource, phases, parallel, parallelPlan, reviewerModel,
                trajectoryReviewerModel, trajectoryUse, javaHome, runId, reasoningEffort;
        private Integer taskWall, taskTokens, implWall, implTokens, planTokens, wallBudget, contextWindow, firstTokenTimeout,
                compactionTrigger, reviewWallSec, topK, maxTokens, parallelPlanWall, handoffWall, wrapupWall, maxTurns, reviewTokens,
                fixWall, fixTokens, parallelPlanTokens, handoffTokens, wrapupTokens, dockerMemoryMib;
        private boolean systemRules, selfReview, trajectoryReview, handoffNotes, manageDocker,
                noContextProbe, contextProbeFresh, keepWorkspace, skipDocker, reviewBlind, dockerKeepWarm;
        private Double reviewWeight, trajectoryWeight, parallelWeight, temperature, topP, repetitionPenalty;

        public Builder task(final String v) { task = v; return this; }
        public Builder model(final String v) { model = v; return this; }
        public Builder harness(final String v) { harness = v; return this; }
        public Builder mode(final String v) { mode = v; return this; }
        public Builder planSource(final String v) { planSource = v; return this; }
        public Builder phases(final String v) { phases = v; return this; }
        public Builder taskWall(final Integer v) { taskWall = v; return this; }
        public Builder taskTokens(final Integer v) { taskTokens = v; return this; }
        public Builder implWall(final Integer v) { implWall = v; return this; }
        public Builder implTokens(final Integer v) { implTokens = v; return this; }
        public Builder planTokens(final Integer v) { planTokens = v; return this; }
        public Builder wallBudget(final Integer v) { wallBudget = v; return this; }
        public Builder parallel(final String v) { parallel = v; return this; }
        public Builder parallelPlan(final String v) { parallelPlan = v; return this; }
        public Builder parallelWeight(final Double v) { parallelWeight = v; return this; }
        public Builder systemRules(final boolean v) { systemRules = v; return this; }
        public Builder selfReview(final boolean v) { selfReview = v; return this; }
        public Builder trajectoryReview(final boolean v) { trajectoryReview = v; return this; }
        public Builder reviewerModel(final String v) { reviewerModel = v; return this; }
        public Builder handoffNotes(final boolean v) { handoffNotes = v; return this; }
        public Builder manageDocker(final boolean v) { manageDocker = v; return this; }
        public Builder noContextProbe(final boolean v) { noContextProbe = v; return this; }
        public Builder contextProbeFresh(final boolean v) { contextProbeFresh = v; return this; }
        public Builder keepWorkspace(final boolean v) { keepWorkspace = v; return this; }
        public Builder skipDocker(final boolean v) { skipDocker = v; return this; }
        public Builder contextWindow(final Integer v) { contextWindow = v; return this; }
        public Builder firstTokenTimeout(final Integer v) { firstTokenTimeout = v; return this; }
        public Builder compactionTrigger(final Integer v) { compactionTrigger = v; return this; }
        public Builder reviewWallSec(final Integer v) { reviewWallSec = v; return this; }
        public Builder reviewTokens(final Integer v) { reviewTokens = v; return this; }
        public Builder reviewBlind(final boolean v) { reviewBlind = v; return this; }
        public Builder trajectoryReviewerModel(final String v) { trajectoryReviewerModel = v; return this; }
        public Builder reviewWeight(final Double v) { reviewWeight = v; return this; }
        public Builder trajectoryWeight(final Double v) { trajectoryWeight = v; return this; }
        public Builder trajectoryUse(final String v) { trajectoryUse = v; return this; }
        public Builder javaHome(final String v) { javaHome = v; return this; }
        public Builder runId(final String v) { runId = v; return this; }
        public Builder temperature(final Double v) { temperature = v; return this; }
        public Builder topP(final Double v) { topP = v; return this; }
        public Builder topK(final Integer v) { topK = v; return this; }
        public Builder repetitionPenalty(final Double v) { repetitionPenalty = v; return this; }
        public Builder maxTokens(final Integer v) { maxTokens = v; return this; }
        public Builder reasoningEffort(final String v) { reasoningEffort = v; return this; }
        public Builder parallelPlanWall(final Integer v) { parallelPlanWall = v; return this; }
        public Builder handoffWall(final Integer v) { handoffWall = v; return this; }
        public Builder wrapupWall(final Integer v) { wrapupWall = v; return this; }
        public Builder maxTurns(final Integer v) { maxTurns = v; return this; }
        public Builder fixWall(final Integer v) { fixWall = v; return this; }
        public Builder fixTokens(final Integer v) { fixTokens = v; return this; }
        public Builder parallelPlanTokens(final Integer v) { parallelPlanTokens = v; return this; }
        public Builder handoffTokens(final Integer v) { handoffTokens = v; return this; }
        public Builder wrapupTokens(final Integer v) { wrapupTokens = v; return this; }
        public Builder dockerMemoryMib(final Integer v) { dockerMemoryMib = v; return this; }
        public Builder dockerKeepWarm(final boolean v) { dockerKeepWarm = v; return this; }

        public RunSpec build() {
            return new RunSpec(task, model, harness, mode, planSource, phases, taskWall, taskTokens, implWall, implTokens,
                    planTokens, wallBudget, parallel, parallelPlan, parallelWeight, systemRules, selfReview, trajectoryReview, reviewerModel, handoffNotes, manageDocker,
                    noContextProbe, contextProbeFresh, keepWorkspace, skipDocker, contextWindow, firstTokenTimeout, compactionTrigger, reviewWallSec,
                    reviewBlind, trajectoryReviewerModel, reviewWeight, trajectoryWeight, trajectoryUse, javaHome, runId,
                    temperature, topP, topK, repetitionPenalty, maxTokens, reasoningEffort, parallelPlanWall, handoffWall, wrapupWall, maxTurns, reviewTokens,
                    fixWall, fixTokens, parallelPlanTokens, handoffTokens, wrapupTokens, dockerMemoryMib, dockerKeepWarm);
        }
    }

    /** builds a RunSpec from a JSON spec map (BenchController's /api/jobs body), by FIELD NAME rather
     *  than by matching a value's position against the constructor's 36-argument list (#77) - the same
     *  extraction and defaulting BenchController.enqueue() used to do inline, moved here so it lives
     *  next to the fields it populates. */
    public static RunSpec from(final Map<String, Object> spec) {
        return builder()
                .task(str(spec, "task")).model(str(spec, "model")).harness(str(spec, "harness"))
                .mode(str(spec, "mode")).planSource(str(spec, "plan_source")).phases(str(spec, "phases"))
                .taskWall(intOf(spec, "task_wall")).taskTokens(intOf(spec, "task_tokens"))
                .implWall(intOf(spec, "impl_wall")).implTokens(intOf(spec, "impl_tokens"))
                .planTokens(intOf(spec, "plan_tokens")).wallBudget(intOf(spec, "wall_budget"))
                .parallel(str(spec, "parallel")).parallelPlan(str(spec, "parallel_plan")).parallelWeight(doubleOf(spec, "parallel_weight"))
                .systemRules(bool(spec, "system_rules", false)).selfReview(bool(spec, "self_review", false))
                .trajectoryReview(bool(spec, "trajectory_review", false)).reviewerModel(str(spec, "reviewer_model"))
                .handoffNotes(bool(spec, "handoff_notes", false)).manageDocker(bool(spec, "manage_docker", true))
                .noContextProbe(bool(spec, "no_context_probe", false)).contextProbeFresh(bool(spec, "context_probe_fresh", false))
                .keepWorkspace(bool(spec, "keep_workspace", false)).skipDocker(bool(spec, "skip_docker", false))
                .contextWindow(intOf(spec, "context_window")).firstTokenTimeout(intOf(spec, "first_token_timeout"))
                .compactionTrigger(intOf(spec, "compaction_trigger")).reviewWallSec(intOf(spec, "review_wall_sec"))
                .reviewTokens(intOf(spec, "review_tokens"))
                .reviewBlind(bool(spec, "review_blind", false)).trajectoryReviewerModel(str(spec, "trajectory_reviewer_model"))
                .reviewWeight(doubleOf(spec, "review_weight")).trajectoryWeight(doubleOf(spec, "trajectory_weight"))
                .trajectoryUse(str(spec, "trajectory_use")).javaHome(str(spec, "java_home")).runId(str(spec, "run_id"))
                .temperature(doubleOf(spec, "temperature")).topP(doubleOf(spec, "top_p"))
                .topK(intOf(spec, "top_k")).repetitionPenalty(doubleOf(spec, "repetition_penalty"))
                .maxTokens(intOf(spec, "max_tokens")).reasoningEffort(str(spec, "reasoning_effort"))
                .parallelPlanWall(intOf(spec, "parallel_plan_wall")).handoffWall(intOf(spec, "handoff_wall"))
                .wrapupWall(intOf(spec, "wrapup_wall")).maxTurns(intOf(spec, "max_turns"))
                .fixWall(intOf(spec, "fix_wall")).fixTokens(intOf(spec, "fix_tokens"))
                .parallelPlanTokens(intOf(spec, "parallel_plan_tokens")).handoffTokens(intOf(spec, "handoff_tokens"))
                .wrapupTokens(intOf(spec, "wrapup_tokens"))
                .dockerMemoryMib(intOf(spec, "docker_memory_mib")).dockerKeepWarm(bool(spec, "docker_keep_warm", false))
                .build();
    }

    private static String str(final Map<String, Object> spec, final String key) {
        final Object o = spec.get(key);
        return o == null ? null : String.valueOf(o);
    }
    private static boolean bool(final Map<String, Object> spec, final String key, final boolean dflt) {
        return Boolean.parseBoolean(String.valueOf(spec.getOrDefault(key, dflt)));
    }
    private static Integer intOf(final Map<String, Object> spec, final String key) {
        final Object o = spec.get(key);
        if (o instanceof Number n) return n.intValue();
        try { return o == null || String.valueOf(o).isBlank() ? null : Integer.parseInt(String.valueOf(o)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("not a number: " + key + "=" + o); }
    }
    private static Double doubleOf(final Map<String, Object> spec, final String key) {
        final Object o = spec.get(key);
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null || String.valueOf(o).isBlank() ? null : Double.parseDouble(String.valueOf(o)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("not a number: " + key + "=" + o); }
    }
}
