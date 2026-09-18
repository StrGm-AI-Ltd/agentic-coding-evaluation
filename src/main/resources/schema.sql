-- Port of service/migrations/001_init.sql. Idempotent: an importer re-run must upsert, never fail.
CREATE TABLE IF NOT EXISTS experiments (
    id                 bigserial PRIMARY KEY,
    name               text NOT NULL,
    tag                text NOT NULL,
    template           text NOT NULL CHECK (template IN ('harness_effect', 'model_ab', 'agent_ab', 'custom')),
    params             jsonb NOT NULL DEFAULT '{}',
    k                  integer NOT NULL CHECK (k >= 1),
    pinned_runner_sha  text,
    pinned_oracle_sha  text,
    status             text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'finished', 'cancelled')),
    comparison         jsonb,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS jobs (
    id                 bigserial PRIMARY KEY,
    experiment_id      bigint REFERENCES experiments (id),
    arm                text,
    repeat             integer,
    kind               text NOT NULL CHECK (kind IN ('run', 'score_only')),
    run_id             text NOT NULL,
    argv               jsonb NOT NULL,
    status             text NOT NULL DEFAULT 'queued'
                       CHECK (status IN ('queued', 'waiting_lock', 'running', 'succeeded', 'failed', 'cancelled', 'blocked')),
    blocked_reason     text,
    priority           integer NOT NULL DEFAULT 0,
    pinned_runner_sha  text,
    pinned_oracle_sha  text,
    pid                integer,
    exit_code          integer,
    cancel_requested   boolean NOT NULL DEFAULT false,
    stdout_path        text,
    result_line        text,
    enqueued_at        timestamptz NOT NULL DEFAULT now(),
    started_at         timestamptz,
    finished_at        timestamptz
);

-- Hot claim() path: SELECT ... WHERE status IN ('queued','waiting_lock') ORDER BY priority DESC, id
-- ... LIMIT 1 FOR UPDATE SKIP LOCKED. Without this every worker claim full-scans the table, so as
-- completed/cancelled jobs accumulate the claim degrades to O(n).
CREATE INDEX IF NOT EXISTS idx_jobs_status_priority ON jobs (status, priority DESC, id);

-- Experiment detail view: SELECT ... FROM jobs WHERE experiment_id = ? ORDER BY repeat, arm.
CREATE INDEX IF NOT EXISTS idx_jobs_experiment ON jobs (experiment_id, repeat, arm);


CREATE TABLE IF NOT EXISTS runs (
    run_id                text PRIMARY KEY,
    results_dir           text NOT NULL,
    job_id                bigint REFERENCES jobs (id),
    task                  text,
    mode                  text,
    model                 text,
    harness               text,
    schema_version        integer,
    poolable              boolean NOT NULL,
    functional_score_pct  double precision,
    functional_points_got integer,
    functional_denominator integer,
    weighted_score_pct    double precision,
    points_got            integer,
    denominator           integer,
    partial_score_pct     double precision,
    valid                 boolean,
    validity_reasons      jsonb NOT NULL DEFAULT '[]',
    contended             boolean,
    key_hash              text,
    wall_sec              double precision,
    completion_tokens     bigint,
    manifest              jsonb NOT NULL DEFAULT '{}',
    oracle                jsonb NOT NULL,
    metrics               jsonb NOT NULL DEFAULT '{}',
    imported_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS check_results (
    run_id     text NOT NULL REFERENCES runs (run_id),
    check_id   text NOT NULL,
    category   text NOT NULL,
    weight     integer NOT NULL,
    status     text NOT NULL,
    detail     jsonb
);
