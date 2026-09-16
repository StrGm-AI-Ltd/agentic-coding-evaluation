package com.agentbench.ui;

import tools.jackson.databind.JsonNode;

import java.util.List;

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
            Long job_id,
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

    /** POST /api/import. */
    public record ImportResult(int imported, int skipped) {
    }

    /** One queue job — GET /api/jobs, /api/jobs/{id}, and the job-mutating endpoints. */
    public record Job(
            Long id,
            Long experiment_id,
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
            String stdout_path,
            String result_line,
            String enqueued_at,
            String started_at,
            String finished_at) {
    }

    /** One experiment — GET /api/experiments and /api/experiments/{id} (detail adds jobs). */
    public record Experiment(
            Long id,
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

    public record ExperimentJob(Long id, String arm, Integer repeat, String run_id, String status, String result_line) {
    }

    /** GET/POST /api/preflight. */
    public record PreflightState(
            boolean running,
            String started_at,
            String finished_at,
            JsonNode results,
            String error) {
    }
}
