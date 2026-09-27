package com.strgmai.ace.service.docker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Port of run_bench.py's Docker-on-demand machinery (R7): Docker Desktop is DOWN while the agent
 *  works (its VM competes with the model for memory and CPU), comes up through the shim on the
 *  agent's first `docker` call, and is stopped again after idle_sec without a call and at the end
 *  of every implementation-kind session. Also ports docker_tools (the shim + CLI plugins into the
 *  fresh HOME) and the shim-log parsing that drives the window record. */
public final class DockerService {
    private DockerService() {}

    private static final Logger log = LoggerFactory.getLogger(DockerService.class);
    /** how often dockerUp() will relaunch a backend that's still not answering - found live
     *  2026-09-27: the old code relaunched exactly ONCE, at the timeout's halfway mark, so a run
     *  with a long timeoutSec spent most of it waiting on a relaunch attempt that itself failed to
     *  take, instead of trying again. */
    private static final int RELAUNCH_INTERVAL_SEC = 90;

    /** cheap, daemon-free: is Docker Desktop's backend alive right now */
    public static boolean dockerRunning() {
        return sh(8, "pgrep", "-f", "com.docker.backend").rc == 0;
    }

    /** start Docker Desktop and wait for the daemon. Relaunches on a fixed cadence rather than once
     *  at the halfway mark, and relaunches IMMEDIATELY - rather than waiting out the rest of the
     *  interval - when the backend process is gone entirely (a crash mid-boot), as opposed to merely
     *  still booting (its VM's cold start under memory pressure from a co-resident model server is
     *  expected to take a while, not a reason to kill and retry). */
    public static boolean dockerUp(final int timeoutSec) {
        sh(10, "open", "-a", "Docker");
        final long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        long nextRelaunch = System.currentTimeMillis() + RELAUNCH_INTERVAL_SEC * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (sh(10, "docker", "info", "--format", "{{.ServerVersion}}").rc == 0) return true;
            if (shouldRelaunch(dockerRunning(), System.currentTimeMillis(), nextRelaunch)) {
                sh(10, "pkill", "-9", "-f", "com.docker.backend");
                sleep(5);
                sh(10, "open", "-a", "Docker");
                nextRelaunch = System.currentTimeMillis() + RELAUNCH_INTERVAL_SEC * 1000L;
            }
            sleep(5);
        }
        return false;
    }

    /** the actual retry POLICY dockerUp() follows, pulled out so it's unit-testable without
     *  spawning real processes: relaunch now if the backend process is gone entirely (crashed - no
     *  reason to wait out the rest of the interval), or if it's still alive but the relaunch
     *  interval has elapsed without the daemon answering (booting too long to just be cold-start). */
    static boolean shouldRelaunch(final boolean backendAlive, final long nowMs, final long nextRelaunchAtMs) {
        return !backendAlive || nowMs >= nextRelaunchAtMs;
    }

    /** never treat an idle window as safe to close-and-kill while a docker CLI operation (compose
     *  up, build) is still actively running as a child process - found live 2026-09-27:
     *  dockerCliBusy() existed for exactly this but had no caller anywhere, so the idle monitor could
     *  hard-kill the backend mid-operation, because the shim only logs at INVOCATION time and a
     *  long-running compose/build with no fresh shim-log entry looked identical to genuine idleness.
     *  A dead backend (!up) still closes the window regardless - there's nothing left to protect. */
    public static boolean shouldCloseWindow(final boolean up, final double idleSec, final int idleThresholdSec, final boolean cliBusy) {
        return !up || (idleSec > idleThresholdSec && !cliBusy);
    }

    /** quit Docker Desktop so its VM stops competing with the model; it wedges, so fall back to a hard kill quickly */
    public static boolean dockerDown(final int timeoutSec) {
        try { new ProcessBuilder("osascript", "-e", "quit app \"Docker\"").start().waitFor(20, TimeUnit.SECONDS); }
        catch (Exception e) { log.debug("graceful `quit app Docker` failed, falling back to a hard kill: {}", e.toString()); }
        for (int i = 0; i < timeoutSec / 3; i++) {
            if (sh(8, "pgrep", "-f", "com.docker.backend").rc != 0) return true;
            sleep(3);
        }
        sh(8, "pkill", "-9", "-f", "com.docker.backend");
        sleep(3);
        return sh(8, "pgrep", "-f", "com.docker.backend").rc != 0;
    }

    /** the agent's Docker CLI in a fresh HOME: the on-demand shim first on PATH and the CLI plugins
     *  (`docker compose` lives in ~/.docker/cli-plugins of the OPERATOR's home). Only plugin
     *  symlinks are linked, never config.json (credentials). */
    public static void dockerTools(final String home) throws IOException {
        final var bin = Path.of(home, "bin");
        Files.createDirectories(bin);
        final Path shim = bin.resolve("docker");
        Files.copy(Objects.requireNonNull(DockerService.class.getResourceAsStream("/docker_shim.sh"), "docker_shim.sh resource missing"), shim, StandardCopyOption.REPLACE_EXISTING);
        shim.toFile().setExecutable(true);
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

    /** (calls, last call epoch, starts) from the shim's log: one line per call `<iso> <pid> <args>`, `#start`/`#ready` markers */
    public static int[] dockerCalls(Path logPath, int[] out) {   // out = [calls, lastEpochSec(0=none), starts]
        int calls = 0; long last = 0; int starts = 0;
        if (logPath != null && Files.isRegularFile(logPath)) {
            try {
                for (String line : Files.readAllLines(logPath)) {
                    final String[] parts = line.split(" ", 3);
                    if (parts.length < 2) continue;
                    try {
                        final OffsetDateTime ts = OffsetDateTime.parse(parts[0].replace("Z", "+00:00"));
                        if (parts.length > 2 && parts[2].startsWith("#start")) { starts++; continue; }
                        if (parts.length > 2 && parts[2].startsWith("#ready")) continue;
                        calls++;
                        last = ts.toEpochSecond() + ts.getNano() / 1_000_000_000L;
                    } catch (Exception e) { log.debug("could not parse shim log line '{}': {}", line, e.toString()); }
                }
            } catch (IOException e) { log.warn("could not read docker shim log {}: {}", logPath, e.toString()); }
        }
        out[0] = calls; out[1] = (int) Math.min(last, Integer.MAX_VALUE); out[2] = starts;
        return out;
    }

    /** is a docker CLI of this run still running (an attached `docker compose up`, a long build)? In the
     *  Java port the agent's bash processes are children of this JVM, so we see them via ProcessHandle;
     *  the Python original scanned `ps -E` for AB_RUN_ID. */
    public static boolean dockerCliBusy() {
        return ProcessHandle.allProcesses().anyMatch(ph -> ph.info().commandLine()
                .map(c -> c.contains("docker compose") || c.contains("docker build") || c.contains("docker-compose"))
                .orElse(false));
    }

    public static boolean portInUse(final int port, final String host) {
        try (var s = new java.net.Socket(host, port)) { return true; }
        catch (IOException e) { return false; }
    }

    /** one subprocess run with BOTH streams drained concurrently (read-after-waitFor deadlocks
     *  on >64KB: the pipe fills, the child blocks on write, waitFor never returns) */
    public record Proc(int rc, String out, String err) {}
    public static Proc proc(final int timeoutSec, final Path cwd, final Map<String, String> env, final String... cmd) {
        try {
            final var pb = new ProcessBuilder(cmd);
            if (cwd != null) pb.directory(cwd.toFile());
            if (env != null) { pb.environment().clear(); pb.environment().putAll(env); }
            final Process p = pb.start();
            final StringBuilder out = new StringBuilder(), err = new StringBuilder();
            final Thread t1 = drain(p.getInputStream(), out), t2 = drain(p.getErrorStream(), err);
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                t1.join(1000); t2.join(1000);
                return new Proc(124, out.toString(), err.toString() + "\n[timeout " + timeoutSec + "s]");
            }
            t1.join(5000); t2.join(5000);
            return new Proc(p.exitValue(), out.toString(), err.toString());
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
