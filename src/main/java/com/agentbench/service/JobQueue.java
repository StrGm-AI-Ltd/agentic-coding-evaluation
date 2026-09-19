package com.agentbench.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Port of service/queue.py: the DB-backed run queue. Claim uses FOR UPDATE with the
 *  same priority order (priority DESC, then FIFO); a run id can never be queued twice or collide
 *  with an existing results dir; cancel lets a RUNNING job finish via its cancel flag; requeue
 *  keeps failed/cancelled/blocked jobs re-runnable. */
@Repository
public class JobQueue {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public JobQueue(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Job(Long id, Long experimentId, String arm, Integer repeat, String kind, String runId,
                      List<String> argv, String status, String blockedReason, int priority, Integer pid,
                      Integer exitCode, boolean cancelRequested, String stdoutPath, String resultLine,
                      String pinnedRunnerSha, String pinnedOracleSha) {}

    public Map<String, Object> enqueue(RunSpec spec, int priority, String resultsDir, String runnerSha, String oracleSha,
                                       Long experimentId, String arm, Integer repeat) {
        String runId = spec.runId() != null ? spec.runId() : RunSpec.defaultRunId("svc");
        if (java.nio.file.Path.of(resultsDir, runId).toFile().exists())
            throw new IllegalArgumentException("results/" + runId + " already exists; choose another run id");
        if (jdbc.queryForObject("SELECT count(*) FROM jobs WHERE kind = 'run' AND run_id = ?", Integer.class, runId) > 0)
            throw new IllegalArgumentException("run id " + runId + " was already queued (ids embed the model name and a timestamp; "
                    + "near-simultaneous experiments can collide - wait a moment or choose explicit run ids)");
        if (experimentId == null && jdbc.queryForObject("SELECT count(*) FROM jobs WHERE run_id = ? AND status <> ALL(?) ", Integer.class,
                runId, new String[]{"succeeded", "failed", "cancelled"}) > 0)
            throw new IllegalArgumentException(runId + " already has a pending job");
        Map<String, Object> job = jdbc.queryForMap(
                "INSERT INTO jobs (experiment_id, arm, repeat, kind, run_id, argv, priority, pinned_runner_sha, pinned_oracle_sha) "
                        + "VALUES (?,?,?,?,?,?::jsonb,?,?,?) RETURNING *",
                experimentId, arm, repeat, "run", runId, toJson(spec.argv(runId)), priority, runnerSha, oracleSha);
        return job;
    }

    /** claim the next runnable job; SKIP LOCKED keeps concurrent workers from fighting */
    public Job claim() {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query(conn -> conn.prepareStatement(
                "UPDATE jobs SET status = 'running', blocked_reason = NULL WHERE id = ("
                        + "SELECT id FROM jobs WHERE status IN ('queued', 'waiting_lock') "
                        + "ORDER BY priority DESC, id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING *",
                java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY), rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                        m.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
                    rows.add(m);
                });
        return rows.isEmpty() ? null : toJob(rows.get(0));
    }

    public void setStatus(long jobId, String status, String reason) {
        jdbc.update("UPDATE jobs SET status = ?, blocked_reason = ?, pid = NULL, started_at = NULL WHERE id = ?", status, reason, jobId);
    }

    public void started(long jobId, int pid, String stdoutPath) {
        jdbc.update("UPDATE jobs SET pid = ?, stdout_path = ?, started_at = now() WHERE id = ?", pid, stdoutPath, jobId);
    }

    public void finish(long jobId, String status, Integer exitCode, String resultLine) {
        jdbc.update("UPDATE jobs SET status = ?, exit_code = ?, result_line = ?, finished_at = now(), pid = NULL WHERE id = ?",
                status, exitCode, resultLine, jobId);
    }

    public void setPriority(long jobId, int priority) {
        if (jdbc.update("UPDATE jobs SET priority = ? WHERE id = ?", priority, jobId) == 0)
            throw new NoSuchElementException("job " + jobId + " does not exist");
    }

    /** port of cancel: a RUNNING job gets the flag (the worker stops it); anything queued is cancelled outright */
    public Map<String, Object> cancel(long jobId) {
        Map<String, Object> job = get(jobId);
        if (RunSpec.TERMINAL.contains(job.get("status")))
            throw new IllegalStateException("job " + jobId + " is already " + job.get("status"));
        // status guard in the WHERE: a concurrent finish() between the get() above and this UPDATE
        // would otherwise let the CASE write 'cancelled' over a fresh terminal status
        int updated = jdbc.update("UPDATE jobs SET cancel_requested = true, "
                + "status = CASE WHEN status = 'running' THEN status ELSE 'cancelled' END, "
                + "finished_at = CASE WHEN status = 'running' THEN finished_at ELSE now() END "
                + "WHERE id = ? AND status NOT IN ('succeeded','failed','cancelled')", jobId);
        if (updated == 0)
            throw new IllegalStateException("job " + jobId + " transitioned to a terminal state concurrently");
        return get(jobId);
    }

    public Map<String, Object> requeue(long jobId, String resultsDir) {
        Map<String, Object> job = get(jobId);
        if (!List.of("failed", "cancelled", "blocked").contains(job.get("status")))
            throw new IllegalStateException("job " + jobId + " is " + job.get("status") + "; only failed, cancelled or blocked jobs can be requeued");
        if ("run".equals(job.get("kind")) && java.nio.file.Path.of(resultsDir, String.valueOf(job.get("run_id"))).toFile().exists())
            throw new IllegalStateException("results/" + job.get("run_id") + " exists and a re-run would mix its files. Move it aside first");
        jdbc.update("UPDATE jobs SET status = 'queued', cancel_requested = false, blocked_reason = NULL, pid = NULL, "
                + "exit_code = NULL, result_line = NULL, started_at = NULL, finished_at = NULL, enqueued_at = now() WHERE id = ?", jobId);
        return get(jobId);
    }

    public Map<String, Object> get(long jobId) {
        return jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", jobId);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT * FROM jobs ORDER BY CASE status WHEN 'running' THEN 0 WHEN 'waiting_lock' THEN 1 "
                + "WHEN 'queued' THEN 2 WHEN 'blocked' THEN 3 ELSE 4 END, priority DESC, id DESC LIMIT 200");
    }

    Job toJob(Map<String, Object> m) {
        return new Job(((Number) m.get("id")).longValue(), (Long) m.get("experiment_id"), (String) m.get("arm"),
                (Integer) m.get("repeat"), (String) m.get("kind"), (String) m.get("run_id"),
                fromJson(String.valueOf(m.get("argv"))), (String) m.get("status"), (String) m.get("blocked_reason"),
                m.get("priority") instanceof Number n ? n.intValue() : 0, (Integer) m.get("pid"), (Integer) m.get("exit_code"),
                Boolean.TRUE.equals(m.get("cancel_requested")), (String) m.get("stdout_path"), (String) m.get("result_line"),
                (String) m.get("pinned_runner_sha"), (String) m.get("pinned_oracle_sha"));
    }

    List<String> fromJson(String s) {
        try { return json.readValue(s, json.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception e) { return List.of(); }
    }
    String toJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return "[]"; }
    }
}
