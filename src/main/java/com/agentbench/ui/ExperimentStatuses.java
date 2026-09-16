package com.agentbench.ui;

import java.util.List;

/**
 * Effective experiment status, derived from its jobs: the service never sets
 * {@code experiments.status = 'running'} (only {@code queued} → {@code finished} via
 * finalize_if_done), so a mid-flight experiment would read "queued" forever. The UI
 * derives the truth: any job running/waiting_lock → running; only blocked jobs →
 * blocked; otherwise the table status stands.
 */
public final class ExperimentStatuses {

    private ExperimentStatuses() {
    }

    public static String effective(String tableStatus, List<String> jobStatuses) {
        if (!"queued".equals(tableStatus) || jobStatuses == null || jobStatuses.isEmpty()) {
            return tableStatus;
        }
        boolean progressing = jobStatuses.stream()
                .anyMatch(status -> "running".equals(status) || "waiting_lock".equals(status));
        if (progressing) {
            return "running";
        }
        boolean blocked = jobStatuses.stream().anyMatch("blocked"::equals);
        if (blocked) {
            return "blocked";
        }
        return "queued";
    }
}
