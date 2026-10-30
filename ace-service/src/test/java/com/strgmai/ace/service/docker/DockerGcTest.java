package com.strgmai.ace.service.docker;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #621: a real round trip against a live daemon, not a mock - creates a container with an
 *  ANONYMOUS volume (no explicit name, exactly the shape a `VOLUME /var/lib/postgresql/data`
 *  Dockerfile directive produces, and exactly what the agent's own `docker run -d --name revpg
 *  ...` calls - no --rm - left behind on the production host; see #621's own comment thread),
 *  then leaves the container exited without removing it, then confirms prune() reclaims both.
 *
 *  Found live writing this test (confirmed against Docker 29.1.2): a plain, EXPLICITLY NAMED
 *  volume (`docker volume create my-name`) is NOT what `docker volume prune -f` (no `-a`) removes
 *  by default - only an anonymous one is. Deliberately NOT what this test simulates: an
 *  explicitly-named, currently-idle volume is not the real leak pattern #621 is about, and
 *  DockerGc.prune() deliberately stays at plain `-f` (never `-a`) rather than risk deleting an
 *  operator's own deliberately-created, currently-unattached named volume.
 *
 *  Needs a live Docker daemon; excluded from the default `test` task like DockerApplicationIT/
 *  SelftestIT - run via `./gradlew dockerTest`. */
@Tag("docker")
class DockerGcTest {

    @Test
    void pruneRemovesAnExitedContainerAndItsOwnAnonymousVolume() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available on this host");

        final String name = "ace-gc-test-" + System.nanoTime();
        // -v /data with no source: an ANONYMOUS volume, auto-named by the daemon - the same shape
        // postgres's own `VOLUME /var/lib/postgresql/data` Dockerfile directive produces. No --rm:
        // the container itself stays around exited, same as every leaked `revpg`/`revapp` instance.
        final var ran = DockerService.sh(15, "docker", "run", "-v", "/data", "--name", name, "alpine", "true");
        assertTrue(ran.rc() == 0, "setup: docker run failed: " + ran.out());
        final var inspect = DockerService.sh(10, "docker", "inspect", name, "--format", "{{range .Mounts}}{{.Name}}{{end}}");
        final String volume = inspect.out().strip();
        assertTrue(volume.length() == 64, "expected a 64-hex-char anonymous volume id, got: " + inspect.out());

        assertFalse(DockerService.sh(10, "docker", "ps", "-aq", "-f", "name=" + name).out().isBlank(), "the exited container must exist before prune()");
        assertTrue(DockerService.sh(10, "docker", "volume", "ls", "-q").out().contains(volume), "its anonymous volume must exist before prune()");

        new DockerGc().prune();

        assertTrue(DockerService.sh(10, "docker", "ps", "-aq", "-f", "name=" + name).out().isBlank(), "prune() must remove the exited container");
        assertFalse(DockerService.sh(10, "docker", "volume", "ls", "-q").out().contains(volume), "prune() must reclaim the now-orphaned anonymous volume");
    }
}
