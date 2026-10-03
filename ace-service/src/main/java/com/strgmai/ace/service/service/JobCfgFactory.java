package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** #82: the argv -> cfg translation WorkerService.poll() used to build inline (~30 lines, growing
 *  with every new run flag), extracted so it's directly unit-testable without a live job/worker/DB
 *  and so poll() itself stays orchestration, not parsing. */
final class JobCfgFactory {
    private JobCfgFactory() {}

    static Map<String, Object> build(final List<String> argv, final BenchProperties props) {
        final Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("results_root", props.resultsDir());
        cfg.put("workspace_root", props.workspaceRoot());
        cfg.put("model", argv.stream().filter(a -> a.startsWith("--model=")).map(a -> a.substring(8)).findFirst().orElse(props.model()));
        cfg.put("system_base_url", null);
        // #phases: RunSpec's own --phases flag (restricts a rung's multi-phase sequence to a subset)
        // had nothing downstream reading it back out of argv into cfg either, same class of bug as
        // --impl-wall/--impl-tokens below
        if (WorkerService.flag(argv, "--phases") != null) cfg.put("phases", WorkerService.flag(argv, "--phases"));
        // #228: 8 more RunSpec flags collected by the UI and emitted by RunSpec.argv() with nothing
        // downstream ever reading them back out of argv into cfg either, same class of bug as --phases
        if (WorkerService.flag(argv, "--plan-wall") != null) cfg.put("plan_wall_sec", Integer.parseInt(WorkerService.flag(argv, "--plan-wall")));
        if (WorkerService.flag(argv, "--plan-tokens") != null) cfg.put("plan_tokens", Long.parseLong(WorkerService.flag(argv, "--plan-tokens")));
        if (WorkerService.flag(argv, "--wall-budget") != null) cfg.put("wall_budget_override", Integer.parseInt(WorkerService.flag(argv, "--wall-budget")));
        if (WorkerService.flag(argv, "--java-home") != null) cfg.put("java_home", WorkerService.flag(argv, "--java-home"));
        cfg.put("keep_workspace", argv.contains("--keep-workspace"));
        cfg.put("skip_docker", argv.contains("--skip-docker"));
        if (WorkerService.flag(argv, "--parallel-plan") != null)
            cfg.put("parallel_plan_enabled", "on".equals(WorkerService.flag(argv, "--parallel-plan")));
        if (WorkerService.flag(argv, "--parallel-weight") != null) {
            final Map<String, Object> parallelPlan = new LinkedHashMap<>();
            parallelPlan.put("weight", Double.parseDouble(WorkerService.flag(argv, "--parallel-weight")));
            cfg.put("parallel_plan", parallelPlan);
        }
        if (WorkerService.flag(argv, "--task-wall") != null) cfg.put("task_wall_sec", Integer.parseInt(WorkerService.flag(argv, "--task-wall")));
        if (WorkerService.flag(argv, "--task-tokens") != null) cfg.put("task_tokens", Long.parseLong(WorkerService.flag(argv, "--task-tokens")));
        // #173: an experiment matches a monolithic arm's single implementation-phase budget to an
        // orchestrated arm's N-tasks x per-task budget (design rule 10) by computing --impl-wall/
        // --impl-tokens (ExperimentsService) and passing them on the job's own argv (RunSpec) - but
        // nothing downstream ever read them back out of argv into cfg, so they were silently dropped
        if (WorkerService.flag(argv, "--impl-wall") != null) cfg.put("impl_wall_sec", Integer.parseInt(WorkerService.flag(argv, "--impl-wall")));
        if (WorkerService.flag(argv, "--impl-tokens") != null) cfg.put("impl_tokens", Long.parseLong(WorkerService.flag(argv, "--impl-tokens")));
        if (WorkerService.flag(argv, "--first-token-timeout") != null) cfg.put("first_token_timeout_sec", Integer.parseInt(WorkerService.flag(argv, "--first-token-timeout")));
        if (WorkerService.flag(argv, "--compaction-trigger") != null) cfg.put("compaction_trigger", Integer.parseInt(WorkerService.flag(argv, "--compaction-trigger")));
        // #95: unlike every other phase/session budget, the turn cap used to be a hardcoded
        // ReferenceAgent-local constant with no run-level override at all
        if (WorkerService.flag(argv, "--max-turns") != null) cfg.put("max_turns", Integer.parseInt(WorkerService.flag(argv, "--max-turns")));
        // sampler knobs (#72) - RunBench folds these into a SamplerOverrides pinned onto
        // every request by RecordingProxy; unset means "use the operator-wide default"
        if (WorkerService.flag(argv, "--temperature") != null) cfg.put("temperature", Double.parseDouble(WorkerService.flag(argv, "--temperature")));
        if (WorkerService.flag(argv, "--top-p") != null) cfg.put("top_p", Double.parseDouble(WorkerService.flag(argv, "--top-p")));
        if (WorkerService.flag(argv, "--top-k") != null) cfg.put("top_k", Integer.parseInt(WorkerService.flag(argv, "--top-k")));
        if (WorkerService.flag(argv, "--repetition-penalty") != null) cfg.put("repetition_penalty", Double.parseDouble(WorkerService.flag(argv, "--repetition-penalty")));
        if (WorkerService.flag(argv, "--max-tokens") != null) cfg.put("max_tokens_override", Integer.parseInt(WorkerService.flag(argv, "--max-tokens")));
        if (WorkerService.flag(argv, "--reasoning-effort") != null) cfg.put("reasoning_effort", WorkerService.flag(argv, "--reasoning-effort"));
        // PARALLEL_PLAN/handoff/wrap-up walls (found live 2026-09-25): were fixed literals
        // in RunBench.java (600/300/300*scale) with no run-level control at all
        if (WorkerService.flag(argv, "--parallel-plan-wall") != null) cfg.put("parallel_plan_wall_sec", Integer.parseInt(WorkerService.flag(argv, "--parallel-plan-wall")));
        if (WorkerService.flag(argv, "--handoff-wall") != null) cfg.put("handoff_wall_sec", Integer.parseInt(WorkerService.flag(argv, "--handoff-wall")));
        if (WorkerService.flag(argv, "--wrapup-wall") != null) cfg.put("wrapup_wall_sec", Integer.parseInt(WorkerService.flag(argv, "--wrapup-wall")));
        // the FIX step (merge-conflict repair after a broken parallel wave): was a fixed literal
        // (900s/20000 tokens) with no run-level control at all
        if (WorkerService.flag(argv, "--fix-wall") != null) cfg.put("fix_wall_sec", Integer.parseInt(WorkerService.flag(argv, "--fix-wall")));
        if (WorkerService.flag(argv, "--fix-tokens") != null) cfg.put("fix_tokens", Integer.parseInt(WorkerService.flag(argv, "--fix-tokens")));
        // PARALLEL_PLAN/handoff/wrap-up TOKEN budgets (found live 2026-09-27): were fixed literals
        // (8000/3000/2000) with no run-level control, same class of bug as their walls above
        if (WorkerService.flag(argv, "--parallel-plan-tokens") != null) cfg.put("parallel_plan_tokens", Integer.parseInt(WorkerService.flag(argv, "--parallel-plan-tokens")));
        if (WorkerService.flag(argv, "--handoff-tokens") != null) cfg.put("handoff_tokens", Integer.parseInt(WorkerService.flag(argv, "--handoff-tokens")));
        if (WorkerService.flag(argv, "--wrapup-tokens") != null) cfg.put("wrapup_tokens", Integer.parseInt(WorkerService.flag(argv, "--wrapup-tokens")));
        // a pinned --context-window IS the window: it skips step 0, whose whole job is to measure one
        final String window = WorkerService.flag(argv, "--context-window");
        if (window != null) cfg.put("context_window", Integer.parseInt(window));
        // the context probe (step 0) is the default for direct runs; queue runs opt in via ACE_JLS_CONTEXT_PROBE;
        // --no-context-probe is a per-run override that skips it even when nothing is pinned
        cfg.put("context_probe", !argv.contains("--no-context-probe") && window == null
                && Boolean.parseBoolean(System.getenv().getOrDefault("ACE_JLS_CONTEXT_PROBE", "true")));   // Python default: the probe runs
        cfg.put("context_probe_fresh", argv.contains("--context-probe-fresh"));
        if (WorkerService.flag(argv, "--parallel") != null) {
            if ("auto".equals(WorkerService.flag(argv, "--parallel"))) cfg.put("parallel_auto", true);
            else cfg.put("parallel", Integer.parseInt(WorkerService.flag(argv, "--parallel")));
        }
        cfg.put("manage_docker", argv.contains("--manage-docker"));
        // caps Docker Desktop's VM memory so it doesn't compete as hard with a co-resident model
        // server; --docker-keep-warm skips the idle monitor's mid-run stop/restart cycling
        if (WorkerService.flag(argv, "--docker-memory-mib") != null) cfg.put("docker_memory_mib", Integer.parseInt(WorkerService.flag(argv, "--docker-memory-mib")));
        cfg.put("docker_keep_warm", argv.contains("--docker-keep-warm"));
        // opt-in control over how much budget efficiency counts toward agent_result_pct (Collect.java)
        if (WorkerService.flag(argv, "--efficiency-weight") != null) cfg.put("efficiency_weight", Double.parseDouble(WorkerService.flag(argv, "--efficiency-weight")));
        // Map.of() rejects a null value outright - "model" IS null whenever review is enabled
        // without an explicit --reviewer-model (self-review alone still needs a reviewer picked
        // downstream, but that is RunBench's decision to make, not a reason to crash the worker)
        final Map<String, Object> review = new LinkedHashMap<>();
        review.put("enabled", argv.contains("--self-review"));
        review.put("model", WorkerService.flag(argv, "--reviewer-model"));
        review.put("blind", argv.contains("--review-blind"));
        // shared by both self-review and trajectory-review (Reviews.runReviewerSession reads
        // this same "review" map's wall_sec/tokens for either kind of reviewer session)
        if (WorkerService.flag(argv, "--review-wall-sec") != null) review.put("wall_sec", Integer.parseInt(WorkerService.flag(argv, "--review-wall-sec")));
        if (WorkerService.flag(argv, "--review-tokens") != null) review.put("tokens", Integer.parseInt(WorkerService.flag(argv, "--review-tokens")));
        // unset -> Collect's own 0.1 default; only set when the run actually pinned one
        if (WorkerService.flag(argv, "--review-weight") != null) review.put("weight", Double.parseDouble(WorkerService.flag(argv, "--review-weight")));
        cfg.put("review", review);
        final Map<String, Object> trajectoryReview = new LinkedHashMap<>();
        trajectoryReview.put("enabled", argv.contains("--trajectory-review"));
        trajectoryReview.put("model", WorkerService.flag(argv, "--trajectory-reviewer-model"));
        if (WorkerService.flag(argv, "--trajectory-weight") != null) trajectoryReview.put("weight", Double.parseDouble(WorkerService.flag(argv, "--trajectory-weight")));
        trajectoryReview.put("use", WorkerService.flag(argv, "--trajectory-use"));
        cfg.put("trajectory_review", trajectoryReview);
        return cfg;
    }
}
