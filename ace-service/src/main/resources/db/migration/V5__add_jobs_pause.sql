-- Pause: a gentler cancel. The job stops (same in-flight interrupt as cancel) but its results dir
-- and workspace are left exactly where they are and it lands on a resumable 'paused' status instead
-- of 'cancelled', so Requeue can pick it straight back up via R18's own git-tag resume mechanism -
-- no --force-aborted move, no lost progress. SQLite has no ALTER TABLE ... ADD CHECK, so widening the
-- status CHECK constraint to include 'paused' means rebuilding the table (recreate, copy, drop,
-- rename). No PRAGMA foreign_keys toggle needed for the swap: runs.job_id's ON DELETE CASCADE only
-- ever fires on a row-level DELETE against jobs, never on DROP TABLE, and the row ids are identical
-- in the rebuilt table - Flyway's SQLite executor also rejects mixing PRAGMA with transactional DDL
-- in one migration anyway (mixed=false).

CREATE TABLE jobs_new (
    id                 UUID PRIMARY KEY,
    experiment_id      UUID REFERENCES experiments (id) ON DELETE SET NULL,
    arm                TEXT,
    repeat             INTEGER,
    kind               TEXT NOT NULL CHECK (kind IN ('run', 'score_only')),
    run_id             TEXT NOT NULL,
    argv               TEXT NOT NULL,
    status             TEXT NOT NULL DEFAULT 'queued'
                       CHECK (status IN ('queued', 'waiting_lock', 'running', 'succeeded', 'failed', 'cancelled', 'blocked', 'paused')),
    blocked_reason     TEXT,
    priority           INTEGER NOT NULL DEFAULT 0,
    pinned_runner_sha  TEXT,
    pinned_oracle_sha  TEXT,
    pid                INTEGER,
    exit_code          INTEGER,
    cancel_requested   BOOLEAN NOT NULL DEFAULT false,
    pause_requested    BOOLEAN NOT NULL DEFAULT false,
    stdout_path        TEXT,
    result_line        TEXT,
    enqueued_at        TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at         TEXT,
    finished_at        TEXT,
    CONSTRAINT uq_jobs_kind_run_id UNIQUE (kind, run_id)
);

INSERT INTO jobs_new (id, experiment_id, arm, repeat, kind, run_id, argv, status, blocked_reason,
                       priority, pinned_runner_sha, pinned_oracle_sha, pid, exit_code,
                       cancel_requested, pause_requested, stdout_path, result_line,
                       enqueued_at, started_at, finished_at)
    SELECT id, experiment_id, arm, repeat, kind, run_id, argv, status, blocked_reason,
           priority, pinned_runner_sha, pinned_oracle_sha, pid, exit_code,
           cancel_requested, false, stdout_path, result_line,
           enqueued_at, started_at, finished_at
    FROM jobs;

DROP TABLE jobs;
ALTER TABLE jobs_new RENAME TO jobs;

CREATE INDEX idx_jobs_status_priority ON jobs (status, priority DESC, enqueued_at);
CREATE INDEX idx_jobs_experiment ON jobs (experiment_id, repeat, arm);
