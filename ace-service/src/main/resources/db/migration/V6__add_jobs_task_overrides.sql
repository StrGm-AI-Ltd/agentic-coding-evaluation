-- #263: per-task budget overrides for a restarted task (task_wall_sec/task_tokens/max_turns/
-- wrapup_wall_sec/wrapup_tokens/first_token_timeout_sec), keyed by task id - JSON shape
-- {"T7": {"task_wall_sec": 40000}}. JobCfgFactory reads this back into cfg on every claim/resume,
-- same jsonb-in-TEXT pattern as argv/manifest/params elsewhere in this schema (JsonColumns).
-- SQLite has no ALTER TABLE ... ADD COLUMN with a non-constant default restriction issue here
-- (DEFAULT '{}' is a constant), so a plain ADD COLUMN suffices - no table rebuild needed, unlike
-- V5's CHECK-constraint widening.
ALTER TABLE jobs ADD COLUMN task_overrides TEXT NOT NULL DEFAULT '{}';
