package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The V-1 fix: blocked jobs must be cancelable AND requeueable, like in the service UI. */
class JobStatusesTest {

    @Test
    void canCancel_matrix() {
        for (final var status : new String[]{"queued", "waiting_lock", "running", "blocked"}) {
            assertTrue(JobStatuses.canCancel(status, false), status + " must be cancelable");
        }
        for (final var status : new String[]{"succeeded", "failed", "cancelled"}) {
            assertFalse(JobStatuses.canCancel(status, false), status + " is terminal, not cancelable");
        }
        assertFalse(JobStatuses.canCancel("running", true), "already-cancel-requested must not offer Cancel");
        assertFalse(JobStatuses.canCancel("blocked", true), "blocked with pending cancel must not re-offer Cancel");
    }

    @Test
    void canRequeue_matrix() {
        for (final var status : new String[]{"failed", "cancelled", "blocked", "paused"}) {
            assertTrue(JobStatuses.canRequeue(status), status + " must be requeueable (queue.requeue accepts it)");
        }
        for (final var status : new String[]{"succeeded", "queued", "waiting_lock", "running"}) {
            assertFalse(JobStatuses.canRequeue(status), status + " must not be requeueable");
        }
    }

    /** 'paused' has no Python equivalent - it's the new Pause button's resumable, non-terminal status. */
    @Test
    void canPause_matrix() {
        for (final var status : new String[]{"queued", "waiting_lock", "running", "blocked"}) {
            assertTrue(JobStatuses.canPause(status, false, false), status + " must be pausable");
        }
        for (final var status : new String[]{"succeeded", "failed", "cancelled"}) {
            assertFalse(JobStatuses.canPause(status, false, false), status + " is terminal, not pausable");
        }
        assertFalse(JobStatuses.canPause("running", true, false), "already-cancel-requested must not re-offer Pause");
        assertFalse(JobStatuses.canPause("running", false, true), "already-pause-requested must not re-offer Pause");
        assertFalse(JobStatuses.isTerminal("paused"), "paused must be resumable, never terminal");
    }

    @Test
    void terminalMatchesQueuePy() {
        assertTrue(JobStatuses.isTerminal("succeeded"));
        assertTrue(JobStatuses.isTerminal("failed"));
        assertTrue(JobStatuses.isTerminal("cancelled"));
        assertFalse(JobStatuses.isTerminal("blocked")); // blocked is a dead-end only without the fix above
    }
}
