package com.strgmai.ace.service.docker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** #621, found live: nothing on the production host ever reclaimed the Docker images/containers/
 *  volumes a long run's own `docker compose up --build`/bash-tool container management leaves
 *  behind - confirmed live, repeatedly, across one session: 9.58GB then 6.75GB of dangling images
 *  from repeated agent builds, 39 orphaned anonymous volumes (2.9GB) from postgres containers a
 *  self-review session started without `--rm` once it needed a custom network (see #621's own
 *  comment thread for the full root-cause trace - not an ACE harness bug, the agent's own container
 *  management via its bash tool). The host filling up mid-run (#622) stops ace-service's worker
 *  thread from even persisting its own failure, orphaning the job's DB row.
 *
 *  Each prune command below only removes what is NOT referenced by any existing container, running
 *  or stopped (confirmed empirically, repeatedly, this same session) - never a live job's own
 *  images/containers/volumes, no matter when this fires. Container, then image, then volume: a
 *  container that just exited becomes prunable for the later two passes in the SAME run. */
@Component
public class DockerGc {
    private static final Logger log = LoggerFactory.getLogger(DockerGc.class);

    @Scheduled(fixedDelayString = "${ace.docker-gc.interval-sec:3600}000", initialDelay = 60000)
    public void prune() {
        for (final List<String> cmd : List.of(
                List.of("docker", "container", "prune", "-f"),
                List.of("docker", "image", "prune", "-f"),
                List.of("docker", "volume", "prune", "-f"))) {
            final DockerService.Sh r = DockerService.sh(120, cmd.toArray(new String[0]));
            if (r.rc() != 0) log.warn("`{}` failed (rc={}): {}", String.join(" ", cmd), r.rc(), r.out());
            else if (!r.out().isBlank()) log.info("`{}`: {}", String.join(" ", cmd), r.out().strip());
        }
    }
}
