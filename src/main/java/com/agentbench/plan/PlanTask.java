package com.agentbench.plan;

import java.util.List;

/** Port of plan.py's parsed task. Fields are nullable exactly as in Python (deps/checks null until resolved). */
public final class PlanTask {
    public String id, title, goal, services, acceptance;   // parsed inputs - kept public because RunBench/RunOracle read them; see the dedupScore comment below
    public List<String> deps;
    public List<String> checks;
    int dedupScore;   // THE dedup value (score() + the detailed-section bonus), assigned once by the parser - the raw richness is score()

    public PlanTask(String id, String title) { this.id = id; this.title = title; this.goal = ""; this.services = ""; this.acceptance = ""; }
    public int score() {
        return (goal != null && !goal.isBlank() ? 1 : 0) + (services != null && !services.isBlank() ? 1 : 0)
                + (acceptance != null && !acceptance.isBlank() ? 1 : 0) + (deps != null && !deps.isEmpty() ? 1 : 0)
                + (checks != null && !checks.isEmpty() ? 1 : 0);
    }
    /** the authoritative dedup score the parser used to pick the winner (score() + the detailed-section bonus) */
    public int dedupScore() { return dedupScore; }
    @Override public String toString() { return id + (title == null ? "" : " " + title); }
}
