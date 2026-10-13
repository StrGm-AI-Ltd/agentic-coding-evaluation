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
}
