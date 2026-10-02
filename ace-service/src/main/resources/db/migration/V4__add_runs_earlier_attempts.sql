-- #217: a run's wall_sec/started only ever reflect its last attempt - if the same run_id was
-- started, interrupted, and restarted earlier the same day, that earlier work (and the time it
-- took) is invisible. Counts session files whose canonical session id was superseded by a later
-- file for the same id - detection only, never folded into wall_sec/total_wall_sec itself.
ALTER TABLE runs ADD COLUMN earlier_attempts INTEGER NOT NULL DEFAULT 0;
