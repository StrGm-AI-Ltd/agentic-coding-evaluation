package com.strgmai.ace.ui;

import java.util.Set;

/**
 * Job status semantics, mirroring agentbench_service/queue.py exactly (C-3):
 * TERMINAL is queue.py's TERMINAL; requeue accepts failed, cancelled and BLOCKED
 * (queue.requeue:173) — a blocked job is never a dead end in this UI (V-1).
 *
 * 'paused' has no Python equivalent - it's new here (the Pause button): a gentler cancel that lands
 * on a resumable, non-terminal status instead of 'cancelled', so it belongs in REQUEUEABLE but never
 * in TERMINAL.
 */
public final class JobStatuses {

    public static final Set<String> TERMINAL = Set.of("succeeded", "failed", "cancelled");
    public static final Set<String> REQUEUEABLE = Set.of("failed", "cancelled", "blocked", "paused");

    private JobStatuses() {
    }

    public static boolean isTerminal(final String status) {
        // Set.of rejects null: an unknown/absent status is non-terminal, not a UI crash
        return status != null && TERMINAL.contains(status);
    }

    /** Cancel: any non-terminal job that has not already been asked to stop. */
    public static boolean canCancel(final String status, final boolean cancelRequested) {
        return !isTerminal(status) && !cancelRequested;
    }

    /** Pause: any non-terminal job that has not already been asked to stop or pause. */
    public static boolean canPause(final String status, final boolean cancelRequested, final boolean pauseRequested) {
        return !isTerminal(status) && !cancelRequested && !pauseRequested;
    }

    /** Requeue: failed, cancelled, blocked or paused — queue.py accepts the first three; paused is new. */
    public static boolean canRequeue(final String status) {
        return status != null && REQUEUEABLE.contains(status);
    }
}
