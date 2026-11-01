package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.docker.DockerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** #696, found live: ace-service mounts the HOST's real /var/run/docker.sock (Docker-outside-of-
 *  Docker) so the agent's own bash tool can use `docker` at all - but that gives the agent's own
 *  `docker ps`/`docker rm` ambient authority over EVERY container on the host, including
 *  ace-service's own sibling containers. An agent that got stuck on a port conflict ran
 *  `docker rm -f $(docker ps -aq)` and took down the entire production stack - twice. Each job now
 *  gets its own Docker-in-Docker sidecar, named deterministically from its run id; the agent's own
 *  tool-call environment is redirected at it via DOCKER_HOST (see RunBenchSupport.scrubbedEnv()),
 *  so an agent's own docker commands can only ever see and destroy its OWN job's containers.
 *
 *  <p>Does NOT touch the harness's own oracle verification (ComposeChecks/BuildChecks): those
 *  always rebuild from a fresh, source-only scratch copy against the real host daemon, never
 *  inspecting whatever the agent's own session left running - confirmed by reading both in full.
 *
 *  <p>An injectable Spring component, not a static utility, on purpose - the same shape
 *  RunRetention already uses. Every call here shells out to a real `docker` binary; WorkerService
 *  calls destroy()/orphaned() on its own hot catch/finally path, which WorkerServiceTest exercises
 *  with real timing assertions against pure mocks - a static method would have made that real
 *  subprocess call unavoidable in every one of those fast unit tests. */
@Component
public class JobDockerSidecar {
    private static final Logger log = LoggerFactory.getLogger(JobDockerSidecar.class);
    private static final String IMAGE = "docker:28-dind";
    private static final int DOCKER_PORT = 2375;
    // found live: a plaintext (no-TLS) dockerd deliberately slows its own startup by ~20s to show
    // its "insecure, consider TLS" warning (see dind's own startup log) before it binds the TCP
    // port at all - 30s cut that too close on an ordinary machine with no other load. 60s leaves
    // real margin.
    private static final int READY_TIMEOUT_SEC = 60;

    private static String containerName(final String runId) {
        return "dind-" + runId;
    }

    /** ace-service must create each sidecar on whatever network it is ALREADY on, so the two can
     *  reach each other by IP - hardcoding a compose-project-specific network name would break on
     *  any differently-named deployment. InetAddress.getLocalHost().getHostName() (not the HOSTNAME
     *  env var, which an exec-form ENTRYPOINT with no shell never gets) reliably returns this
     *  container's own short id, since Docker always sets the kernel hostname regardless of
     *  entrypoint shape. Returns null (meaning: no --network flag, accept the default bridge) if
     *  resolution fails for any reason - safer than refusing to run a job over a missing network. */
    public String resolveNetwork() {
        try {
            final String selfId = InetAddress.getLocalHost().getHostName();
            final var r = DockerService.sh(10, "docker", "inspect", "--format",
                    "{{range $k,$v := .NetworkSettings.Networks}}{{$k}}\n{{end}}", selfId);
            if (r.rc() != 0) { log.warn("could not resolve ace-service's own docker network: {}", r.out()); return null; }
            final String first = r.out().lines().map(String::strip).filter(s -> !s.isBlank()).findFirst().orElse(null);
            if (first == null) log.warn("ace-service's own container reported no docker networks");
            return first;
        } catch (final Exception e) {
            log.warn("could not resolve ace-service's own docker network: {}", e.toString());
            return null;
        }
    }

    /** Idempotent find-or-start-or-create; safe to call on every runOnce() attempt of the same run
     *  id (including a resume after pause, or a service restart) - an already-running sidecar is
     *  reused as-is, with whatever images/containers the agent already built inside it intact. */
    public String ensure(final String runId, final String network) {
        final String name = containerName(runId);
        final var running = DockerService.sh(10, "docker", "inspect", "--format", "{{.State.Running}}", name);
        if (running.rc() != 0) {
            final List<String> run = new ArrayList<>(List.of("docker", "run", "-d", "--privileged", "--name", name));
            if (network != null && !network.isBlank()) run.addAll(List.of("--network", network));
            run.addAll(List.of("-e", "DOCKER_TLS_CERTDIR=", IMAGE));
            final var created = DockerService.sh(120, run.toArray(String[]::new));
            if (created.rc() != 0) throw new IllegalStateException("could not create docker sidecar " + name + ": " + created.out());
        } else if (!"true".equals(running.out().strip())) {
            final var started = DockerService.sh(30, "docker", "start", name);
            if (started.rc() != 0) throw new IllegalStateException("could not start existing docker sidecar " + name + ": " + started.out());
        }
        final String ip = containerIp(name);
        if (ip == null) throw new IllegalStateException("docker sidecar " + name + " has no reachable IP");
        waitUntilReady(name, ip);
        return "tcp://" + ip + ":" + DOCKER_PORT;
    }

    private static String containerIp(final String name) {
        final var r = DockerService.sh(10, "docker", "inspect", "--format",
                "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", name);
        final String ip = r.out().strip();
        return r.rc() == 0 && !ip.isBlank() ? ip : null;
    }

    private static void waitUntilReady(final String name, final String ip) {
        final long deadline = System.nanoTime() + READY_TIMEOUT_SEC * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (DockerService.portInUse(DOCKER_PORT, ip)) return;
            try { Thread.sleep(1000); } catch (final InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("docker sidecar {} did not answer on port {} within {}s - proceeding anyway, the agent's first docker call will surface the real error", name, DOCKER_PORT, READY_TIMEOUT_SEC);
    }

    /** Best-effort, never throws - same philosophy RunRetention.stripBinaries already follows: a
     *  failed cleanup here must never fail the job whose own outcome is already decided. */
    public void destroy(final String runId) {
        try {
            final var r = DockerService.sh(60, "docker", "rm", "-f", containerName(runId));
            if (r.rc() != 0 && !r.out().contains("No such container"))
                log.warn("could not remove docker sidecar for run {}: {}", runId, r.out());
        } catch (final Exception e) {
            log.warn("could not remove docker sidecar for run {}: {}", runId, e.toString());
        }
    }

    /** Called once at WorkerService startup (reconcile()): any dind-<runId> container whose run id
     *  is not among the jobs still queued/running/waiting_lock/blocked/paused was left behind by a
     *  crash (ace-service itself being killed before its own cleanup ran is exactly how issue #696
     *  started) and is safe to remove. */
    public List<String> orphaned(final Set<String> liveRunIds) {
        final var r = DockerService.sh(15, "docker", "ps", "-a", "--filter", "name=dind-", "--format", "{{.Names}}");
        if (r.rc() != 0) { log.warn("could not list docker sidecars to sweep orphans: {}", r.out()); return List.of(); }
        final Set<String> live = liveRunIds == null ? new HashSet<>() : liveRunIds;
        final List<String> orphans = new ArrayList<>();
        for (final String name : r.out().lines().map(String::strip).filter(s -> s.startsWith("dind-")).toList()) {
            final String runId = name.substring("dind-".length());
            if (!live.contains(runId)) orphans.add(runId);
        }
        return orphans;
    }
}
