package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ReferenceAgent;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Ports of run_bench.py's process-level machinery: the single-run lock (two runs would share the
 *  model server and fight over Docker), git workspace snapshots (the commit SHAs live OUTSIDE the
 *  workspace; the bundle is P3's evidence) and the scrubbed environment (PATH/LANG/TMPDIR only, no
 *  operator tokens, a pinned JAVA_HOME first on PATH). run_bench.py's kill_tree was deliberately NOT
 *  ported: Java's ProcessHandle.Info exposes only command()/commandLine()/arguments(), never the
 *  process environment, so its AB_RUN_ID sweep could never match; descendants() covers the process tree. */
public final class RunBenchSupport {
    private RunBenchSupport() {}

    /** one benchmark run at a time on this machine */
    public static FileChannel acquireLock(final Path lockPath) {
        try {
            Files.createDirectories(lockPath.toAbsolutePath().getParent());
            final FileChannel ch = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (ch.tryLock() == null) {
                ch.close();
                throw new IllegalStateException("another run holds " + lockPath + "; refusing to start a concurrent run");
            }
            return ch;
        } catch (IOException e) { throw new IllegalStateException("cannot lock " + lockPath + ": " + e, e); }
    }

    public static Map<String, String> scrubbedEnv(final String home, final String runId, final String javaHome) {
        final Map<String, String> env = new LinkedHashMap<>();
        for (String k : List.of("PATH", "LANG", "LC_ALL", "TERM", "TMPDIR", "SHELL")) {   // PATH/LANG/TMPDIR only - no operator tokens
            final String v = System.getenv(k);
            if (v != null) env.put(k, v);
        }
        env.put("HOME", home);
        env.put("USER", "bench");
        env.put("CI", "1");
        env.put("NO_COLOR", "1");
        env.put("TERM", "dumb");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("ACE_RUN_ID", runId);
        if (javaHome != null && !javaHome.isBlank()) {   // a PINNED JAVA_HOME first on PATH (R4 C-2)
            env.put("JAVA_HOME", javaHome);
            env.put("PATH", Paths.get(javaHome).resolve("bin") + ":" + env.getOrDefault("PATH", ""));
        }
        env.put("PATH", Paths.get(home, "bin") + ":" + env.getOrDefault("PATH", ""));   // the docker shim first (R7)
        env.put("ACE_DOCKER_LOG", Paths.get(home, "docker-calls.log").toString());
        return env;
    }

    /** commit + tag; returns the commit SHA. Build outputs never enter a snapshot. */
    public static String snapshot(final Path ws, final String tag) throws IOException, InterruptedException {
        if (!Files.exists(ws.resolve(".git"))) {   // a worktree has a .git FILE pointing at the main repo: it is already initialised
            runGit(ws, "init", "-q");
            runGit(ws, "config", "user.email", "bench@local");
            runGit(ws, "config", "user.name", "bench");
            Files.createDirectories(ws.resolve(".git/info"));
            Files.writeString(ws.resolve(".git/info/exclude"),
                    "build/\n.gradle/\n.gradle-cache/\ntarget/\nnode_modules/\nout/\ndist/\n.idea/\n");
            Files.writeString(ws.resolve(".git/info/attributes"), "docs/PROGRESS.md merge=union\nhandoff/*.md merge=union\n");
        }
        runGit(ws, "add", "-A");
        runGit(ws, "commit", "-q", "--allow-empty", "-m", tag);
        runGit(ws, "tag", "-f", tag);
        return gitOut(ws, "rev-parse", "HEAD");
    }

    /** does this phase tag already exist in ws's history? A tag is only ever written AFTER its
     *  phase's session call returns normally (see snapshot() call sites in RunBench) - its presence
     *  means that phase genuinely finished in a PRIOR invocation of this same run, not merely that
     *  it was attempted (a crash mid-session never reaches the snapshot call). Used to resume past
     *  already-completed work instead of redoing it from a blank session after the service restarts
     *  mid-run and the job gets requeued (R18). false, not an exception, on a genuinely fresh ws. */
    public static boolean tagExists(final Path ws, final String tag) {
        if (!Files.exists(ws.resolve(".git"))) return false;
        try {
            final Process p = git(ws, "rev-parse", "--verify", "--quiet", "refs/tags/" + tag);
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) { return false; }
    }

    public static String gitOut(final Path ws, final String... args) throws IOException, InterruptedException {
        final Process p = git(ws, args);
        final var out = new String(p.getInputStream().readAllBytes()).strip();
        if (p.waitFor() != 0) throw new IOException("git " + args[0] + " failed: " + new String(p.getErrorStream().readAllBytes()));   // like runGit, don't return partial output on failure
        return out;
    }

    static void runGit(final Path ws, final String... args) throws IOException, InterruptedException {
        final Process p = git(ws, args);
        if (p.waitFor() != 0) throw new IOException("git " + args[0] + " failed: " + new String(p.getErrorStream().readAllBytes()));
    }

    private static Process git(final Path ws, final String... args) throws IOException {
        final List<String> cmd = new ArrayList<>(List.of("git", "-C", ws.toString()));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).start();
    }

    public static Optional<String> javaHome() {
        final String jh = System.getenv("AB_JAVA_HOME");
        if (jh != null && Files.isRegularFile(Paths.get(jh, "bin/java"))) return Optional.of(jh);
        return Optional.empty();
    }

    public static String nowIso() { return Instant.now().toString(); }
    public static long secondsSince(Instant t0) { return Duration.between(t0, Instant.now()).getSeconds(); }

    /** Runs one agent call on its own virtual thread and enforces wallSec PREEMPTIVELY - even
     *  mid-request, not just between turns. The run's own task-wall budget (the run's own params,
     *  R4) is meant to be the only time limit a session needs: ReferenceAgent no longer enforces any
     *  wall budget or mid-stream idle timeout of its own (see its awaitStream/run javadoc - removed
     *  2026-09-25 after the fixed mid-stream idle ceiling there was found firing on oMLX's own
     *  legitimate memory-pressure throttling, discarding real partial generation and restarting from
     *  scratch each time). Every direct agent.run() call site (RunBench's main/continue/wrapup task
     *  sessions, its handoff and PARALLEL_PLAN sessions, and Reviews' reviewer sessions) goes through
     *  this - none of them get task-wall enforcement any other way now.
     *  On timeout: names the reason on abortProxy BEFORE cancelling (best-effort - the interrupted
     *  call's own chatWithRetry catch block may also write its own, vaguer reason to the same
     *  RecordingProxy field concurrently; a benign, diagnostic-only race, not correctness-affecting),
     *  cancels the agent's thread (interrupting it - awaitStream's InterruptedException path fires),
     *  and reports rc=124 exactly as ReferenceAgent's own wall-budget exit used to. */
    public static ReferenceAgent.SessionResult runBounded(final long wallSec, final String name, final Path sessionDir,
                                                            final String sessionId, final Consumer<String> abortProxy,
                                                            final Callable<ReferenceAgent.SessionResult> call) throws Exception {
        final Instant start = Instant.now();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<ReferenceAgent.SessionResult> future = exec.submit(call);
            try {
                return future.get(wallSec, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                abortProxy.accept("task-wall budget exceeded (" + wallSec + "s)");
                future.cancel(true);
                return new ReferenceAgent.SessionResult(name, 124, wallSec, null, 0, 0, 0,
                        findSessionFile(sessionDir, sessionId), start, Instant.now());
            } catch (ExecutionException ee) {
                if (ee.getCause() instanceof Exception e) throw e;
                throw new RuntimeException(ee.getCause());
            }
        }
    }

    /** best-effort: a timed-out attempt's session file already has whatever it wrote before being
     *  cut off (AgentSession.write() appends per turn, not buffered), so the manifest's session_id
     *  for a timed-out task still points at real, if partial, data. Same lookup AgentSession itself
     *  uses for --continue. */
    private static Path findSessionFile(final Path sessionDir, final String sessionId) {
        try (var s = Files.list(sessionDir)) {
            return s.filter(p -> p.getFileName().toString().contains(sessionId) && p.getFileName().toString().endsWith(".jsonl"))
                    .max(Comparator.comparing(RunBenchSupport::lastModifiedOrEpoch))
                    .orElse(null);
        } catch (IOException e) { return null; }
    }

    private static java.nio.file.attribute.FileTime lastModifiedOrEpoch(final Path p) {
        try { return Files.getLastModifiedTime(p); } catch (IOException e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
    }
}
