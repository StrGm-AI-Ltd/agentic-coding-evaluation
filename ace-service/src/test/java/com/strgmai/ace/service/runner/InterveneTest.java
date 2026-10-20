package com.strgmai.ace.service.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** #417-followup: Intervene had no direct test coverage at all despite already being live in
 *  production for tasks - closing that gap now that review sessions use the same mailbox too
 *  (Reviews.runReviewerSession()). */
class InterveneTest {

    @Test
    void pollReturnsNullWhenNothingIsPending() {
        final var intervene = new Intervene();
        assertNull(intervene.poll("run-1", "T1"));
    }

    @Test
    void offerWrapsTheTextAsAnAuthoritativeOperatorInstruction() {
        final var intervene = new Intervene();
        intervene.offer("run-1", "T1", "stop refactoring, just make the tests pass");
        final var delivered = intervene.poll("run-1", "T1");
        assertEquals("[Operator intervention] A human operator is steering you directly. Follow this instruction:\n\n"
                + "stop refactoring, just make the tests pass", delivered);
    }

    @Test
    void pollIsFifoAndEmptiesTheMailbox() {
        final var intervene = new Intervene();
        intervene.offer("run-1", "T1", "first");
        intervene.offer("run-1", "T1", "second");
        assertEquals(true, intervene.poll("run-1", "T1").endsWith("first"));
        assertEquals(true, intervene.poll("run-1", "T1").endsWith("second"));
        assertNull(intervene.poll("run-1", "T1"), "the mailbox must be empty once everything pending has been delivered");
    }

    /** The mailbox is keyed by (runId, taskId) together - a message for one must never leak into
     *  another run's session, or a different task/phase/review in the SAME run. */
    @Test
    void mailboxesAreIsolatedByBothRunIdAndTaskId() {
        final var intervene = new Intervene();
        intervene.offer("run-1", "T1", "for run-1 T1");
        assertNull(intervene.poll("run-1", "T2"), "a different task id in the same run must not see it");
        assertNull(intervene.poll("run-2", "T1"), "the same task id in a different run must not see it");
        assertEquals(true, intervene.poll("run-1", "T1").endsWith("for run-1 T1"), "the exact (runId, taskId) pair it was sent to must still see it");
    }

    @Test
    void clearDropsAnythingPendingForThatKeyOnly() {
        final var intervene = new Intervene();
        intervene.offer("run-1", "T1", "stale, from a prior attempt");
        intervene.offer("run-1", "T2", "a different task - must survive T1's own clear");
        intervene.clear("run-1", "T1");
        assertNull(intervene.poll("run-1", "T1"), "cleared - must not leak into a later, unrelated attempt at the same id");
        assertEquals(true, intervene.poll("run-1", "T2").endsWith("a different task - must survive T1's own clear"));
    }

    @Test
    void clearOnAKeyWithNothingPendingIsANoOpNotAnError() {
        final var intervene = new Intervene();
        intervene.clear("run-1", "never-sent-anything");
        assertNull(intervene.poll("run-1", "never-sent-anything"));
    }
}
