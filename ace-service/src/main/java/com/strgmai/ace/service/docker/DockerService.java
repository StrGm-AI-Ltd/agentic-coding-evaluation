package com.strgmai.ace.service.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    /** the operator's default VM memory cap when a run doesn't pin its own - conservative enough to
     *  leave room for a local model server sharing the same host (the actual root cause behind
     *  Docker Desktop's VM competing for memory at the worst possible moment). */
    public static final int DEFAULT_MEMORY_MIB = 4096;
    private static final Path DOCKER_SETTINGS_STORE = Path.of(System.getProperty("user.home"), "Library/Group Containers/group.com.docker/settings-store.json");
    private static final Path DOCKER_SETTINGS_LEGACY = Path.of(System.getProperty("user.home"), "Library/Group Containers/group.com.docker/settings.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    /** found live 2026-09-28: opening ~/Library/Group Containers/group.com.docker/settings-store.json
     *  from a launchd-submitted (non-interactive) process hung INDEFINITELY on this host - a macOS
     *  privacy/TCC permission gate on Group Containers access apparently blocks native file I/O
     *  waiting on a UI prompt a headless job never sees. Every --manage-docker run hung before ever
     *  creating a journal. Bounds the whole operation so a stuck permission check can never hang a
     *  run - it degrades to "no cap applied" instead. */
    private static final int MEMORY_CAP_TIMEOUT_SEC = 15;

    /** cheap, daemon-free: is Docker Desktop's backend alive right now */
    public static boolean dockerRunning() {
        return sh(8, "pgrep", "-f", "com.docker.backend").rc == 0;
    }

    /** start Docker Desktop and wait for the daemon, first capping its VM memory when desiredMemoryMib
     *  is given (null skips it entirely - the run isn't managing this knob). Relaunches on a fixed
     *  cadence rather than once at the halfway mark, and relaunches IMMEDIATELY - rather than waiting
     *  out the rest of the interval - when the backend process is gone entirely (a crash mid-boot), as
     *  opposed to merely still booting (its VM's cold start under memory pressure from a co-resident
     *  model server is expected to take a while, not a reason to kill and retry). */
    public static boolean dockerUp(final int timeoutSec, final Integer desiredMemoryMib) {
        if (desiredMemoryMib != null) applyMemoryCap(desiredMemoryMib);
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

    /** caps Docker Desktop's VM memory for whenever it NEXT starts - by dockerUp() below, or by the
     *  agent's own shim during task work (docker_shim.sh, a separate launch path this class doesn't
     *  control). If Desktop happens to already be running under a DIFFERENT value, kills it now so
     *  the very first real start of this run already reflects the cap, rather than leaving a stale
     *  VM running - uncapped - until dockerUp()'s own explicit call much later in the run (oracle
     *  scoring), by which point the agent's own task work already ran under the old setting.
     *
     *  Path RESOLUTION (Files.exists()) stays OUTSIDE the bound below - it did not hang on the host
     *  where this was found live, only the actual file OPEN/read did (confirmed via a thread dump:
     *  stuck in FileInputStream.open0, not Files.exists). Bounded to MEMORY_CAP_TIMEOUT_SEC (see its
     *  javadoc): a stuck settings-file read must never hang the run this is meant to make MORE
     *  reliable, not less. Deliberately NOT try-with-resources on the executor -
     *  ExecutorService.close() awaits termination (repeatedly, up to a day at a time) before
     *  returning, which would just move the hang here instead of removing it. A timed-out task's
     *  virtual thread is abandoned via shutdownNow() (non-blocking - does not wait for it), not
     *  actually freed - native blocking file I/O does not respond to Thread.interrupt() - but that is
     *  one leaked (daemon-by-default) virtual thread per occurrence, an acceptable trade against
     *  hanging the whole run indefinitely. */
    public static void applyMemoryCap(final int desiredMib) {
        final Path settings = Files.exists(DOCKER_SETTINGS_STORE) ? DOCKER_SETTINGS_STORE
                : Files.exists(DOCKER_SETTINGS_LEGACY) ? DOCKER_SETTINGS_LEGACY : null;
        if (settings == null) {
            log.warn("Docker Desktop settings file not found at {} or {} - cannot cap its VM memory (has it ever been launched?)", DOCKER_SETTINGS_STORE, DOCKER_SETTINGS_LEGACY);
            return;
        }
        applyMemoryCap(settings, desiredMib, MEMORY_CAP_TIMEOUT_SEC);
    }

    /** the actual bounded read/edit/write + conditional restart, taking the settings file and
     *  timeout explicitly so it's unit-testable against a blocking file (a FIFO) with a SHORT
     *  timeout, instead of waiting out the real MEMORY_CAP_TIMEOUT_SEC on every test run. */
    static void applyMemoryCap(final Path settings, final int desiredMib, final int timeoutSec) {
        final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        try {
            exec.submit(() -> {
                if (ensureMemoryCap(settings, desiredMib) && dockerRunning()) {
                    sh(10, "pkill", "-9", "-f", "com.docker.backend");
                    sleep(5);
                }
            }).get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("capping Docker Desktop's VM memory did not complete within {}s - some macOS " +
                    "configurations gate ~/Library/Group Containers access behind a permission prompt " +
                    "a headless process never sees; continuing WITHOUT a cap rather than hang the run", timeoutSec);
        } catch (Exception e) {
            log.warn("could not cap Docker Desktop's VM memory: {}", e.toString());
        } finally {
            exec.shutdownNow();
        }
    }

    /** the actual read/edit/write, taking the settings file explicitly so it's unit-testable against
     *  a temp file instead of touching this host's real Docker Desktop configuration. Leaves every
     *  other setting (cpus, disk size, file sharing, ...) untouched. Returns whether the file
     *  actually changed - the caller must force a restart if Docker was already running under the
     *  old value, since it won't pick up a settings-file edit on its own. */
    static boolean ensureMemoryCap(final Path settings, final int desiredMib) {
        try {
            final var node = (ObjectNode) JSON.readTree(settings.toFile());
            if (node.path("memoryMiB").asInt(-1) == desiredMib) return false;
            node.put("memoryMiB", desiredMib);
            Files.writeString(settings, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            return true;
        } catch (IOException e) {
            log.warn("could not cap Docker Desktop's VM memory via {}: {}", settings, e.toString());
            return false;
        }
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
