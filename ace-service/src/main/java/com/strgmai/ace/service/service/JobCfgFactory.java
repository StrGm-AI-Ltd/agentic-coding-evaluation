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
        if (WorkerService.flag(argv, "--task-wall") != null) cfg.put("task_wall_sec", Integer.parseInt(WorkerService.flag(argv, "--task-wall")));
        if (WorkerService.flag(argv, "--task-tokens") != null) cfg.put("task_tokens", Long.parseLong(WorkerService.flag(argv, "--task-tokens")));
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
        // Map.of() rejects a null value outright - "model" IS null whenever review is enabled
        // without an explicit --reviewer-model (self-review alone still needs a reviewer picked
        // downstream, but that is RunBench's decision to make, not a reason to crash the worker)
        final Map<String, Object> review = new LinkedHashMap<>();
        review.put("enabled", argv.contains("--self-review"));
        review.put("model", WorkerService.flag(argv, "--reviewer-model"));
        review.put("blind", argv.contains("--review-blind"));
        // shared by both self-review and trajectory-review (Reviews.runReviewerSession reads
        // this same "review" map's wall_sec for either kind of reviewer session)
        if (WorkerService.flag(argv, "--review-wall-sec") != null) review.put("wall_sec", Integer.parseInt(WorkerService.flag(argv, "--review-wall-sec")));
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
