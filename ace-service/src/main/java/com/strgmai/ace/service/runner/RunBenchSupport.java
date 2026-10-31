package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.docker.DockerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(RunBenchSupport.class);
    private static final ObjectMapper JSON = new ObjectMapper();

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
        // #673: a baseline deployment guarantee (set once, operator-level, in docker-compose.yml),
        // not a per-run configurable knob the way javaHome is above - every task on this benchmark
        // family needs SOME working gradle, so there is nothing for a run to meaningfully override.
        // Read directly, same reasoning Preflight.java's own fatal gate for this uses.
        final String gradleHome = System.getenv("ACE_GRADLE_HOME");
        if (gradleHome != null && !gradleHome.isBlank()) {
            env.put("PATH", Paths.get(gradleHome).resolve("bin") + ":" + env.getOrDefault("PATH", ""));
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
     *  root, committed whole because nothing excluded it). "gradle-dist/" is the same pattern's
     *  next instance, found live: a task that could not get its own Gradle wrapper download
     *  working self-unzipped gradle.zip into gradle-dist/ instead - ~20,900 of its own files (the
     *  entire Gradle 8.5 distribution) landed in that one task's own `files_changed` metric,
     *  swamping its real ~5-file diff and making every other task's own count look suspiciously
     *  tiny by comparison. */
    public static final List<String> VENDORED_PATHS = List.of(
            "build/", ".gradle/", ".gradle-cache/", "target/", "node_modules/", "out/", "dist/", ".idea/", ".toolchains/", "gradle-dist/");

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
        stripNestedGitDirs(ws);
        runGit(ws, "add", "-A");
        runGit(ws, "commit", "-q", "--allow-empty", "-m", tag);
        runGit(ws, "tag", "-f", tag);
        return gitOut(ws, "rev-parse", "HEAD");
    }

    /** #605, found live: an agent scaffolding tool (`gradle init`, `npm init`, etc. - any of these
     *  can auto-initialize a git repo as a convenience when it finds none) can leave a NESTED .git
     *  directory somewhere under the workspace, uncommitted. `git add -A` on the OUTER repo then
     *  treats that path as a submodule reference and - since a submodule needs at least one commit to
     *  record a SHA for - refuses outright ("does not have a commit checked out"), throwing an
     *  IOException with nothing downstream positioned to catch it: it propagates out of snapshot()
     *  (called at EVERY phase boundary) and crashes the entire run, losing however much real,
     *  already-completed task work preceded it - a single ordinary agent action (creating a nested
     *  git repo is routine scaffolding behavior, not a mistake worth penalizing) taking down 18
     *  tasks' worth of progress. Removing just the stray `.git` entry - never the real files sitting
     *  beside it - lets the outer `git add -A` pick up everything the agent actually wrote, same as
     *  if that tool had never touched git at all. Depth-limited and skips VENDORED_PATHS for the same
     *  reason scratchCopy() does: a deep find through a multi-hundred-MB node_modules/.gradle tree on
     *  every single snapshot would be real, avoidable overhead. */
    private static void stripNestedGitDirs(final Path ws) {
        try (var walk = Files.walk(ws, 6)) {
            for (Path p : walk.filter(q -> q.getFileName() != null && q.getFileName().toString().equals(".git")).toList()) {
                if (p.equals(ws.resolve(".git"))) continue;   // the outer repo's own .git - never touch it
                final Path rel = ws.relativize(p);
                if (rel.getNameCount() > 1 && VENDORED_PATHS.stream().anyMatch(v -> rel.getName(0).toString().equals(v.replace("/", "")))) continue;
                try (var inner = Files.walk(p)) {
                    for (Path f : inner.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
                    log.warn("stripped a stray nested git repo at {} before snapshotting {} - an agent scaffolding tool likely auto-initialized it", rel, ws);
                } catch (IOException e) {
                    log.warn("could not strip nested git repo at {}: {}", rel, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("could not scan {} for nested git repos before snapshotting: {}", ws, e.toString());
        }
    }

    /** #265, found live: a task interrupted by an external pause (not the dedicated restart-task
     *  endpoint, which already reverts this via its own git reset) leaves whatever status line it
     *  had already self-reported sitting in docs/PROGRESS.md. The next attempt is a FRESH session -
     *  no memory of writing that line - so it never replaces it, only appends its own elsewhere in
     *  the file (anchored on the PREVIOUS task's own line, not this task's stale one), leaving two
     *  conflicting lines for the same task id with the newest not even necessarily landing last.
     *  Called right before a task's own fresh (non-continuation) attempt begins; a no-op on a
     *  genuinely first attempt, where there is nothing to strip. */
    /** #656-followup/#671-followup2: an agent's own `docker compose up` (started during an EARLIER
     *  task's own implementation/verification work, or by the INTEGRATION stage's own explicit
     *  instruction to confirm `docker compose up` works - see Packs.INTEGRATION_INSTRUCTION, which
     *  never asks it to come back down again) is never torn down on its own - this is a harness
     *  responsibility, not something a prompt asking the agent to remember can reliably guarantee.
     *
     *  <p>The FIRST version of this method (#656-followup) assumed the project would always be
     *  named after the workspace ROOT's own basename ("workspace", since every task workspace is
     *  `.../workspace`) - wrong in practice: an agent that `cd`s into a subdirectory before running
     *  `docker compose up` (e.g. `account-service/`, the Spring project's own dir name) gets THAT
     *  directory's basename as the Compose project name instead, since that is Compose's own
     *  default naming rule (the compose file's containing directory), not anything tied to the task
     *  workspace itself. Found live, still reproducing AFTER that first fix shipped (job 76b308ac,
     *  run svc-20261031-012905): the INTEGRATION stage's own `docker compose up` (from
     *  `workspace/account-service/`, project name "account-service") sat running for over an hour,
     *  host ports 8080 AND 5432 both bound, still there when the oracle's own LATER `docker compose
     *  up` for C2/F5/F6/F7 scoring collided with it ("Error response from daemon") - undetected by
     *  the FIRST sweepAgentCompose() (wrong project name) AND by ComposeChecks' own portInUse(8080)
     *  pre-flight guard (see its own javadoc: that check is ALSO blind to this, a separate bug).
     *
     *  <p>Fixed by discovery instead of name-guessing: `docker compose ls` (the same mechanism
     *  ComposeChecks.sweepStale() already uses for its own, differently-scoped sweep) lists every
     *  compose project's own `ConfigFiles` path - tear down any project whose config file lives
     *  under THIS run's own workspace, regardless of what Compose happened to name it. Never matches
     *  the ACE platform's own compose project (ace-service/ace-ui-vaadin/caddy) - its config file
     *  lives at the operator's own deploy path, never under a run's `/app/data/workspaces/...` tree.
     *
     *  <p>Called at every task boundary (RunBench's own call sites, next to pauseAfterTask's own
     *  between-tasks checkpoint) AND, critically, once more immediately before oracle.score() runs -
     *  the one guaranteed checkpoint that does not depend on every earlier call site having actually
     *  fired (a restart between the last task boundary and scoring would otherwise skip straight
     *  past it) - "ensure infra is ready for the judge" is the harness's own job, not an emergent
     *  property of enough earlier cleanup calls happening to have covered every path. */
    public static void sweepAgentCompose(final Path ws) {
        final DockerService.Sh ls = DockerService.sh(60, "docker", "compose", "ls", "-a", "--format", "json");
        if (ls.rc() != 0) {
            log.warn("sweepAgentCompose: `docker compose ls` failed (rc={}), skipping: {}", ls.rc(), ls.out());
            return;
        }
        try {
            final String wsReal = ws.toRealPath().toString();
            for (final var p : JSON.readTree(ls.out().isEmpty() ? "[]" : ls.out())) {
                final String name = p.path("Name").asText("");
                final String configFiles = p.path("ConfigFiles").asText("");
                if (name.isBlank() || !configFiles.startsWith(wsReal)) continue;
                final DockerService.Sh down = DockerService.sh(120, "docker", "compose", "-p", name, "down", "-v", "--remove-orphans");
                if (down.rc() != 0) log.warn("sweepAgentCompose: `docker compose -p {} down` rc={}: {}", name, down.rc(), down.out());
                else log.info("sweepAgentCompose: tore down stray compose project '{}' ({})", name, configFiles);
            }
        } catch (final Exception e) {
            log.warn("sweepAgentCompose failed for {}: {}", ws, e.toString());
        }
    }

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
                final Path sessionFile = findSessionFile(sessionDir, sessionId);
                final RecoveredCounts recovered = recoverCountsOnTimeout(sessionFile);
                return new ReferenceAgent.SessionResult(name, 124, wallSec, null, null, recovered.turns(), recovered.toolErrors(),
                        recovered.compactions(), sessionFile, start, Instant.now());
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
     *  uses for --continue. Package-visible (#628): Reviews.java's own resume path needs this same
     *  lookup to recover an already-completed review's session file by its deterministic id. */
    static Path findSessionFile(final Path sessionDir, final String sessionId) {
        try (var s = Files.list(sessionDir)) {
            return s.filter(p -> p.getFileName().toString().contains(sessionId) && p.getFileName().toString().endsWith(".jsonl"))
                    .max(Comparator.comparing(RunBenchSupport::lastModifiedOrEpoch))
                    .orElse(null);
        } catch (IOException e) { return null; }
    }

    private static java.nio.file.attribute.FileTime lastModifiedOrEpoch(final Path p) {
        try { return Files.getLastModifiedTime(p); } catch (IOException e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
    }

    /** found live via #355's Stats tab: a timed-out attempt used to report turns/toolErrors/
     *  compactions as hardcoded 0 below - Future.cancel(true) gives no real final state back from
     *  the interrupted thread, so those three fields were always synthetic placeholders, never a
     *  real measurement, even on a session that genuinely ran for hours (T7, #351: 142 turns, 51
     *  compactions, 6 tool errors, all reported as 0). The session's own file already has
     *  everything genuinely written before the cut (AgentSession.write() appends per turn, not
     *  buffered) - recovered the same way Collect.compactionOverhead()/BenchController.
     *  toolCallCounts() already read a session file directly rather than trusting a summary field. */
    record RecoveredCounts(int turns, int toolErrors, int compactions) {
        static final RecoveredCounts NONE = new RecoveredCounts(0, 0, 0);
    }

    /** Best-effort: a missing/unreadable session file (a timeout before the very first write ever
     *  happened, or an I/O failure) falls back to RecoveredCounts.NONE - no worse than the old
     *  hardcoded-zero behavior, just no longer wrong whenever a real session file exists to
     *  recover from. One bad line is skipped, not fatal to the whole count (same resilience
     *  convention as every other best-effort session-file reader in this codebase). */
    static RecoveredCounts recoverCountsOnTimeout(final Path sessionFile) {
        if (sessionFile == null || !Files.isRegularFile(sessionFile)) return RecoveredCounts.NONE;
        final var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        int turns = 0, toolErrors = 0, compactions = 0;
        try {
            for (final String line : Files.readAllLines(sessionFile)) {
                if (line.isBlank()) continue;
                final Map<String, Object> e;
                try { e = mapper.readValue(line, Map.class); } catch (final Exception ignore) { continue; }
                final String type = String.valueOf(e.get("type"));
                if ("compaction".equals(type)) { compactions++; continue; }
                if (!"message".equals(type) || !(e.get("message") instanceof Map<?, ?> m)) continue;
                final Object role = m.get("role");
                if ("assistant".equals(role)) turns++;
                else if ("toolResult".equals(role) && Boolean.TRUE.equals(m.get("isError"))) toolErrors++;
            }
        } catch (final Exception e) { return RecoveredCounts.NONE; }
        return new RecoveredCounts(turns, toolErrors, compactions);
    }
}
