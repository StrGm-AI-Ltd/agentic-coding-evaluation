-- RunsView.java's "started" column bound to Api.Run.started() with nothing backing it: the run's
-- own manifest.json already records this (RunBench.java's manifest.put("started", nowIso())), it
-- was just never carried into the runs table on import.
ALTER TABLE runs ADD COLUMN started TEXT;
