package com.strgmai.ace.ui;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * JSON payload records mirroring agentbench_service's /api endpoints
 * (service/agentbench_service/api.py and migrations/001_init.sql).
 * Field names intentionally match the wire format (snake_case); unknown
 * JSON properties are ignored (Spring Boot's default Jackson setting).
 */
public final class Api {

    private Api() {
    }

    /** One run — shape of both GET /api/runs (list; checks absent) and GET /api/runs/{id}. */
    public record Run(
            String run_id,
            String results_dir,
            String job_id,
            String task,
            String mode,
            String model,
            String harness,
            Integer schema_version,
            boolean poolable,
            Double functional_score_pct,
            Integer functional_points_got,
            Integer functional_denominator,
            List<String> functional_ids,
            Double weighted_score_pct,
            Integer points_got,
            Integer denominator,
            Double partial_score_pct,
            Boolean valid,
            List<String> validity_reasons,
            Boolean contended,
            String key_hash,
            Double wall_sec,
            Long completion_tokens,
            String started,
            int earlier_attempts,
            JsonNode manifest,
            JsonNode oracle,
            JsonNode metrics,
            List<Check> checks) {
    }

    /** One oracle check result on a run. */
    public record Check(
            String check_id,
            String category,
            Double weight,
            String status,
            String detail,
            String description) {
    }

    /** GET /api/groups → {ranked: [...], indicative: [...]}. */
    public record GroupResponse(List<Group> ranked, List<Group> indicative) {
    }

    /** A leaderboard group: same task + model + key_hash, pooled by stats.py. */
    public record Group(
            String task,
            String model,
            String key_hash,
            String mode,
            List<String> run_ids,
            Summary summary,
            String printed,
            String refused) {
    }

    public record Summary(
            Integer k,
            Stats functional,
            Stats composite,
            Stats agent_result,
            java.util.Map<String, CheckStat> matrix) {
    }

    public record Stats(Double mean, List<Double> ci90, Integer n) {
    }

    public record CheckStat(Boolean pass_k, Double pass_rate) {
    }

    /** GET /api/tasks/details element - a ladder.json rung with every check resolved to its own
     *  category/weight/description from CheckId, not just a bare id. */
    public record RungDetail(
            String name,
            String description,
            Integer budget_sec,
            Integer denominator,
            List<RungCheck> checks) {
    }

    public record RungCheck(String check_id, String category, Integer weight, String description) {
    }

    /** POST /api/compare. */
    public record CompareRequest(
            List<String> a,
            List<String> b,
            String metric,
            boolean model_ab,
            boolean allow_partial,
            boolean include_invalid,
            boolean allow_budget_mismatch) {
    }

    /** Response of POST /api/compare — refused null means the comparison ran. */
    public record CompareResponse(JsonNode result, String printed, String refused) {
    }

    /** POST /api/runs/{a}/compare/{b}/match-tasks — reviewer_model null means the run's own local
     *  model (the same "omlx/model" default Judge/Reviews use). */
    public record MatchTasksRequest(String reviewer_model) {
    }

    /** One task-to-task pairing TaskMatcher judged - bId/bTitle null means no match found. */
    public record TaskMatch(String aId, String aTitle, String bId, String bTitle) {
    }

    public record MatchTasksResponse(List<TaskMatch> matches) {
    }

    /** POST /api/import — run-id lists, not counts (importer.import_all returns lists). */
    public record ImportResult(List<String> imported, List<String> skipped) {
    }

    /** GET /api/jobs/{id}/plan — the plan's own task list, in planned execution order. Works on a
     *  still-running job (reads a results-dir file directly; no import needed). goal is the task's
     *  real description (PlanParser's richest field); title is its heading text, often terser. */
    public record PlanResponse(List<PlanTask> tasks, String error) {
    }

    /** implemented/handoff_done come from the workspace's own R18 git tags (phase/{id},
     *  phase/{id}-handoff) - the resume-independent source of truth for "is this task actually
     *  done," since a task finished in a PRIOR attempt never produces a live session event in a
     *  fresh page view. reported_status is a different axis entirely: whether the task's session
     *  actually SUCCEEDED, not just that it ran to completion - "done"/"partial"/"blocked" (the
     *  agent's own self-report) or "unfinished" (the harness's own fallback, written only once even
     *  a dedicated wrap-up chance came back empty); null until the task's main session has ended.
     *  reported_detail is the free-text half of that same self-reported line - how it was verified,
     *  what's missing, why it's blocked, or the harness fallback's own fixed explanation.
     *  See JobDetailView.taskStatus(). stage marks one of the harness's own pipeline stages
     *  (definition/plan/parallel-plan/self-review) rather than a real plan task - the UI hides the
     *  Restart tab for these (the per-task restart endpoint assumes a "phase/" + id tag that
     *  doesn't match any of these stages' own, irregular tag names). */
    public record PlanTask(int order, String id, String title, String goal, List<String> deps,
                           boolean implemented, boolean handoff_done, String reported_status, String reported_detail,
                           int attempts, boolean rescued, boolean stage) {
    }

    /** GET /api/jobs/{id}/tasks/{taskId}/prompt — progressively enriched: text is always the task's
     *  static instruction at minimum; started=true means it's enriched with the real, dynamic part
     *  the harness computed right as that task's session began (packs/{id}.md). */
    public record TaskPrompt(boolean started, String text) {
    }

    /** GET /api/jobs/{id}/tasks/{taskId}/diff — the task's own net change. finished=true means
     *  before_sha/after_sha bound its own completed commit (or wave merge); finished=false means
     *  after_sha is empty and diff/new_files are the live, uncommitted working tree against
     *  before_sha (HEAD) instead — see BenchController.taskDiff(). */
    public record TaskDiff(boolean started, boolean finished, String before_sha, String after_sha,
                           String diff, boolean diff_truncated, List<NewFile> new_files,
                           boolean new_files_truncated, String error) {
    }

    /** One untracked file a still-running task created — git diff alone never shows these. */
    public record NewFile(String path, String content, boolean truncated) {
    }

    /** #270: GET /api/runs/{id}/files — one entry per file in the run's own results dir (top-level
     *  files plus one level of subdirs, workspace/ excluded). size is -1 when the file could not be
     *  stat'd (same "–" FilesBrowser's own sizeOf() already renders for that case). */
    public record RunFile(String path, long size) {
    }

    /** #263: GET /api/jobs/{id}/tasks/{taskId}/attempts — one entry per past run of this task
     *  (every run, not just a restarted/overridden one), newest first. params mirrors the
     *  restart-task endpoint's own override fields (task_wall_sec/task_tokens/max_turns), always
     *  present with whatever effective value was actually used, override or run default.
     *  reported_status/reported_detail are the same self-reported outcome/reason PlanTask already
     *  carries for the CURRENT attempt - here, for every attempt. */
    public record TaskAttempt(String task_id, String attempt_started, String attempt_ended,
                              Map<String, Object> params, boolean overridden, int rc, double seconds,
                              String reported_status, String reported_detail) {
    }

    /** #265: GET /api/jobs/{id}/tasks/{taskId}/judge — one entry per turn the mid-task judge fired
     *  for this task, ascending by turn. */
    public record JudgeCheckpoint(int turn, String prompt, String recommendation) {
    }

    /** One queue job — GET /api/jobs, /api/jobs/{id}, and the job-mutating endpoints. */
    public record Job(
            String id,
            String experiment_id,
            String arm,
            Integer repeat,
            String kind,
            String run_id,
            List<String> argv,
            String status,
            String blocked_reason,
            Integer priority,
            Integer pid,
            Integer exit_code,
            boolean cancel_requested,
            boolean pause_requested,
            String stdout_path,
            String result_line,
            String enqueued_at,
            String started_at,
            String finished_at,
            // #266: GET /api/jobs/{id}/.jobs() already returns this (BenchController runs every row
            // through JsonColumns.parse(), which lists "task_overrides" among the keys it parses as
            // nested JSON) - this DTO just never declared the field, so Jackson's "unknown JSON
            // properties are ignored" setting silently dropped it on the way into the UI. Without
            // this, buildRestartTab()/buildSessionRestartTab() can never show an operator what
            // override values (if any) are already active for a task from a previous restart.
            Map<String, Object> task_overrides) {
    }

    /** One experiment — GET /api/experiments and /api/experiments/{id} (detail adds jobs). */
    public record Experiment(
            String id,
            String name,
            String tag,
            String template,
            JsonNode params,
            Integer k,
            String pinned_runner_sha,
            String pinned_oracle_sha,
            String status,
            JsonNode comparison,
            String created_at,
            List<ExperimentJob> jobs) {
    }

    public record ExperimentJob(String id, String arm, Integer repeat, String run_id, String status, String result_line) {
    }

    /** GET /api/preflight — #108: synchronous (BenchController.preflight() runs every check inline
     *  and returns the final result in the same request; there is no separate "start" call and
     *  nothing to poll), matching the Spring backend's actual shape (the old {running, started_at,
     *  finished_at, results, error} shape was the pre-port Python/FastAPI service's async contract,
     *  which this backend never implemented). */
    public record PreflightState(List<PreflightCheck> checks, boolean blocked, String verdict) {
    }

    /** one Preflight.Check as BenchController.preflight() actually serializes it. */
    public record PreflightCheck(String check, boolean ok, String detail, boolean fatal) {
    }

    /** GET/POST/PUT /api/providers — the LLM Providers registry. `has_api_key` is deliberately the
     *  only signal the raw secret ever gives the client; BenchController never serializes the real
     *  value, and this record has no field for it either, so a round-tripped secret is structurally
     *  impossible, not just a convention someone could forget. */
    public record Provider(String id, String name, String provider_key, String base_url, String default_model, boolean has_api_key) {
    }
}
