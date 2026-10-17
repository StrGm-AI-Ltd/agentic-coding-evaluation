package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.service.JobQueue;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** An operator's "pause once the current task finishes" request. Unlike the existing immediate
 *  /pause (which can land mid-task and discard real work), this only ever calls through to the
 *  exact same JobQueue.pause() at a safe, between-tasks checkpoint RunBench's own orchestration
 *  loop triggers. */
class PauseAfterTaskTest {

    @Test
    void checkAndPauseIfRequestedDoesNothingWhenNothingWasRequested() {
        final JobQueue queue = mock(JobQueue.class);
        new PauseAfterTask(queue).checkAndPauseIfRequested("svc-20261013-opt");
        verify(queue, never()).pause(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void checkAndPauseIfRequestedCallsTheExistingPauseOnceRequested() {
        final JobQueue queue = mock(JobQueue.class);
        final var pat = new PauseAfterTask(queue);
        final UUID jobId = UUID.randomUUID();
        pat.request("svc-20261013-opt", jobId);
        assertTrue(pat.isRequested("svc-20261013-opt"));

        pat.checkAndPauseIfRequested("svc-20261013-opt");

        verify(queue).pause(jobId);
    }

    @Test
    void checkAndPauseIfRequestedConsumesTheRequestSoItOnlyFiresOnce() {
        final JobQueue queue = mock(JobQueue.class);
        final var pat = new PauseAfterTask(queue);
        final UUID jobId = UUID.randomUUID();
        pat.request("svc-20261013-opt", jobId);

        pat.checkAndPauseIfRequested("svc-20261013-opt");   // the task boundary that should trigger it
        pat.checkAndPauseIfRequested("svc-20261013-opt");   // every later task boundary in the same run

        verify(queue, org.mockito.Mockito.times(1)).pause(jobId);
        assertFalse(pat.isRequested("svc-20261013-opt"));
    }

    @Test
    void checkAndPauseIfRequestedIsScopedToItsOwnRunId() {
        final JobQueue queue = mock(JobQueue.class);
        final var pat = new PauseAfterTask(queue);
        pat.request("svc-A", UUID.randomUUID());

        pat.checkAndPauseIfRequested("svc-B");   // a different run - must not be affected

        verify(queue, never()).pause(org.mockito.ArgumentMatchers.any());
        assertTrue(pat.isRequested("svc-A"), "svc-A's own pending request must be untouched");
    }

    /** A transient failure pausing the job must not crash the whole run, nor must it keep retrying
     *  on every later task boundary - the request is already consumed by the time pause() is
     *  attempted. */
    @Test
    void checkAndPauseIfRequestedToleratesAFailedPauseCall() {
        final JobQueue queue = mock(JobQueue.class);
        final UUID jobId = UUID.randomUUID();
        org.mockito.Mockito.when(queue.pause(jobId)).thenThrow(new IllegalStateException("already terminal"));
        final var pat = new PauseAfterTask(queue);
        pat.request("svc-20261013-opt", jobId);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> pat.checkAndPauseIfRequested("svc-20261013-opt"));
        assertFalse(pat.isRequested("svc-20261013-opt"));
    }
}
