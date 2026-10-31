package com.strgmai.ace.service.docker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Port of run_bench.py's Docker machinery, minus the on-demand lifecycle (R7): ACE no longer
 *  starts or stops Docker Desktop itself - the operator owns that entirely. This only probes
 *  whether it happens to be running and mirrors the agent's Docker CLI tooling into a fresh HOME. */
public final class DockerService {
    private DockerService() {}

    private static final Logger log = LoggerFactory.getLogger(DockerService.class);

    /** cheap, daemon-free: is Docker Desktop's backend alive right now */
    public static boolean dockerRunning() {
        return sh(8, "pgrep", "-f", "com.docker.backend").rc == 0;
    }

    /** the agent's Docker CLI plugins in a fresh HOME (`docker compose` lives in
     *  ~/.docker/cli-plugins of the OPERATOR's home). Only plugin symlinks are linked, never
     *  config.json (credentials); the real `docker` binary itself is reached through the ordinary
     *  PATH the scrubbed environment already copies from the operator's own shell - no shim. */
    public static void dockerTools(final String home) throws IOException {
        final var plug = Path.of(home, ".docker", "cli-plugins");
        Files.createDirectories(plug);
        for (String src : List.of(Path.of(System.getProperty("user.home"), ".docker/cli-plugins").toString(),
                "/Applications/Docker.app/Contents/Resources/cli-plugins")) {
            final var s = Path.of(src);
            if (Files.isDirectory(s)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(s)) {
                    for (Path f : ds) {
                        final String n = f.getFileName().toString();
                        final Path dst = plug.resolve(n);
                        if (n.startsWith("docker-") && !Files.exists(dst))
                            Files.createSymbolicLink(dst, f.toRealPath());
                    }
                }
                break;
            }
        }
    }

    public static boolean portInUse(final int port, final String host) {
        try (var s = new java.net.Socket(host, port)) { return true; }
        catch (IOException e) { return false; }
    }

    /** #671-followup2, found live: ComposeChecks' own pre-flight port-8080 guard called
     *  portInUse(port, "127.0.0.1") from HERE - inside ace-service's own container (a Docker-
     *  outside-of-Docker deployment: this JVM shells out to the HOST'S docker daemon via a mounted
     *  socket, but has its own, separate container network namespace). "127.0.0.1" from inside THAT
     *  namespace is never the host's loopback - a container published on the real host network
     *  (0.0.0.0:8080->8080, confirmed live sitting there for over an hour) is NOT reachable via
     *  127.0.0.1 from here, the exact same "localhost won't work from here" shape the codebase
     *  already documents as a known gotcha for the AGENT's own bash sandbox (Packs.KNOWN_GOTCHAS) -
     *  this was the harness's OWN check falling into the identical trap, silently: the guard never
     *  once detected a real collision, always reporting the port free. Confirmed live: curling
     *  host.docker.internal:8080 from inside this container got a real HTTP response; 127.0.0.1:8080
     *  got connection-refused, against the exact same already-bound host port at the same moment.
     *
     *  <p>Checks BOTH 127.0.0.1 and host.docker.internal (true if either answers) rather than
     *  switching to just one, since this same method also has to work when ace-service runs
     *  directly on a developer's own machine (not containerized at all) - there, 127.0.0.1 IS the
     *  correct host and "host.docker.internal" may not even resolve; in the container deployment,
     *  the reverse holds. No environment-detection flag needed - trying both is cheap and correct
     *  either way. */
    public static boolean hostPortInUse(final int port) {
        return portInUse(port, "127.0.0.1") || portInUse(port, "host.docker.internal");
    }

    /** one subprocess run with BOTH streams drained concurrently (read-after-waitFor deadlocks
     *  on >64KB: the pipe fills, the child blocks on write, waitFor never returns) */
    public record Proc(int rc, String out, String err) {}
    public static Proc proc(final int timeoutSec, final Path cwd, final Map<String, String> env, final String... cmd) {
        Process p = null;
        Thread t1 = null, t2 = null;
        try {
            final var pb = new ProcessBuilder(cmd);
            if (cwd != null) pb.directory(cwd.toFile());
            if (env != null) { pb.environment().clear(); pb.environment().putAll(env); }
            p = pb.start();
            final StringBuilder out = new StringBuilder(), err = new StringBuilder();
            t1 = drain(p.getInputStream(), out); t2 = drain(p.getErrorStream(), err);
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                t1.join(1000); t2.join(1000);
                return new Proc(124, out.toString(), err.toString() + "\n[timeout " + timeoutSec + "s]");
            }
            t1.join(5000); t2.join(5000);
            return new Proc(p.exitValue(), out.toString(), err.toString());
        } catch (InterruptedException ie) {
            // #271: waitFor()/join() throw this on an external interrupt (job cancellation, the
            // harness's own task-wall preemption) - the generic catch below used to swallow it
            // silently: the real OS subprocess (and anything IT spawned, e.g. a gradle/docker-compose
            // child) was simply abandoned, still running, AND the interrupted status was consumed
            // with nothing left to notice the cancellation ever happened. Killing the process here
            // and restoring the interrupted flag match the timeout branch above and this codebase's
            // "an external interrupt must reach whatever is actually blocking" discipline elsewhere
            // (ReferenceAgent.chatWithRetry, RecordingProxy.abortInflight). 130 (128+SIGINT, the
            // shell convention) keeps this distinguishable from a genuine 1 "went wrong" exit.
            if (p != null) p.destroyForcibly();
            try { if (t1 != null) t1.join(1000); if (t2 != null) t2.join(1000); } catch (InterruptedException ignore) { /* already interrupted; restored below regardless */ }
            Thread.currentThread().interrupt();
            return new Proc(130, "", "interrupted");
        } catch (Exception e) { return new Proc(1, "", String.valueOf(e)); }
    }

    private static Thread drain(final java.io.InputStream in, final StringBuilder sb) {
        Thread t = new Thread(() -> {
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) sb.append(line).append('\n');
            } catch (Exception e) { log.debug("subprocess output stream drain ended: {}", e.toString()); }
        }, "proc-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    public record Sh(int rc, String out) {}
    public static Sh sh(final int timeoutSec, final String... cmd) {
        final Proc r = proc(timeoutSec, null, null, cmd);
        return new Sh(r.rc(), r.out() + r.err());
    }

    public static void sleep(int sec) { try { TimeUnit.SECONDS.sleep(sec); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    public static String nowIso() { return DateTimeFormatter.ISO_INSTANT.format(Instant.now()); }

    // #612, found live: when ace-service itself runs as a container (Docker-outside-of-Docker, the
    // only topology that deployment uses), a path string that's meaningful INSIDE this container's
    // own mount namespace (e.g. "/app/data/...", the named-volume mount point docker-compose.yml
    // itself defines) is NOT a real path on the actual host - it only happens to exist there as an
    // accidental, empty, auto-created directory the first time something bind-mounts it (confirmed
    // live: `docker run -v /app/data/x:/w alpine ls /w` silently creates an empty host-root
    // "/app/data/x" and mounts THAT in, exit 0, no error). Every sibling container BuildChecks/
    // ComposeChecks spawn via "-v <container-path>:/w" over the shared host daemon was silently
    // getting an empty directory instead of the real scratch copy just written there - this is what
    // hostPath() actually fixes: given a path under one of THIS container's own mounts, resolve the
    // REAL host-side source Docker itself already knows about (self-introspection via `docker
    // inspect` on this container's own id - HOSTNAME is Docker's own default container-id env var,
    // not something this image sets), so the sibling container's "-v" argument points at the same
    // bytes instead of a coincidentally-matching but empty host path.
    private static volatile List<Map.Entry<String, String>> ownMounts;   // destination -> host source, longest-first

    private static List<Map.Entry<String, String>> ownMounts() {
        var m = ownMounts;
        if (m != null) return m;
        synchronized (DockerService.class) {
            if (ownMounts != null) return ownMounts;
            final String id = System.getenv("HOSTNAME");
            final List<Map.Entry<String, String>> out = new ArrayList<>();
            if (id != null && !id.isBlank()) {
                final Sh r = sh(15, "docker", "inspect", id, "--format",
                        "{{range .Mounts}}{{.Destination}}\t{{.Source}}\n{{end}}");
                if (r.rc() == 0) {
                    for (String line : r.out().split("\n")) {
                        final String[] parts = line.split("\t", 2);
                        if (parts.length == 2 && !parts[0].isBlank()) out.add(Map.entry(parts[0], parts[1]));
                    }
                } else {
                    log.warn("could not resolve this container's own mounts via `docker inspect {}` (rc={}) - hostPath() will fall back to returning paths unchanged, which silently reproduces the empty-mount bug this exists to fix", id, r.rc());
                }
            }
            out.sort((a, b) -> b.getKey().length() - a.getKey().length());   // longest destination prefix must win (e.g. "/app/data/x" over "/app/data")
            ownMounts = List.copyOf(out);
            return ownMounts;
        }
    }

    /** Translates a path under one of THIS container's own mounts (e.g. "/app/data/workspaces/x")
     *  into its real host-side equivalent, for passing as the SOURCE half of a sibling container's
     *  own "-v" bind mount. Returns the path unchanged (with a one-time warning already logged by
     *  ownMounts() above) if it isn't under any known mount or this isn't running containerized at
     *  all - the same behavior the code had before this existed, never a new failure mode. */
    public static String hostPath(final Path containerPath) {
        return hostPath(containerPath, ownMounts());
    }

    /** The pure matching logic, split out from ownMounts()'s own `docker inspect` call so it's
     *  testable with a literal mount list instead of a real container environment. mounts must
     *  already be longest-destination-first (ownMounts() guarantees this); this does not re-sort. */
    static String hostPath(final Path containerPath, final List<Map.Entry<String, String>> mounts) {
        final String p = containerPath.toString();
        for (final var mount : mounts) {
            final String dest = mount.getKey();
            if (p.equals(dest) || p.startsWith(dest + "/")) return mount.getValue() + p.substring(dest.length());
        }
        return p;
    }
}
