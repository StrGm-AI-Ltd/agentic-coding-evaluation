package com.strgmai.ace.service.docker;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    /** #612, found live: a container-internal path like "/app/data/workspaces/x" is meaningless to
     *  the HOST's own docker daemon - it only happens to silently auto-create an empty directory
     *  there instead of erroring (confirmed live: `docker run -v /app/data/x:/w alpine ls /w` exits
     *  0, empty), so every "-v <container path>:/w" BuildChecks passed to a sibling container
     *  mounted an empty scratch copy regardless of what was actually written there. hostPath()
     *  rewrites the container-side prefix to the real host-side source docker inspect already
     *  knows about this container's own mounts. */
    @Test
    void hostPathRewritesTheLongestMatchingMountPrefix() {
        final var mounts = List.of(
                Map.entry("/app/data/workspaces/run1", "/var/lib/docker/volumes/x/_data/workspaces/run1"),   // longest first, as ownMounts() guarantees
                Map.entry("/app/data", "/var/lib/docker/volumes/x/_data"));

        assertEquals("/var/lib/docker/volumes/x/_data/workspaces/run1/ab-build-1/w",
                DockerService.hostPath(Path.of("/app/data/workspaces/run1/ab-build-1/w"), mounts),
                "the MORE SPECIFIC mount (run1's own) must win over the broader /app/data one");
        assertEquals("/var/lib/docker/volumes/x/_data/other/thing",
                DockerService.hostPath(Path.of("/app/data/other/thing"), mounts),
                "falls back to the broader /app/data mount when no more specific one matches");
    }

    /** A path under none of this container's own mounts (e.g. a container-private /tmp that was
     *  never bind-mounted at all) must come back unchanged, not throw or silently drop data - the
     *  same behavior this had before hostPath() existed, never a new failure mode. */
    @Test
    void hostPathReturnsThePathUnchangedWhenNoMountMatches() {
        final var mounts = List.of(Map.entry("/app/data", "/var/lib/docker/volumes/x/_data"));
        assertEquals("/tmp/unrelated/path", DockerService.hostPath(Path.of("/tmp/unrelated/path"), mounts));
    }
}
