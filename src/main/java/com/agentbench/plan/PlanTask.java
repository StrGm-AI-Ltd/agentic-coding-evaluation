package com.agentbench.plan;

import java.util.List;

/** Port of plan.py's parsed task. Fields are nullable exactly as in Python (deps/checks null until resolved). */
public final class PlanTask {
    public String id, title, goal, services, acceptance;
    public List<String> deps;
    public List<String> checks;
    int score;   // when an id occurs twice (overview table before detailed sections) the richer block wins

    public PlanTask(String id, String title) { this.id = id; this.title = title; this.goal = ""; this.services = ""; this.acceptance = ""; }
    public int score() {
        return (goal != null && !goal.isBlank() ? 1 : 0) + (services != null && !services.isBlank() ? 1 : 0)
                + (acceptance != null && !acceptance.isBlank() ? 1 : 0) + (deps != null && !deps.isEmpty() ? 1 : 0)
                + (checks != null && !checks.isEmpty() ? 1 : 0);
    }
    @Override public String toString() { return id + (title == null ? "" : " " + title); }
}
