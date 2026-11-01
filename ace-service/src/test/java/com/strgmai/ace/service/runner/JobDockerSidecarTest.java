package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.docker.DockerService;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** #696: real, not mocked - no abstraction exists to mock DockerService.sh() against, same style
 *  DockerApplicationIT already uses for anything Docker-touching. Creates an actual privileged
 *  docker:28-dind container, so this is tagged "docker" and excluded from the default `test` task
 *  (see build.gradle) - run via `./gradlew dockerTest`. */
@Tag("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JobDockerSidecarTest {

    private static final String RUN_ID = "jds-test-" + System.currentTimeMillis();
    private static final JobDockerSidecar sidecar = new JobDockerSidecar();
    private static String firstDockerHost;

    @BeforeAll
    static void dockerAvailable() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available on this host");
    }

    @AfterAll
    static void cleanUp() {
        sidecar.destroy(RUN_ID);
    }

    /** #696: verifies the sidecar is genuinely alive and functional via `docker exec` (reaching it
     *  by NAME through the outer daemon, which always works) rather than dialing the returned
     *  tcp://<ip>:2375 DOCKER_HOST directly from this test JVM - confirmed live that direct
     *  container-to-container IP reachability on the shared bridge network (what ace-service's own
     *  container actually does in production) DOES work correctly, but Docker Desktop for Mac/
     *  Windows deliberately does not route a bare HOST process to a container's bridge IP at all
     *  (its containers run inside a hidden VM) - asserting that reachability from here would make
     *  this test fail on every contributor's Mac while proving nothing wrong in production. */
    @Test
    @Order(1)
    void ensureCreatesAReachableSidecar() {
        firstDockerHost = sidecar.ensure(RUN_ID, sidecar.resolveNetwork());
        assertTrue(firstDockerHost.startsWith("tcp://") && firstDockerHost.endsWith(":2375"), firstDockerHost);
        final var v = DockerService.sh(30, "docker", "exec", "dind-" + RUN_ID, "docker", "version", "--format", "{{.Server.Version}}");
        assertEquals(0, v.rc(), "the sidecar's own inner daemon must answer a real docker command: " + v.out());
        assertFalse(v.out().isBlank());
    }

    @Test
    @Order(2)
    void ensureIsIdempotentAndReusesTheSameContainer() {
        final String idBefore = DockerService.sh(10, "docker", "inspect", "--format", "{{.Id}}", "dind-" + RUN_ID).out().strip();
        final String second = sidecar.ensure(RUN_ID, sidecar.resolveNetwork());
        assertEquals(firstDockerHost, second, "a second ensure() for the same run id must return the SAME sidecar, not a new one");
        final String idAfter = DockerService.sh(10, "docker", "inspect", "--format", "{{.Id}}", "dind-" + RUN_ID).out().strip();
        assertEquals(idBefore, idAfter, "must be the exact same container, not a recreated one");
    }

    @Test
    @Order(3)
    void orphanedExcludesLiveRunsAndIncludesEverythingElse() {
        assertFalse(sidecar.orphaned(Set.of(RUN_ID)).contains(RUN_ID), "a run id passed as live must never be reported orphaned");
        assertTrue(sidecar.orphaned(Set.of()).contains(RUN_ID), "a sidecar whose run id isn't live is orphaned");
    }

    @Test
    @Order(4)
    void destroyActuallyRemovesTheSidecar() {
        sidecar.destroy(RUN_ID);
        final var inspect = DockerService.sh(10, "docker", "inspect", "dind-" + RUN_ID);
        assertNotEquals(0, inspect.rc(), "the sidecar must be gone after destroy()");
    }
}
