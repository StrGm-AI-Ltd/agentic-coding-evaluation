package com.strgmai.ace.ui;

/**
 * Centralized hover-tooltip text for the job/experiment/compare form fields. One param, one
 * description, reused on every form the same param appears on (JobNewView, ExperimentNewView,
 * CompareView) so its meaning never drifts between them. Grounded in RunSpec's own validation,
 * RunBench/Collect/Reviews' consuming logic, and the UI's own placeholder/helper text for exact
 * default values - a tooltip explains what the field IS and what it's for; the placeholder already
 * shows the default.
 */
final class Tooltips {
    private Tooltips() {}

    static final String TASK = "Which rung to attempt, from tasks/ladder.json (L1_migration_entity … "
            + "L7_full_platform, plus L3p_point_in_time: L3's own feature, but plan-first). Fixes the task "
            + "prompt, which checks are scored and their combined weight (the denominator), and the "
            + "wall-clock budget. Type to filter a known rung, or enter a custom value - an unrecognized "
            + "one has no ladder entry, so no budget/checks panel shows and nothing scores against it.";

    static final String RUN_ID = "Id for this run's own results directory (results/<id>/) and workspace. "
            + "Pattern: starts with a letter or digit, then up to 99 more letters/digits/'.'/'_'/'-'. "
            + "Blank = auto-generated from the model name and a timestamp. An id that's already queued, or "
            + "that already has a results directory, is rejected.";

    static final String HARNESS = "Which agent harness executes the task. 'ref' = ReferenceAgent, the "
            + "harness this project implements end-to-end. 'pi' = the original Pi harness. "
            + "Blank = the operator-wide default.";

    static final String MODE = "'monolithic': one growing agent session handles planning and "
            + "implementation together, with structural compaction as context fills. 'orchestrated': the "
            + "plan is split into independent tasks that can run in concurrent waves, each its own "
            + "session. Blank = monolithic.";

    static final String PLAN_SOURCE = "Where the implementation plan comes from (orchestrated mode). "
            + "'agent': the agent writes docs/IMPLEMENTATION_PLAN.md itself as phase p1_plan. "
            + "'reference': a pre-written plan is used instead and the planning session is skipped "
            + "entirely (only L3p_point_in_time ships one; other rungs fall back to the raw task prompt). "
            + "Blank = agent.";

    static final String PHASES = "Restricts which of a rung's own multi-phase sequence actually run: "
            + "p0_definition, p1_plan, p2_implementation. Only L7_full_platform (all three) and "
            + "L3p_point_in_time (p1_plan + p2_implementation) have more than one phase - selecting "
            + "anything here has no effect on every other rung. Typical use: resume past an "
            + "already-completed phase, or isolate one phase for debugging. Blank/none selected = every "
            + "phase the rung defines.";

    static final String PARALLEL = "Orchestrated mode: how many tasks run per concurrent wave. 'auto' "
            + "lets the agent's own parallelisation plan decide; a number from 1 to 99 pins a fixed wave "
            + "size (1 = sequential). Blank = sequential.";

    static final String PARALLEL_PLAN = "Toggles the parallelisation-planning step: before "
            + "implementation, the agent proposes how to group its own tasks into concurrent waves, which "
            + "the harness scores for validity, parallelism captured, and fix-up friction. 'off' skips it, "
            + "falling back to simple dependency-order waves. Blank = on, whenever there's more than one task.";

    static final String PARALLEL_WEIGHT = "Weight (0 to 1) of the parallelisation term inside "
            + "agent_result_pct. Blank = 0.1, and that default applies automatically whenever the "
            + "parallelisation step actually ran - this field only matters if you want a different weight.";

    static final String EFFICIENCY_WEIGHT = "Weight (0 to 1) of the budget-efficiency term inside "
            + "agent_result_pct: how much of the rung's own wall-clock budget was left unused (100 = "
            + "finished instantly, 0 = at or over budget). Unlike the other weighted terms, this is "
            + "opt-in only - it defaults to 0, so leaving it blank changes nothing about the score.";

    static final String TASK_WALL = "Orchestrated mode: wall-clock seconds given to each individual task "
            + "(and to the INTEGRATION step). Blank = derived from the rung's own time budget via the "
            + "step-0 context probe's measured window.";

    static final String TASK_TOKENS = "Orchestrated mode: completion-token budget per task. "
            + "Blank = derived the same way as task_wall.";

    static final String IMPL_WALL = "Monolithic mode: wall-clock seconds for the single implementation "
            + "session. An explicit value here always wins over the rung's own derived budget - used by "
            + "harness-effect experiments to match a monolithic arm's one session against an orchestrated "
            + "arm's N tasks × per-task budget.";

    static final String IMPL_TOKENS = "The completion-token counterpart to impl_wall - an explicit cap "
            + "for the implementation session, overriding the rung's derived default.";

    static final String PLAN_WALL = "Wall-clock seconds for the p0_definition/p1_plan sessions, in "
            + "either mode. Blank = the operator-wide per-phase default if one is configured, else 900 "
            + "seconds. 0 means unlimited.";

    static final String PLAN_TOKENS = "Completion-token budget for the p0_definition/p1_plan sessions. "
            + "Blank = the operator-wide per-phase token default.";

    static final String WALL_BUDGET = "Forces every phase's wall-clock budget to this one value at "
            + "once, no matter what the rung or any other wall setting would normally give it - a "
            + "genuine experimental setting, just one that overrides several budgets in a single move "
            + "instead of one at a time. Blank = each phase keeps its own budget.";

    static final String CONTEXT_WINDOW = "Pins the model's context window to this exact token count and "
            + "skips the step-0 probe (whose only job is to measure one). Leave blank to let the probe "
            + "measure it - the normal case.";

    static final String FIRST_TOKEN_TIMEOUT = "Seconds to wait for the first streamed token of a "
            + "response before treating the request as stalled. Blank = 180 seconds.";

    static final String COMPACTION_TRIGGER = "Prompt-token count above which the agent compacts context "
            + "(stubs out its oldest tool results) rather than risk running out of room. Blank = 28000. "
            + "0 disables compaction entirely.";

    static final String REVIEW_WALL_SEC = "Wall-clock seconds given to the self-review session (only "
            + "relevant when self_review is checked). Blank = 900 seconds.";

    static final String REVIEW_TOKENS = "Completion-token budget for the self-review session (only "
            + "relevant when self_review is checked). Blank = unlimited.";

    static final String MAX_TURNS = "Hard cap on the number of agent-tool-call turns within a single "
            + "session before the harness force-stops it. Must be >= 1. Blank = 400.";

    static final String FIX_WALL = "Wall-clock seconds for the FIX step - a repair session the harness "
            + "runs when a parallel wave's own merge leaves conflicts the agent needs to resolve. "
            + "Blank = unlimited.";

    static final String FIX_TOKENS = "Completion-token budget for the FIX step. Blank = unlimited.";

    static final String PARALLEL_PLAN_TOKENS = "Completion-token budget for the parallelisation-planning "
            + "session (see parallel_plan_wall for its wall-clock budget). Blank = unlimited.";

    static final String HANDOFF_TOKENS = "Completion-token budget for each hand-off-notes session (see "
            + "handoff_wall for its wall-clock budget). Blank = unlimited.";

    static final String WRAPUP_TOKENS = "Completion-token budget for a session's wrap-up pass (see "
            + "wrapup_wall for its wall-clock budget). Blank = unlimited.";

    static final String DOCKER_MEMORY_MIB = "Caps Docker Desktop's VM memory (MiB) so it competes less "
            + "with a co-resident model server for RAM - only applies when manage_docker is checked. "
            + "Blank = 4096 MiB.";

    static final String DOCKER_KEEP_WARM = "When checked, Docker Desktop stays running for the whole run "
            + "instead of being stopped/restarted by the idle monitor mid-run - only relevant when "
            + "manage_docker is checked.";

    static final String PRIORITY = "Queue priority for this job - higher values claim a worker before "
            + "lower ones; ties break by enqueue order. 0 is the default for an ordinary run.";

    static final String REVIEW_WEIGHT = "Weight (0 to 1) of the self-review term inside agent_result_pct "
            + "- how closely the reviewer's own score agrees with (or, in 'direct' mode, simply is) the "
            + "run's actual correctness. Blank = 0.1, applied automatically even when self_review never "
            + "ran (a review that never happened scores 0, still at this weight).";

    static final String TRAJECTORY_WEIGHT = "Weight (0 to 1) of the trajectory-review term inside "
            + "agent_result_pct - see trajectory_use for what's being measured. Blank = 0.1, applied the "
            + "same way as review_weight even when trajectory_review never ran.";

    static final String TEMPERATURE = "Sampling temperature sent to the model. Must be >= 0. "
            + "Blank = the operator-wide default.";

    static final String TOP_P = "Nucleus-sampling top-p sent to the model, within (0, 1]. "
            + "Blank = the operator-wide default.";

    static final String TOP_K = "Top-k sampling sent to the model; must be >= 1. "
            + "Blank = the model's own default.";

    static final String REPETITION_PENALTY = "Repetition-penalty sampling parameter sent to the model; "
            + "must be >= 0. Blank = the model's own default.";

    static final String MAX_TOKENS = "Hard cap on completion tokens per model request, independent of "
            + "any phase/task token budget. Must be >= 1. Blank = derived from the step-0 context probe.";

    static final String REASONING_EFFORT = "Reasoning-effort hint sent to the model for every phase. "
            + "'none' explicitly disables thinking; 'low'/'medium'/'high' request progressively more. "
            + "Blank = the operator's own per-phase default.";

    static final String PARALLEL_PLAN_WALL = "Wall-clock seconds for the parallelisation-planning "
            + "session itself, before any task implementation starts. Blank = 600 seconds.";

    static final String HANDOFF_WALL = "Wall-clock seconds for each sequential task's hand-off-notes "
            + "session (only runs when handoff_notes is checked) - a short briefing for the next task. "
            + "Blank = 300 seconds.";

    static final String WRAPUP_WALL = "Wall-clock seconds for a session's wrap-up pass (triggered when a "
            + "session hits rc=124 or ends with no artifact) to let the agent record what it did before "
            + "the hard cutoff. Blank = 300 seconds, scaled up at slow decode speeds.";

    static final String HANDOFF_NOTES = "When checked, each sequential orchestrated task gets a short "
            + "extra session afterward to write a hand-off note briefing the next task. Adds one session "
            + "(the handoff_wall budget) per task boundary.";

    static final String SYSTEM_RULES = "When checked, injects the same working-rules/hygiene text "
            + "orchestrated-mode agents normally get into a monolithic session too - the "
            + "'monolithic+rules' ablation arm, for isolating whether the rules themselves (not the "
            + "orchestration) explain a result difference.";

    static final String SELF_REVIEW = "When checked, runs an extra reviewer session after "
            + "implementation that scores the agent's own work; its score (and its agreement with the "
            + "actual functional score) feeds agent_result_pct at review_weight.";

    static final String REVIEW_BLIND = "When checked, hides the agent's own PROGRESS.md claims from the "
            + "self-review session, so the reviewer scores only what it can independently observe, not "
            + "what the agent says it did.";

    static final String TRAJECTORY_REVIEW = "When checked, runs a reviewer session that scores the run's "
            + "whole trajectory, not just the final diff; its score feeds agent_result_pct at "
            + "trajectory_weight, per trajectory_use.";

    static final String TRAJECTORY_USE = "How the trajectory-reviewer's score is used. 'calibration' "
            + "(default): rewarded for agreeing with the harness's own objective trajectory index, not "
            + "for the raw score. 'direct': the reviewer's own score IS the blended term. "
            + "Blank = calibration.";

    static final String NO_CONTEXT_PROBE = "Skips the step-0 context-window probe entirely (it normally "
            + "runs by default for a direct run). Has no effect if context_window is already pinned, "
            + "since a pinned window skips the probe anyway.";

    static final String CONTEXT_PROBE_FRESH = "Forces a fresh step-0 context-probe measurement instead "
            + "of reusing a cached one from an earlier run against the same setup.";

    static final String KEEP_WORKSPACE = "When checked, the run's workspace and HOME directory are NOT "
            + "deleted after the run finishes - normally they're deleted once the git-bundle snapshot in "
            + "results/<run_id>/workspace.bundle is confirmed written, since that bundle is the durable "
            + "record. Useful for inspecting the live workspace afterward, at the cost of disk space.";

    static final String MANAGE_DOCKER = "When checked, the harness starts Docker Desktop itself before "
            + "the run needs it (capped at a configured memory limit) and quits it afterward, rather than "
            + "assuming it's already running.";

    static final String SKIP_DOCKER = "Skips Docker entirely for the oracle's scoring phase: no "
            + "provenance probing, and manage_docker's own start/stop around scoring is suppressed. The "
            + "oracle already auto-detects when Docker is unreachable and gracefully skips Docker-gated "
            + "checks either way - this just avoids the wasted start/stop cycle when you already know you "
            + "don't want it.";

    static final String JAVA_HOME = "Pins which JDK the workspace's build/test commands run under (sets "
            + "JAVA_HOME and prepends its bin/ to PATH for the agent's spawned processes). Blank = the "
            + "operator-wide default if configured, else whatever `java_home -v <major>` resolves on this "
            + "machine.";

    static final String MODEL = "Which model serves this run. Type to pick from models seen in past "
            + "runs, or enter a custom provider/model string.";

    static final String REVIEWER_MODEL = "Which model judges the self-review session (only used when "
            + "self_review is checked). Format: provider/model - e.g. openrouter/…, gpt-5, claude-opus-5. "
            + "Blank = the same model that ran the task.";

    static final String TRAJECTORY_REVIEWER_MODEL = "Which model judges the trajectory-review session "
            + "(only used when trajectory_review is checked). Format: provider/model. "
            + "Blank = the same model that ran the task.";

    // ---- experiment-only fields (ExperimentNewView) ----

    static final String EXPERIMENT_NAME = "Label for this experiment, shown in the experiments list and "
            + "its own detail page. Blank = the template name plus a timestamp.";

    static final String TEMPLATE = "Which comparison this experiment runs. 'harness_effect': one model "
            + "across multiple harness arms (orchestrated vs monolithic, and optionally parallel / "
            + "prompt-only-rules variants) - isolates what the HARNESS contributes. 'model_ab': two "
            + "models (A vs B), same harness and budgets - isolates what the MODEL contributes. "
            + "'agent_ab': the reference agent vs Pi, same model and budgets - isolates what the AGENT "
            + "implementation contributes.";

    static final String K = "How many times each arm repeats, to average out run-to-run noise before "
            + "comparing arms. Each repeat gets its own queued job.";

    static final String EXPERIMENT_TASK_WALL = "Orchestrated-mode arms: wall-clock seconds per task, "
            + "directly. Monolithic arms: multiplied by the number of tasks the reference plan has, to "
            + "give the single implementation session a MATCHED total budget - so a harness_effect "
            + "comparison tests the harness, not an accidental budget difference between arms.";

    static final String EXPERIMENT_TASK_TOKENS = "The token-budget counterpart to task_wall, with the "
            + "same per-task-then-matched-to-monolithic treatment. Enter 'auto' (or leave blank) to "
            + "derive it from the step-0 context probe, or a specific integer to pin it.";

    static final String MODEL_A = "The first model in a model_ab comparison (arm A). Type to pick from "
            + "models seen in past runs or the live model server, or enter a custom id.";

    static final String MODEL_B = "The second model in a model_ab comparison (arm B), compared against "
            + "model_a under the same harness and budgets.";

    static final String ARM_ORCH = "Include the orchestrated-harness arm in this harness_effect "
            + "experiment: the plan is split into independent tasks run in waves.";

    static final String ARM_MONO = "Include the monolithic-harness arm: one growing session handles "
            + "planning and implementation together, with a budget matched to the orchestrated arm's "
            + "total (see task_wall).";

    static final String ARM_MONO_RULES = "Include a 'mono+rules' arm: monolithic, but with the same "
            + "working-rules text orchestrated agents get (see system_rules) - isolates whether the "
            + "rules themselves, not the orchestration, explain a result difference.";

    static final String ARM_PAR = "Include a 'par' arm: orchestrated with a fixed parallel wave size "
            + "(see the parallel field below) instead of the agent's own parallelisation plan.";

    static final String EXPERIMENT_PARALLEL = "Fixed number of tasks per concurrent wave for the 'par' "
            + "arm specifically (2 to 20). Only used when that arm is included above.";

    static final String AGENT_MODE = "Harness mode both agent_ab arms run under - orchestrated or "
            + "monolithic. Both arms always use the SAME mode; only the agent implementation (ref vs pi) "
            + "differs between them.";

    static final String AGENT_A = "The first agent implementation in an agent_ab comparison (arm A) - "
            + "'ref' (ReferenceAgent) or 'pi' (the original Pi harness).";

    static final String AGENT_B = "The second agent implementation in an agent_ab comparison (arm B), "
            + "compared against agent_a under the same model/mode/budgets.";

    // ---- compare-only fields (CompareView) ----

    static final String COMPARE_GROUP_A = "Run ids forming group A (only poolable runs - stats.py's own "
            + "pooling rule - are listed). Pick one or more; each run contributes one data point.";

    static final String COMPARE_GROUP_B = "Run ids forming group B, compared against group A on the "
            + "chosen metric.";

    static final String COMPARE_METRIC = "Which score to compare between the two groups. 'functional': "
            + "functional_score_pct, the primary correctness number. 'score': weighted_score_pct, the "
            + "full composite across every check category. 'agent_result': agent_result_pct, the "
            + "composite further blended with whichever calibration/efficiency terms were weighted in.";

    static final String COMPARE_MODEL_AB = "Tell the comparison the two groups are different MODELS, "
            + "not different configs of the same model/harness. Switches from requiring matched budgets "
            + "(the harness-effect assumption) to a harness-identity check instead - skips budget "
            + "matching, but still warns if the two groups don't share the same harness/config.";

    static final String COMPARE_ALLOW_PARTIAL = "Include runs whose score is null (Docker was skipped, "
            + "or infrastructure failed) in the pool. Normally excluded, since a partial run has nothing "
            + "to compare.";

    static final String COMPARE_INCLUDE_INVALID = "Include runs Validity.java marked invalid (a crash, "
            + "a dead proxy, upstream errors - see the run's own validity.reasons) in the pool. Normally "
            + "excluded, since an invalid run is a harness/infra failure, not a real result.";

    static final String COMPARE_ALLOW_BUDGET_MISMATCH = "Proceed even when the two groups' wall/token "
            + "budgets don't match (normally refused outright for a harness-effect-style comparison, "
            + "since a budget difference confounds whatever you're trying to isolate). Has no effect "
            + "when model_ab is checked, which skips the budget-match requirement entirely.";

    // ---- runs-list filters (RunsView) ----

    static final String FILTER_TASK = "Filter the list to runs of this exact rung. Blank = any.";

    static final String FILTER_MODEL = "Filter the list to runs of this exact model. Blank = any.";

    static final String FILTER_MODE = "Filter the list to runs in this harness mode (monolithic or "
            + "orchestrated). Blank = any.";

    static final String FILTER_VALID = "Filter by Validity.java's own verdict: 'true' shows only runs "
            + "with no harness/infra failure recorded in validity.reasons; 'false' shows only the ones "
            + "that do. Blank = any.";

    static final String FILTER_POOLABLE = "Filter by whether a run is eligible for statistical pooling "
            + "(StatsService/CompareView) - 'true' shows only runs that qualify; 'false' shows only the "
            + "ones that don't. Blank = any.";
}
