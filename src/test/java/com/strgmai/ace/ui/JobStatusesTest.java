package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The V-1 fix: blocked jobs must be cancelable AND requeueable, like in the service UI. */
class JobStatusesTest {

    @Test
    void canCancel_matrix() {
        for (String status : new String[]{"queued", "waiting_lock", "running", "blocked"}) {
            assertTrue(JobStatuses.canCancel(status, false), status + " must be cancelable");
        }
        for (String status : new String[]{"succeeded", "failed", "cancelled"}) {
            assertFalse(JobStatuses.canCancel(status, false), status + " is terminal, not cancelable");
        }
        assertFalse(JobStatuses.canCancel("running", true), "already-cancel-requested must not offer Cancel");
        assertFalse(JobStatuses.canCancel("blocked", true), "blocked with pending cancel must not re-offer Cancel");
    }

    @Test
    void canRequeue_matrix() {
        for (String status : new String[]{"failed", "cancelled", "blocked"}) {
            assertTrue(JobStatuses.canRequeue(status), status + " must be requeueable (queue.requeue accepts it)");
        }
        for (String status : new String[]{"succeeded", "queued", "waiting_lock", "running"}) {
            assertFalse(JobStatuses.canRequeue(status), status + " must not be requeueable");
        }
    }

    @Test
    void terminalMatchesQueuePy() {
        assertTrue(JobStatuses.isTerminal("succeeded"));
        assertTrue(JobStatuses.isTerminal("failed"));
        assertTrue(JobStatuses.isTerminal("cancelled"));
        assertFalse(JobStatuses.isTerminal("blocked")); // blocked is a dead-end only without the fix above
    }
}
