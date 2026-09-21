-- SQLite port of the former schema.sql (Postgres). Flyway-versioned: this runs exactly once per
-- database, so none of the old idempotent-heal patterns (CREATE ... IF NOT EXISTS, drop-and-re-add
-- constraints) are needed - constraints are declared inline on first creation.
CREATE TABLE experiments (
    id                 UUID PRIMARY KEY,   -- assigned in Java (SQLite has no UUID generator)
    name               TEXT NOT NULL,
    tag                TEXT NOT NULL,
    template           TEXT NOT NULL CHECK (template IN ('harness_effect', 'model_ab', 'agent_ab', 'custom')),
    params             TEXT NOT NULL DEFAULT '{}',
    k                  INTEGER NOT NULL CHECK (k >= 1),
    pinned_runner_sha  TEXT,
    pinned_oracle_sha  TEXT,
    status             TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'finished', 'cancelled')),
    comparison         TEXT,
    created_at         TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE jobs (
    id                 UUID PRIMARY KEY,   -- assigned in Java (SQLite has no UUID generator)
    experiment_id      UUID REFERENCES experiments (id) ON DELETE SET NULL,   -- nullable: unpin, don't fail, on experiment deletion
    arm                TEXT,
    repeat             INTEGER,
    kind               TEXT NOT NULL CHECK (kind IN ('run', 'score_only')),
    run_id             TEXT NOT NULL,
    argv               TEXT NOT NULL,
    status             TEXT NOT NULL DEFAULT 'queued'
                       CHECK (status IN ('queued', 'waiting_lock', 'running', 'succeeded', 'failed', 'cancelled', 'blocked')),
    blocked_reason     TEXT,
    priority           INTEGER NOT NULL DEFAULT 0,
    pinned_runner_sha  TEXT,
    pinned_oracle_sha  TEXT,
    pid                INTEGER,
    exit_code          INTEGER,
    cancel_requested   BOOLEAN NOT NULL DEFAULT false,
    stdout_path        TEXT,
    result_line        TEXT,
    enqueued_at        TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at         TEXT,
    finished_at        TEXT,
    -- DB-level uniqueness: JobQueue.enqueue previously guarded with SELECT count(*) - a TOCTOU race
    -- (two concurrent enqueues with the same run_id both passed and inserted duplicates).
    CONSTRAINT uq_jobs_kind_run_id UNIQUE (kind, run_id)
);

-- Hot claim() path: SELECT ... WHERE status IN ('queued','waiting_lock') ORDER BY priority DESC,
-- enqueued_at ... LIMIT 1. Without this every worker claim full-scans the table, so as completed/
-- cancelled jobs accumulate the claim degrades to O(n). enqueued_at, not id, is the FIFO tie-break -
-- a UUID primary key carries no ordering of its own.
CREATE INDEX idx_jobs_status_priority ON jobs (status, priority DESC, enqueued_at);

-- Experiment detail view: SELECT ... FROM jobs WHERE experiment_id = ? ORDER BY repeat, arm.
CREATE INDEX idx_jobs_experiment ON jobs (experiment_id, repeat, arm);

CREATE TABLE runs (
    run_id                  TEXT PRIMARY KEY,
    results_dir             TEXT NOT NULL,
    job_id                  UUID REFERENCES jobs (id) ON DELETE CASCADE,   -- a run dies with its job: cleanup must not need manual orphaning
    task                    TEXT,
    mode                    TEXT,
    model                   TEXT,
    harness                 TEXT,
    schema_version          INTEGER,
    poolable                BOOLEAN NOT NULL,
    functional_score_pct    REAL,
    functional_points_got   INTEGER,
    functional_denominator  INTEGER,
    weighted_score_pct      REAL,
    points_got              INTEGER,
    denominator             INTEGER,
    partial_score_pct       REAL,
    valid                   BOOLEAN,
    validity_reasons        TEXT NOT NULL DEFAULT '[]',
    contended               BOOLEAN,
    key_hash                TEXT,
    wall_sec                REAL,
    completion_tokens       INTEGER,
    manifest                TEXT NOT NULL DEFAULT '{}',
    oracle                  TEXT NOT NULL,
    metrics                 TEXT NOT NULL DEFAULT '{}',
    imported_at             TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE check_results (
    run_id     TEXT NOT NULL REFERENCES runs (run_id) ON DELETE CASCADE,   -- check rows die with their run (importer's DELETE-by-run becomes optional)
    check_id   TEXT NOT NULL,
    category   TEXT NOT NULL,
    weight     INTEGER NOT NULL,
    -- the CheckStatus enum (PASS/FAIL/NOT_ATTEMPTED/SKIPPED/INFRA); upper() accepts the lowercase
    -- values legacy data (and the tests) carry, like CheckStatus.parse does
    status     TEXT NOT NULL CHECK (upper(status) IN ('PASS', 'FAIL', 'NOT_ATTEMPTED', 'SKIPPED', 'INFRA')),
    detail     TEXT,
    -- the importer does DELETE-by-run then re-insert; the composite unique makes re-import idempotent
    -- by construction and rules out silent duplicates if that pattern is ever split
    CONSTRAINT uq_check_results_run_check UNIQUE (run_id, check_id)
);

-- every lookup on check_results filters by run_id
CREATE INDEX idx_check_results_run ON check_results (run_id);
