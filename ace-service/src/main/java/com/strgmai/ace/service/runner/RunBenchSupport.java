package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.docker.DockerService;

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
        // a daemon left running for the whole task session is a sustained, idle memory footprint
        // competing with the model server on the same host (found live - it lined up with an
        // empty-stream stutter #256 was filed for); VerifyTask/BuildChecks already disable it for
        // their own gradle calls the same way, this just extends that to the agent's own bash tool
        env.put("GRADLE_OPTS", "-Dorg.gradle.daemon=false");
        return env;
    }

    /** Never the agent's own work - downloaded/generated tooling, not source. Shared between the
     *  git exclude list below (so none of this ever enters a snapshot in the first place) and
     *  BenchController.taskDiff()'s own display-time filter (for whatever slipped in on a
     *  workspace initialised before a given entry was added here - #260, found live: a full
     *  unpacked Eclipse Temurin JDK distribution landed at .toolchains/ straight in the project
     *  root, committed whole because nothing excluded it). */
    public static final List<String> VENDORED_PATHS = List.of(
            "build/", ".gradle/", ".gradle-cache/", "target/", "node_modules/", "out/", "dist/", ".idea/", ".toolchains/");

    /** commit + tag; returns the commit SHA. Build outputs never enter a snapshot. */
    public static String snapshot(final Path ws, final String tag) throws IOException, InterruptedException {
        if (!Files.exists(ws.resolve(".git"))) {   // a worktree has a .git FILE pointing at the main repo: it is already initialised
            runGit(ws, "init", "-q");
            runGit(ws, "config", "user.email", "bench@local");
            runGit(ws, "config", "user.name", "bench");
            Files.createDirectories(ws.resolve(".git/info"));
            Files.writeString(ws.resolve(".git/info/exclude"), String.join("\n", VENDORED_PATHS) + "\n");
            Files.writeString(ws.resolve(".git/info/attributes"), "docs/PROGRESS.md merge=union\nhandoff/*.md merge=union\n");
        }
        runGit(ws, "add", "-A");
        runGit(ws, "commit", "-q", "--allow-empty", "-m", tag);
        runGit(ws, "tag", "-f", tag);
        return gitOut(ws, "rev-parse", "HEAD");
    }

    /** #265, found live: a task interrupted by an external pause (not the dedicated restart-task
     *  endpoint, which already reverts this via its own git reset) leaves whatever status line it
     *  had already self-reported sitting in docs/PROGRESS.md. The next attempt is a FRESH session -
     *  no memory of writing that line - so it never replaces it, only appends its own elsewhere in
     *  the file (anchored on the PREVIOUS task's own line, not this task's stale one), leaving two
     *  conflicting lines for the same task id with the newest not even necessarily landing last.
     *  Called right before a task's own fresh (non-continuation) attempt begins; a no-op on a
     *  genuinely first attempt, where there is nothing to strip. */
    public static void clearStaleProgressLine(final Path ws, final String taskId) throws IOException {
        final Path progress = ws.resolve("docs/PROGRESS.md");
        if (!Files.isRegularFile(progress)) return;
        final List<String> lines = Files.readAllLines(progress);
        final List<String> kept = lines.stream().filter(l -> !l.startsWith(taskId + " | ")).toList();
        if (kept.size() != lines.size()) Files.write(progress, kept);
    }

    /** does this phase tag already exist in ws's history? A tag is only ever written AFTER its
     *  phase's session call returns normally (see snapshot() call sites in RunBench) - its presence
     *  means that phase genuinely finished in a PRIOR invocation of this same run, not merely that
     *  it was attempted (a crash mid-session never reaches the snapshot call). Used to resume past
     *  already-completed work instead of redoing it from a blank session after the service restarts
     *  mid-run and the job gets requeued (R18). false, not an exception, on a genuinely fresh ws. */
    public static boolean tagExists(final Path ws, final String tag) {
        if (!Files.exists(ws.resolve(".git"))) return false;
        // #292: DockerService.sh/proc - not a bare ProcessBuilder.start()+waitFor() - drains both
        // streams concurrently AND enforces a timeout; see this method's own class-level finding.
        return DockerService.sh(20, "git", "-C", ws.toString(),
                "rev-parse", "--verify", "--quiet", "refs/tags/" + tag).rc() == 0;
    }

    /** the commit SHA of the nearest phase/* tag reachable from ref - the correct "state right
     *  before this phase" boundary, NOT ref's immediate git-parent (#260: the agent's own session
     *  can `git commit` its own work before snapshot() ever runs, so a plain git-parent walk can
     *  land on the agent's own commit instead of the true phase boundary - `git describe` walks
     *  past any such untagged commits to the nearest ACTUAL phase/* tag). Used by taskDiff()'s
     *  before/after boundary and by the task-restart flow's own reset-to-before-this-task point.
     *
     *  The run's very first phase has no EARLIER phase/* tag to find - the only tag that early is
     *  run/start, deliberately named outside the phase/* namespace (see snapshot()'s own comment)
     *  so it can never be mistaken for a real phase boundary elsewhere. `git describe` legitimately
     *  finds nothing in that case - fall back to run/start itself instead of letting that surface
     *  as a raw failure on the Definition task's own Changes tab. Found live with two different
     *  git error wordings for the same underlying condition: "No names found, cannot describe
     *  anything" when NO phase/* tag exists anywhere yet (ref is mid-Definition, still running),
     *  and "No tags can describe '<sha>'. Try --always, ..." once a LATER phase/* tag exists
     *  elsewhere in the repo but isn't an ancestor of this particular ref (ref is phase/p0^ itself,
     *  the commit right before Definition's own tag - the scenario actually seen live). */
    public static String nearestPhaseTagSha(final Path ws, final String ref) throws IOException, InterruptedException {
        final var cmd = new ArrayList<>(List.of("git", "-C", ws.toString(), "describe", "--tags", "--match", "phase/*", "--abbrev=0", ref));
        final var r = DockerService.proc(60, null, null, cmd.toArray(new String[0]));
        if (r.rc() != 0) {
            if (r.err().contains("No tags can describe") || r.err().contains("No names found")) return gitOut(ws, "rev-parse", "run/start");
            throw new IOException("git describe failed: " + r.err());
        }
        return gitOut(ws, "rev-parse", r.out().strip());
    }

    /** #292, found live: this - and runGit() below - used to be a bare `new ProcessBuilder(cmd).start()`
     *  with neither stream drained before waitFor(), unlike every OTHER subprocess invocation in
     *  this codebase (AgentTools.bash(), DockerService.proc() itself, VerifyTask.verify() - all with
     *  an explicit "drain concurrently or a read-after-wait deadlocks the pipe" comment). snapshot()
     *  calls runGit() (via these methods) at EVERY phase/task boundary of EVERY run - `git add -A`
     *  over an agent-controlled workspace can legitimately produce enough stdout (CRLF warnings
     *  across many files, a stray large file before .gitignore catches it) to fill the OS pipe
     *  buffer and block the child on write, hanging this thread forever with no timeout to recover.
     *  Routed through DockerService.sh(), which already drains both streams concurrently and
     *  enforces a bound, rather than maintaining a second, unguarded git-invocation path. */
    public static String gitOut(final Path ws, final String... args) throws IOException, InterruptedException {
        // proc(), not sh(): sh() merges stdout+stderr into one string, but callers of gitOut() rely
        // on getting back ONLY stdout on success (e.g. a bare commit SHA) - git occasionally writes
        // advisory text to stderr even on a successful (rc=0) call, which merging would corrupt.
        final List<String> cmd = new ArrayList<>(List.of("git", "-C", ws.toString()));
        cmd.addAll(List.of(args));
        final var r = DockerService.proc(60, null, null, cmd.toArray(new String[0]));
        if (r.rc() != 0) throw new IOException("git " + args[0] + " failed: " + r.err());   // like runGit, don't return partial output on failure
        return r.out().strip();
    }

    static void runGit(final Path ws, final String... args) throws IOException, InterruptedException {
        final List<String> cmd = new ArrayList<>(List.of("git", "-C", ws.toString()));
        cmd.addAll(List.of(args));
        final var r = DockerService.proc(60, null, null, cmd.toArray(new String[0]));
        if (r.rc() != 0) throw new IOException("git " + args[0] + " failed: " + r.err());
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
     *  and reports rc=124 exactly as ReferenceAgent's own wall-budget exit used to.
     *
     *  #222 (found live: a cancelled job stuck "running" for 6+ hours): an EXTERNAL interrupt - the
     *  cancel-watch thread's currentJobFuture.cancel(true), unrelated to this session's own wallSec -
     *  landing on the outer thread while it is blocked in future.get() throws InterruptedException,
     *  not TimeoutException. Left uncaught, the inner future is never itself cancelled, so the
     *  try-with-resources's own implicit close()/awaitTermination() then waits forever for a task
     *  nothing ever told to stop. Unlike a timeout (this session's own budget, expected to let the
     *  run continue to its next phase), an external interrupt means the WHOLE job is being torn
     *  down - cancel the inner future the same way, then propagate (not swallow) so the caller chain
     *  unwinds all the way to WorkerService's own cancellation handling instead of silently moving
     *  on to the next task. */
    public static ReferenceAgent.SessionResult runBounded(final long wallSec, final String name, final Path sessionDir,
                                                            final String sessionId, final Consumer<String> abortProxy,
                                                            final Callable<ReferenceAgent.SessionResult> call) throws Exception {
        final Instant start = Instant.now();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<ReferenceAgent.SessionResult> future = exec.submit(call);
            try {
                // wallSec <= 0 means unlimited (the system-wide "0 = no budget" convention) -
                // future.get(0, SECONDS) would mean "timeout immediately", the opposite of that
                return wallSec > 0 ? future.get(wallSec, TimeUnit.SECONDS) : future.get();
            } catch (TimeoutException te) {
                abortProxy.accept("task-wall budget exceeded (" + wallSec + "s)");
                future.cancel(true);
                return new ReferenceAgent.SessionResult(name, 124, wallSec, null, 0, 0, 0,
                        findSessionFile(sessionDir, sessionId), start, Instant.now());
            } catch (InterruptedException ie) {
                abortProxy.accept("cancelled");
                future.cancel(true);
                throw ie;
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
