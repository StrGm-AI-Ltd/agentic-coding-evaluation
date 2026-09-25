package com.strgmai.ace.service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.strgmai.ace.service.config.JsonColumns;
import com.strgmai.ace.service.jooq.tables.records.JobsRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

import static com.strgmai.ace.service.jooq.Tables.JOBS;

/** Port of service/queue.py: the DB-backed run queue. Claim uses an atomic UPDATE...WHERE id=(SELECT
 *  ... LIMIT 1) with the same priority order (priority DESC, then FIFO); a run id can never be queued
 *  twice or collide with an existing results dir; cancel lets a RUNNING job finish via its cancel
 *  flag; requeue keeps failed/cancelled/blocked jobs re-runnable.
 *
 *  SQLite has no FOR UPDATE SKIP LOCKED (the Postgres original used it to keep concurrent workers
 *  from fighting over the same row): this service's worker runs on a single-threaded executor, so a
 *  plain atomic UPDATE picking one row is enough - SQLite serializes writers on its own, and there is
 *  never more than one claimer to skip past. */
@Repository
public class JobQueue {
    private static final Logger log = LoggerFactory.getLogger(JobQueue.class);
    private final DSLContext dsl;
    private final ObjectMapper json = new ObjectMapper();

    public JobQueue(DSLContext dsl) { this.dsl = dsl; }

    public record Job(UUID id, UUID experimentId, String arm, Integer repeat, String kind, String runId,
                      List<String> argv, String status, String blockedReason, int priority, Integer pid,
                      Integer exitCode, boolean cancelRequested, String stdoutPath, String resultLine,
                      String pinnedRunnerSha, String pinnedOracleSha) {}

    public Map<String, Object> enqueue(RunSpec spec, int priority, String resultsDir, String runnerSha, String oracleSha,
                                       UUID experimentId, String arm, Integer repeat) {
        final String runId = spec.runId() != null ? spec.runId() : RunSpec.defaultRunId("svc");
        if (java.nio.file.Path.of(resultsDir, runId).toFile().exists())
            throw new IllegalArgumentException("results/" + runId + " already exists; choose another run id");
        if (dsl.fetchCount(JOBS, JOBS.KIND.eq("run").and(JOBS.RUN_ID.eq(runId))) > 0)
            throw new IllegalArgumentException("run id " + runId + " was already queued (ids embed the model name and a timestamp; "
                    + "near-simultaneous experiments can collide - wait a moment or choose explicit run ids)");
        if (experimentId == null && dsl.fetchCount(JOBS, JOBS.RUN_ID.eq(runId)
                .and(JOBS.STATUS.notIn("succeeded", "failed", "cancelled"))) > 0)
            throw new IllegalArgumentException(runId + " already has a pending job");
        JobsRecord rec;
        try {
            rec = dsl.insertInto(JOBS)
                    .set(JOBS.ID, UUID.randomUUID())   // no AUTOINCREMENT on a UUID PK - assigned here
                    .set(JOBS.EXPERIMENT_ID, experimentId)
                    .set(JOBS.ARM, arm)
                    .set(JOBS.REPEAT, repeat)
                    .set(JOBS.KIND, "run")
                    .set(JOBS.RUN_ID, runId)
                    .set(JOBS.ARGV, toJson(spec.argv(runId)))
                    .set(JOBS.PRIORITY, priority)
                    .set(JOBS.PINNED_RUNNER_SHA, runnerSha)
                    .set(JOBS.PINNED_ORACLE_SHA, oracleSha)
                    .returning()
                    .fetchOne();
        } catch (org.jooq.exception.DataAccessException e) {
            // #87: the pre-checks above race with the DB's own uq_jobs_kind_run_id unique constraint
            // (the real backstop against a genuine double-insert) rather than being backed by it in
            // the exception-handling path - a request that LOSES that race used to surface as a raw,
            // unmapped 500 instead of the 409 Conflict its sibling methods (cancel(), requeue())
            // already return for the equivalent "state changed concurrently" case.
            throw new IllegalStateException("run id " + runId + " was enqueued concurrently by another request", e);
        }
        return JsonColumns.parse(rec.intoMap());
    }

    /** claim the next runnable job */
    public Job claim() {
        // FIFO tie-break is enqueued_at, not id: a UUID primary key carries no ordering of its own
        var candidate = dsl.select(JOBS.ID).from(JOBS)
                .where(JOBS.STATUS.in("queued", "waiting_lock"))
                .orderBy(JOBS.PRIORITY.desc(), JOBS.ENQUEUED_AT.asc())
                .limit(1);
        JobsRecord rec = dsl.update(JOBS)
                .set(JOBS.STATUS, "running")
                .setNull(JOBS.BLOCKED_REASON)
                .where(JOBS.ID.eq(candidate.asField()))
                .returning()
                .fetchOne();
        return rec == null ? null : toJob(rec);
    }

    public void setStatus(final UUID jobId, final String status, final String reason) {
        dsl.update(JOBS).set(JOBS.STATUS, status).set(JOBS.BLOCKED_REASON, reason)
                .setNull(JOBS.PID).setNull(JOBS.STARTED_AT).where(JOBS.ID.eq(jobId)).execute();
    }

    public void started(final UUID jobId, final int pid, final String stdoutPath) {
        dsl.update(JOBS).set(JOBS.PID, pid).set(JOBS.STDOUT_PATH, stdoutPath).set(JOBS.STARTED_AT, now())
                .where(JOBS.ID.eq(jobId)).execute();
    }

    public void finish(final UUID jobId, final String status, final Integer exitCode, final String resultLine) {
        dsl.update(JOBS).set(JOBS.STATUS, status).set(JOBS.EXIT_CODE, exitCode).set(JOBS.RESULT_LINE, resultLine)
                .set(JOBS.FINISHED_AT, now()).setNull(JOBS.PID).where(JOBS.ID.eq(jobId)).execute();
    }

    public void setPriority(final UUID jobId, final int priority) {
        if (dsl.update(JOBS).set(JOBS.PRIORITY, priority).where(JOBS.ID.eq(jobId)).execute() == 0)
            throw new NoSuchElementException("job " + jobId + " does not exist");
    }

    /** port of cancel: a RUNNING job gets the flag (the worker stops it); anything queued is cancelled outright */
    public Map<String, Object> cancel(final UUID jobId) {
        final Map<String, Object> job = get(jobId);
        if (RunSpec.TERMINAL.contains(job.get("status")))
            throw new IllegalStateException("job " + jobId + " is already " + job.get("status"));
        // status guard in the WHERE: a concurrent finish() between the get() above and this UPDATE
        // would otherwise let the CASE write 'cancelled' over a fresh terminal status
        int updated = dsl.update(JOBS)
                .set(JOBS.CANCEL_REQUESTED, true)
                .set(JOBS.STATUS, org.jooq.impl.DSL.when(JOBS.STATUS.eq("running"), JOBS.STATUS).otherwise("cancelled"))
                .set(JOBS.FINISHED_AT, org.jooq.impl.DSL.when(JOBS.STATUS.eq("running"), JOBS.FINISHED_AT).otherwise(now()))
                .where(JOBS.ID.eq(jobId).and(JOBS.STATUS.notIn("succeeded", "failed", "cancelled")))
                .execute();
        if (updated == 0)
            throw new IllegalStateException("job " + jobId + " transitioned to a terminal state concurrently");
        return get(jobId);
    }

    public Map<String, Object> requeue(final UUID jobId, final String resultsDir) {
        final Map<String, Object> job = get(jobId);
        if (!List.of("failed", "cancelled", "blocked").contains(job.get("status")))
            throw new IllegalStateException("job " + jobId + " is " + job.get("status") + "; only failed, cancelled or blocked jobs can be requeued");
        if ("run".equals(job.get("kind")) && java.nio.file.Path.of(resultsDir, String.valueOf(job.get("run_id"))).toFile().exists())
            throw new IllegalStateException("results/" + job.get("run_id") + " exists and a re-run would mix its files. Move it aside first");
        // status IN (...) in the WHERE makes the update atomic with the check above: a concurrent
        // cancel of a blocked job must not be silently un-done by the flip back to 'queued'
        int updated = dsl.update(JOBS)
                .set(JOBS.STATUS, "queued").set(JOBS.CANCEL_REQUESTED, false).setNull(JOBS.BLOCKED_REASON)
                .setNull(JOBS.PID).setNull(JOBS.EXIT_CODE).setNull(JOBS.RESULT_LINE)
                .setNull(JOBS.STARTED_AT).setNull(JOBS.FINISHED_AT).set(JOBS.ENQUEUED_AT, now())
                .where(JOBS.ID.eq(jobId).and(JOBS.STATUS.in("failed", "cancelled", "blocked")))
                .execute();
        if (updated == 0)
            throw new IllegalStateException("job " + jobId + " status changed concurrently; re-try the requeue");
        return get(jobId);
    }

    public Map<String, Object> get(final UUID jobId) {
        final JobsRecord rec = dsl.selectFrom(JOBS).where(JOBS.ID.eq(jobId)).fetchOne();
        if (rec == null) throw new org.springframework.dao.EmptyResultDataAccessException("job " + jobId, 1);
        return JsonColumns.parse(rec.intoMap());
    }

    public List<Map<String, Object>> list() {
        var order = org.jooq.impl.DSL.case_(JOBS.STATUS)
                .when("running", 0).when("waiting_lock", 1).when("queued", 2).when("blocked", 3).otherwise(4);
        // newest-first tie-break is enqueued_at, not id: a UUID primary key carries no ordering of its own
        return JsonColumns.parseAll(dsl.selectFrom(JOBS)
                .orderBy(order, JOBS.PRIORITY.desc(), JOBS.ENQUEUED_AT.desc())
                .limit(200)
                .fetch()
                .intoMaps());
    }

    Job toJob(JobsRecord r) {
        return new Job(r.getId(), r.getExperimentId(), r.getArm(), r.getRepeat(), r.getKind(), r.getRunId(),
                fromJson(r.getArgv()), r.getStatus(), r.getBlockedReason(),
                r.getPriority() == null ? 0 : r.getPriority(), r.getPid(), r.getExitCode(),
                Boolean.TRUE.equals(r.getCancelRequested()), r.getStdoutPath(), r.getResultLine(),
                r.getPinnedRunnerSha(), r.getPinnedOracleSha());
    }

    private static String now() { return Instant.now().toString(); }

    List<String> fromJson(String s) {
        try { return json.readValue(s, json.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception e) {
            // a job's argv silently becoming [] here means the worker would try to run it with no
            // arguments at all - a real correctness problem, not just a display glitch
            log.warn("could not parse stored argv '{}', treating as empty: {}", s, e.toString());
            return List.of();
        }
    }
    String toJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (Exception e) { log.warn("could not serialize argv {}, storing as empty: {}", o, e.toString()); return "[]"; }
    }
}
