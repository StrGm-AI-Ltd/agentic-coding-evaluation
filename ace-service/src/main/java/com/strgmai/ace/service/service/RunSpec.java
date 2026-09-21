package com.strgmai.ace.service.service;

import java.util.*;

/** Port of service/queue.py's RunSpec: the flags a queued run may carry, in argv order, with the
 *  same validation (known rung, positive budgets, bounded patterns). argv() rebuilds the CLI
 *  record stored on the job. */
public record RunSpec(String task, String model, String harness, String mode, String planSource, Integer taskWall, Integer taskTokens,
                      Integer implWall, Integer implTokens, String parallel, boolean systemRules, boolean selfReview,
                      boolean trajectoryReview, String reviewerModel, boolean handoffNotes, boolean manageDocker,
                      Integer contextWindow, String runId) {

    public static final String RUN_ID = "^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$";

    public RunSpec {
        taskWall = positive(taskWall); taskTokens = positive(taskTokens); implWall = positive(implWall); implTokens = positive(implTokens);
        contextWindow = positive(contextWindow);
        if (runId != null && !runId.matches(RUN_ID)) throw new IllegalArgumentException("invalid run id: " + runId);
        if (mode != null && !List.of("monolithic", "orchestrated").contains(mode))
            throw new IllegalArgumentException("mode must be monolithic or orchestrated");
        if (planSource != null && !List.of("agent", "reference").contains(planSource))
            throw new IllegalArgumentException("plan_source must be agent or reference");
    }

    private static Integer positive(Integer v) { return v == null || v > 0 ? v : failPositive(v); }
    private static Integer failPositive(Integer v) { throw new IllegalArgumentException("budgets must be positive: " + v); }

    public static final Set<String> TERMINAL = Set.of("succeeded", "failed", "cancelled");

    public List<String> argv(final String resolvedRunId) {
        final List<String> args = new ArrayList<>(List.of("--run-id=" + resolvedRunId, "--task=" + task));
        if (model != null) args.add("--model=" + model);
        if (harness != null) args.add("--harness=" + harness);
        if (mode != null) args.add("--mode=" + mode);
        if (planSource != null) args.add("--plan-source=" + planSource);
        if (taskWall != null) args.add("--task-wall=" + taskWall);
        if (taskTokens != null) args.add("--task-tokens=" + taskTokens);
        if (implWall != null) args.add("--impl-wall=" + implWall);
        if (implTokens != null) args.add("--impl-tokens=" + implTokens);
        if (parallel != null && !parallel.isBlank()) args.add("--parallel=" + parallel);
        if (systemRules) args.add("--system-rules");
        if (selfReview) args.add("--self-review");
        if (trajectoryReview) args.add("--trajectory-review");
        if (reviewerModel != null) args.add("--reviewer-model=" + reviewerModel);
        if (handoffNotes) args.add("--handoff-notes");
        if (manageDocker) args.add("--manage-docker");
        // a pinned window: the run uses it as-is and skips step 0 (the probe exists to MEASURE one)
        if (contextWindow != null) args.add("--context-window=" + contextWindow);
        return args;
    }

    public static String defaultRunId(final String prefix) {
        return prefix + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.now());
    }
}
