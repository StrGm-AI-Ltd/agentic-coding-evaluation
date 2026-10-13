package com.strgmai.ace.service.docker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DockerServiceTest {

    /** #271, found live: waitFor()'s InterruptedException used to land in proc()'s generic catch,
     *  which never killed the real OS process and never restored the interrupted flag - an
     *  external interrupt (job cancellation, the harness's own task-wall preemption) was silently
     *  dropped, and the subprocess kept running unkilled. rc=130 (the shell SIGINT convention)
     *  must be distinguishable from a normal failure (1) or timeout (124). */
    @Test
    void procKillsTheSubprocessAndRestoresTheInterruptedFlagOnAnExternalInterrupt() throws Exception {
        final DockerService.Proc[] result = new DockerService.Proc[1];
        final boolean[] interruptedFlagRestored = {false};
        final Thread worker = new Thread(() -> {
            result[0] = DockerService.proc(30, null, null, "sleep", "30");
            interruptedFlagRestored[0] = Thread.currentThread().isInterrupted();
        });
        worker.start();
        Thread.sleep(500);   // let the subprocess actually start before interrupting
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "proc() must return promptly on an external interrupt, not wait out the sleep or the 30s timeout");
        assertNotNull(result[0]);
        assertEquals(130, result[0].rc(), "an interrupted proc() must be distinguishable from a normal failure (1) or timeout (124)");
        assertTrue(interruptedFlagRestored[0], "the interrupted status must be restored, not silently consumed");
    }
}
