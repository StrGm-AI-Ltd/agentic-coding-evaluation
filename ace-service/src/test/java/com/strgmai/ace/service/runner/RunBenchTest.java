package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.docker.DockerService;
import com.strgmai.ace.service.oracle.RunOracle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The bug behind job #14 (and every other real task) failing with
 *  "java.nio.file.NoSuchFileException: .../task/PROMPT.md": the port read task/PROMPT.md as a
 *  filesystem path relative to the repo root, a file that was never vendored into this repo at all
 *  (nor would a single generic file have been correct - run_bench.py prefers each task's OWN prompt,
 *  tasks/&lt;task&gt;/PROMPT.md, first). Fixed by vendoring the prompts as classpath resources
 *  (matching how tasks/ladder.json already works) and preferring the task-specific one. */
class RunBenchTest {

    // the temp workspace dir(s) leak across runs - track and delete them recursively
        private final Set<Path> temps = new HashSet<>();

    private Path track(Path p) { temps.add(p); return p; }

    @AfterEach
    void cleanUp() throws IOException {
        for (Path p : temps)
            if (p != null && Files.exists(p)) {
                try (var s = Files.walk(p)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignore) {} });
                }
            }
        temps.clear();
    }


    private static RunBench runBench() {
        return runBench(mock(BenchProperties.class));
    }

    private static RunBench runBench(BenchProperties props) {
        return new RunBench(props, mock(ReferenceAgent.class), mock(RecordingProxyFactory.class),
                mock(RunOracle.class), mock(Reviews.class), mock(ContextProbe.class));
    }

    @Test
    void promptResourcePrefersTheTaskSOwnPrompt() throws Exception {
        try (InputStream in = runBench().promptResource("L3p_point_in_time")) {
            assertNotNull(in, "tasks/L3p_point_in_time/PROMPT.md must be vendored as a resource");
            final var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("point-in-time") || text.contains("point in time") || text.length() > 100,
                    "a real, task-specific prompt, not empty: " + text.substring(0, Math.min(200, text.length())));
        }
    }

    @Test
    void promptResourceFallsBackToTheGenericDefaultForAnUnknownTask() throws Exception {
        try (InputStream in = runBench().promptResource("no-such-task")) {
            assertNotNull(in, "tasks/PROMPT.md (the generic default) must be vendored as a resource");
        }
    }

    /** Job #26's failure, hidden behind #14's until that one was fixed: Files.copy's TARGET
     *  directory (ws/task) must exist too, not just its parent (ws) -
     *  Files.createDirectories(ws.resolve("task").getParent()) created only ws. */
    @Test
    void setUpTaskPromptCreatesTheTaskDirectoryItselfNotJustItsParent() throws Exception {
        Path ws = track(Files.createTempDirectory("ws"));   // a bare, empty workspace dir - nothing pre-created under it

        final String text = runBench().setUpTaskPrompt(ws, "L3p_point_in_time");

        assertTrue(Files.isRegularFile(ws.resolve("task/PROMPT.md")), "task/PROMPT.md must exist under the workspace");
        assertFalse(text.isBlank());
    }

    @Test
    void everyLadderTaskWithAKnownPromptResolvesToItsOwnFile() throws Exception {
        // every rung except L7_full_platform has its own PROMPT.md in the Python original (verified
        // against ~/Documents/repo/agentbench-trading-service/tasks/*/PROMPT.md) - each must resolve
        // to ITS OWN content here too, not silently share the generic default
        for (String task : new String[]{"L1_migration_entity", "L2_one_endpoint", "L3_point_in_time",
                "L3p_point_in_time", "L4_state_machine", "L5_second_service", "L6_compose_health"}) {
            try (InputStream perTask = runBench().promptResource(task);
                 InputStream generic = RunBenchTest.class.getResourceAsStream("/tasks/PROMPT.md")) {
                assertNotNull(perTask, task + " must resolve to a vendored prompt");
                assertNotNull(generic, "the generic tasks/PROMPT.md must be vendored");   // a missing resource is a clear message, not an NPE
                final var taskText = new String(perTask.readAllBytes(), StandardCharsets.UTF_8);
                final var genericText = new String(generic.readAllBytes(), StandardCharsets.UTF_8);
                assertNotEquals(genericText, taskText, task + " must not silently fall back to the generic prompt");
            }
        }
    }

    /** #173: --impl-wall (cfg's "impl_wall_sec") matches a monolithic arm's single implementation
     *  session budget to an orchestrated arm's N-tasks x per-task budget (design rule 10) - it must
     *  win over the rung's own impl_sec default whenever the run explicitly set one. */
    @Test
    void implWallPrefersTheExplicitCfgOverrideOverTheRungDefault() {
        final var rung = Map.of("impl_sec", 900);
        assertEquals(3600, RunBench.implWall(Map.of("impl_wall_sec", 3600), rung));
    }

    @Test
    void implWallFallsBackToTheRungDefaultWhenNotSetOnTheRun() {
        final var rung = Map.of("impl_sec", 900);
        assertEquals(900, RunBench.implWall(Map.of(), rung));
    }

    @Test
    void implTokensPrefersTheExplicitCfgOverrideOverTheOperatorDefault() {
        final BenchProperties props = mock(BenchProperties.class);
        when(props.phaseTokens("p2_implementation")).thenReturn(5000);
        assertEquals(50000L, RunBench.implTokens(Map.of("impl_tokens", 50000L), "p2_implementation", props));
    }

    @Test
    void implTokensFallsBackToTheOperatorWideDefaultWhenNotSetOnTheRun() {
        final BenchProperties props = mock(BenchProperties.class);
        when(props.phaseTokens("p2_implementation")).thenReturn(5000);
        assertEquals(5000L, RunBench.implTokens(Map.of(), "p2_implementation", props));
    }

    /** #228: --plan-tokens (cfg's "plan_tokens") is the p0_definition/p1_plan counterpart to
     *  implTokens() above. */
    @Test
    void planTokensPrefersTheExplicitCfgOverrideOverTheOperatorDefault() {
        final BenchProperties props = mock(BenchProperties.class);
        when(props.phaseTokens("p1_plan")).thenReturn(3000);
        assertEquals(9000L, RunBench.planTokens(Map.of("plan_tokens", 9000L), "p1_plan", props));
    }

    @Test
    void planTokensFallsBackToTheOperatorWideDefaultWhenNotSetOnTheRun() {
        final BenchProperties props = mock(BenchProperties.class);
        when(props.phaseTokens("p1_plan")).thenReturn(3000);
        assertEquals(3000L, RunBench.planTokens(Map.of(), "p1_plan", props));
    }

    /** #228: --wall-budget (cfg's "wall_budget_override") - a smoke-test knob that overrides every
     *  phase's wall uniformly. */
    @Test
    void wallBudgetOverrideReadsTheCfgValueWhenPresent() {
        assertEquals(300, RunBench.wallBudgetOverride(Map.of("wall_budget_override", 300)));
    }

    @Test
    void wallBudgetOverrideIsNullWhenUnset() {
        assertNull(RunBench.wallBudgetOverride(Map.of()));
    }

    /** Found live 2026-10-03: --phases (cfg's "phases") restricts a rung's own multi-phase sequence
     *  to an explicit subset, matching queue.py's phases_wanted filter in run_once(). */
    @Test
    void phasesWantedReturnsEveryRungPhaseWhenUnset() {
        final var rungPhases = List.of("p0_definition", "p1_plan", "p2_implementation");
        assertEquals(rungPhases, RunBench.phasesWanted(Map.of(), rungPhases));
        assertEquals(rungPhases, RunBench.phasesWanted(Map.of("phases", ""), rungPhases));
    }

    @Test
    void phasesWantedFiltersDownToTheChosenSubsetInRungOrder() {
        final var rungPhases = List.of("p0_definition", "p1_plan", "p2_implementation");
        assertEquals(List.of("p1_plan", "p2_implementation"),
                RunBench.phasesWanted(Map.of("phases", "p2_implementation,p1_plan"), rungPhases));
    }

    @Test
    void phasesWantedYieldsAnEmptyIntersectionWhenTheChosenPhaseIsNotOneOfTheRungSOwn() {
        // matches queue.py's own filter exactly: an intersection, not an error, for a phase outside this rung
        assertEquals(List.of(), RunBench.phasesWanted(Map.of("phases", "p0_definition"), List.of("implement")));
    }

    /** orchestrated mode always runs p1_plan/p2_implementation on a rung that defines them - --phases
     *  excluding either fails loudly instead of silently being ignored. */
    @Test
    void requirePlanAndImplementationPhasesRejectsExcludingEitherOnAnEligibleRung() {
        final var rungPhases = List.of("p0_definition", "p1_plan", "p2_implementation");
        assertThrows(IllegalStateException.class,
                () -> RunBench.requirePlanAndImplementationPhases(rungPhases, List.of("p0_definition", "p2_implementation"), "L7_full_platform"));
        assertThrows(IllegalStateException.class,
                () -> RunBench.requirePlanAndImplementationPhases(rungPhases, List.of("p0_definition", "p1_plan"), "L7_full_platform"));
    }

    @Test
    void requirePlanAndImplementationPhasesAllowsExcludingOnlyP0Definition() {
        final var rungPhases = List.of("p0_definition", "p1_plan", "p2_implementation");
        assertDoesNotThrow(() -> RunBench.requirePlanAndImplementationPhases(rungPhases, List.of("p1_plan", "p2_implementation"), "L7_full_platform"));
    }

    @Test
    void requirePlanAndImplementationPhasesIsANoOpForARungThatNeverHadThemAtAll() {
        // phasesFor() falls back to ["implement"] for every non-L7/L3p rung - unaffected either way
        assertDoesNotThrow(() -> RunBench.requirePlanAndImplementationPhases(List.of("implement"), List.of(), "L1_migration_entity"));
    }

    /** Found live 2026-09-25: oMLX's own memory-pressure throttling was slow enough to trip the
     *  agent's 180s stall detector, and oMLX's log - the only place that explained why - spans
     *  every run on the machine and keeps growing. Each run now copies its own slice out. */
    @Test
    void captureOmlxLogDoesNothingWhenNotConfigured() throws Exception {
        final BenchProperties props = mock(BenchProperties.class);
        when(props.omlxServerLog()).thenReturn(null);
        final Path rd = track(Files.createTempDirectory("rd"));

        runBench(props).captureOmlxLog(rd, Map.of("started", "2026-09-25T07:00:00Z", "ended", "2026-09-25T08:00:00Z"));

        assertFalse(Files.exists(rd.resolve("omlx_server.log")), "no config = no capture, not a failed capture");
    }

    @Test
    void captureOmlxLogDoesNothingWhenTheConfiguredFileIsMissing() throws Exception {
        final BenchProperties props = mock(BenchProperties.class);
        when(props.omlxServerLog()).thenReturn("/no/such/omlx-log-for-this-test.log");
        final Path rd = track(Files.createTempDirectory("rd"));

        assertDoesNotThrow(() -> runBench(props).captureOmlxLog(rd,
                Map.of("started", "2026-09-25T07:00:00Z", "ended", "2026-09-25T08:00:00Z")));
        assertFalse(Files.exists(rd.resolve("omlx_server.log")));
    }

    @Test
    void captureOmlxLogKeepsOnlyLinesWithinTheRunsOwnWindowPlusTheirContinuations() throws Exception {
        final ZoneId zone = ZoneId.systemDefault();   // oMLX's own log has no zone offset - it's local time
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
        final Instant start = Instant.parse("2026-09-25T07:00:00Z"), end = Instant.parse("2026-09-25T08:00:00Z");
        final String before = fmt.format(LocalDateTime.ofInstant(start.minusSeconds(120), zone));
        final String firstInRange = fmt.format(LocalDateTime.ofInstant(start.plusSeconds(60), zone));
        final String lastInRange = fmt.format(LocalDateTime.ofInstant(end.minusSeconds(60), zone));
        final String after = fmt.format(LocalDateTime.ofInstant(end.plusSeconds(120), zone));

        final Path src = track(Files.createTempFile("omlx-server", ".log"));
        Files.write(src, List.of(
                before + " - omlx.server - INFO - before the run, must be dropped",
                firstInRange + " - omlx.server - INFO - first line inside the run",
                "    a continuation line with no timestamp, belongs to the entry above",
                lastInRange + " - omlx.server - INFO - last line inside the run",
                after + " - omlx.server - INFO - after the run, must be dropped"));
        final BenchProperties props = mock(BenchProperties.class);
        when(props.omlxServerLog()).thenReturn(src.toString());
        final Path rd = track(Files.createTempDirectory("rd"));

        runBench(props).captureOmlxLog(rd, Map.of("started", start.toString(), "ended", end.toString()));

        final List<String> kept = Files.readAllLines(rd.resolve("omlx_server.log"));
        assertEquals(3, kept.size(), "the two in-range dated lines plus the continuation, nothing outside the window: " + kept);
        assertTrue(kept.get(0).contains("first line inside the run"));
        assertTrue(kept.get(1).contains("continuation line"));
        assertTrue(kept.get(2).contains("last line inside the run"));
    }

    /** #99: a cancelled job interrupts the thread blocked in joinAll() - that interrupt must reach
     *  every parallel-wave thread (not just get swallowed here while they keep running to completion
     *  unattended), and joinAll() must actually wait for them to stop before returning. */
    @Test
    void joinAllInterruptsAndWaitsForEveryWaveThreadWhenCancelled() throws Exception {
        final var interrupted = new java.util.concurrent.atomic.AtomicInteger();
        final var started = new java.util.concurrent.CountDownLatch(2);
        final List<Thread> workers = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final Thread w = new Thread(() -> {
                started.countDown();
                try { Thread.sleep(60_000); }   // stands in for a still-running agent session
                catch (InterruptedException ie) { interrupted.incrementAndGet(); }
            });
            workers.add(w);
            w.start();
        }
        started.await();

        final Thread caller = new Thread(() -> {
            try { RunBench.joinAll(workers); }
            catch (InterruptedException expected) { /* correctly propagated to the caller */ }
        });
        caller.start();
        Thread.sleep(200);   // let joinAll() actually block in th.join() before interrupting it
        caller.interrupt();
        caller.join(5000);

        assertFalse(caller.isAlive(), "joinAll() must return once its own interrupt is handled");
        assertEquals(2, interrupted.get(), "every wave thread must receive its own interrupt, not be abandoned running");
        for (Thread w : workers) assertFalse(w.isAlive(), "joinAll() must wait for the wave threads to actually stop, not just signal and move on");
    }

    /** #168: runParallelWave's per-task threads put() into a shared map with no synchronization
     *  beyond the Semaphore bounding how many run at once - which provides no mutual exclusion.
     *  A plain LinkedHashMap can silently lose an entry (or corrupt its structure) when two threads
     *  resize it concurrently; ConcurrentHashMap (what recs/errors were switched to) is the actual
     *  fix - this reproduces the same access shape (many threads, unique keys, bounded concurrency)
     *  against it directly, as a regression guard against reverting to a non-concurrent map. */
    @Test
    void concurrentPutsFromManyThreadsUnderABoundedSemaphoreLoseNoEntries() throws Exception {
        final int n = 200;
        final Map<String, Object> shared = new java.util.concurrent.ConcurrentHashMap<>();
        final var sem = new java.util.concurrent.Semaphore(8);   // same bound shape as "parallel"
        final List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String key = "t" + i;
            final Thread th = new Thread(() -> {
                try {
                    sem.acquire();
                    try { shared.put(key, key); } finally { sem.release(); }
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            });
            threads.add(th);
            th.start();
        }
        RunBench.joinAll(threads);
        assertEquals(n, shared.size(), "every thread's own key must survive - none lost to an unsynchronized map resize");
        for (int i = 0; i < n; i++) assertEquals("t" + i, shared.get("t" + i));
    }

    /** #168: the ownership-glob matcher this replaced stripped only the LAST `*` in its first
     *  branch (dead for any `**`-suffixed glob) and fell back to a bare directory-prefix check for
     *  a single-star glob - silently treating "account-service/*.java" (direct children only) the
     *  same as "account-service/**" (any depth), under-detecting real ownership violations. */
    @Test
    void matchesOwnershipRecursiveGlobMatchesAnyDepth() {
        assertTrue(RunBench.matchesOwnership("account-service/**", "account-service/Foo.java"));
        assertTrue(RunBench.matchesOwnership("account-service/**", "account-service/sub/Foo.java"));
        assertFalse(RunBench.matchesOwnership("account-service/**", "other-service/Foo.java"));
    }

    @Test
    void matchesOwnershipSingleStarGlobMatchesOnlyDirectChildren() {
        assertTrue(RunBench.matchesOwnership("account-service/*.java", "account-service/Foo.java"));
        assertFalse(RunBench.matchesOwnership("account-service/*.java", "account-service/sub/Foo.java"),
                "a single-star glob must not reach into subdirectories - that is what ** is for");
    }

    @Test
    void matchesOwnershipWithNoWildcardIsAPlainDirectoryPrefix() {
        assertTrue(RunBench.matchesOwnership("account-service", "account-service/Foo.java"));
        assertFalse(RunBench.matchesOwnership("account-service", "other-service/Foo.java"));
    }

    /** Found live 2026-09-28: DockerWindowMonitor.poll() used to read ACE_DOCKER_LOG via
     *  System.getenv() - the ace-service JVM's OWN environment, where that variable is never
     *  actually set (it only ever exists inside the Map built for the AGENT's child process, see
     *  RunBenchSupport.scrubbedEnv). dockerCalls() therefore always saw "/dev/null" and returned all
     *  zeros, silently turning "idle since the agent's last real docker call" into "idle since the
     *  window opened" for every run. The constructor now takes THIS session's own path explicitly. */
    @Test
    void dockerWindowMonitorUsesTheGivenDockerLogPathNotTheJvmsOwnEnvironment() {
        final RunBench rb = runBench();
        final var dw = rb.new DockerWindowMonitor("/custom/per-session/docker-calls.log");
        assertEquals(Path.of("/custom/per-session/docker-calls.log"), dw.dockerLog);
    }

    @Test
    void dockerWindowMonitorDefaultsToDevNullWhenNoDockerLogPathIsGiven() {
        final RunBench rb = runBench();
        assertEquals(Path.of("/dev/null"), rb.new DockerWindowMonitor(null).dockerLog);
    }

    /** Found live 2026-09-28: interrupt() alone only sets a flag the monitor loop checks BETWEEN
     *  poll() calls - a caller proceeding right after interrupt() has no guarantee the monitor has
     *  actually stopped. stopMonitor() gives it a bounded chance to actually finish first. */
    @Test
    void stopMonitorWaitsForTheThreadToActuallyStopWhenItRespondsPromptly() throws Exception {
        final Thread t = new Thread(() -> {
            try { Thread.sleep(60_000); } catch (InterruptedException ignored) { /* responds immediately */ }
        });
        t.start();
        Thread.sleep(50);   // let it actually reach the sleep before stopping it

        RunBench.stopMonitor(t);

        assertFalse(t.isAlive(), "a thread that responds promptly to interrupt() must be stopped by the time stopMonitor() returns");
    }

    @Test
    void stopMonitorGivesUpAfterItsOwnBoundRatherThanHangOnAThreadThatIgnoresInterrupt() throws Exception {
        final var started = new java.util.concurrent.CountDownLatch(1);
        final Thread t = new Thread(() -> {
            started.countDown();
            final long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) { /* deliberately ignores its own interrupted flag */ }
        });
        t.start();
        started.await();

        final long t0 = System.currentTimeMillis();
        RunBench.stopMonitor(t);
        final long elapsedMs = System.currentTimeMillis() - t0;

        assertTrue(elapsedMs < 5_000, "stopMonitor() must give up around its own bound, not wait out a thread that ignores interrupt: " + elapsedMs + "ms");
        t.join(15_000);   // let the real background thread actually finish so it doesn't leak past this test
    }

    /** Found live 2026-09-28: a run's generated code lived ONLY in ws, a scratch dir under $TMPDIR
     *  that survives only until the OS decides to reclaim it - a run from days earlier had already
     *  lost its working tree AND part of its own git object store by the time anyone went looking,
     *  recoverable only by hand. exportSource() puts a plain, ready-to-run copy in rd/source/ instead
     *  of leaving that as the only backup a `git clone` of workspace.bundle could someday reconstruct. */
    private static void git(final Path dir, final String... args) throws Exception {
        final var cmd = new java.util.ArrayList<String>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "git " + args[0] + " failed: " + out);
    }

    @Test
    void exportSourceProducesAPlainReadyToRunDirectoryFromTheCommittedState(@org.junit.jupiter.api.io.TempDir final Path tmp) throws Exception {
        final Path ws = track(Files.createDirectory(tmp.resolve("ws")));
        final Path rd = track(Files.createDirectory(tmp.resolve("rd")));
        git(ws, "init", "-q");
        git(ws, "config", "user.email", "bench@local");
        git(ws, "config", "user.name", "bench");
        Files.writeString(ws.resolve(".git/info/exclude"), "build/\n");   // matches RunBenchSupport.snapshot()
        Files.writeString(ws.resolve("docker-compose.yml"), "services: {}\n");
        Files.createDirectories(ws.resolve("build"));
        Files.writeString(ws.resolve("build/compiled.class"), "not source");   // untracked, must not be exported
        git(ws, "add", "-A");
        git(ws, "commit", "-q", "-m", "T1");

        assertTrue(RunBench.exportSource(ws, rd));

        assertEquals("services: {}\n", Files.readString(rd.resolve("source/docker-compose.yml")));
        assertFalse(Files.exists(rd.resolve("source/build")), "only git-TRACKED files belong in the export, not build output");
    }

    @Test
    void exportSourceReturnsFalseRatherThanThrowWhenWsIsNotAGitRepository(@org.junit.jupiter.api.io.TempDir final Path tmp) throws Exception {
        final Path ws = track(Files.createDirectory(tmp.resolve("not-a-repo")));
        final Path rd = track(Files.createDirectory(tmp.resolve("rd2")));

        assertFalse(RunBench.exportSource(ws, rd));
    }

    // ---- oracleWithDocker(): found live 2026-09-29, Docker Desktop's own backend crashed under RAM
    // pressure from a co-resident model server (#165 fixed this by unloading the model before Docker
    // comes up and reloading it after) - these pin the sequencing/exception-safety that fix depends on.

    private ContextProbe mockProbeAndProps(final BenchProperties props) {
        when(props.endpoint()).thenReturn("http://127.0.0.1:9191/v1");
        when(props.apiKey()).thenReturn("key");
        when(props.model()).thenReturn("m");
        return mock(ContextProbe.class);
    }

    @Test
    void oracleWithDockerUnloadsBeforeDockerUpBeforeScoringBeforeDockerDownBeforeReload(@org.junit.jupiter.api.io.TempDir final Path tmp) throws Exception {
        final var props = mock(BenchProperties.class);
        final var probe = mockProbeAndProps(props);
        final var oracle = mock(RunOracle.class);
        final var rb = new RunBench(props, mock(ReferenceAgent.class), mock(RecordingProxyFactory.class), oracle, mock(Reviews.class), probe);
        when(oracle.score(any(), any(), any(), any())).thenReturn(Map.of("ok", true));

        try (var dockerMock = mockStatic(DockerService.class)) {
            dockerMock.when(() -> DockerService.dockerUp(anyInt(), anyInt())).thenReturn(true);

            rb.oracleWithDocker(Map.of("manage_docker", true), tmp, "task", tmp, Map.of());

            final var inOrder = inOrder(probe, oracle);
            inOrder.verify(probe).unloadModel("http://127.0.0.1:9191/v1", "key", "m");
            dockerMock.verify(() -> DockerService.dockerUp(360, DockerService.DEFAULT_MEMORY_MIB));
            inOrder.verify(oracle).score(any(), any(), any(), any());
            dockerMock.verify(() -> DockerService.dockerDown(30));
            inOrder.verify(probe).loadModel("http://127.0.0.1:9191/v1", "key", "m");
        }
    }

    /** the #165 guarantee: a Docker-gated check crashing (or any other failure mid-scoring) must
     *  never leave the model permanently unloaded / Docker Desktop's VM permanently up - the finally
     *  block is what this test pins down. */
    @Test
    void oracleWithDockerStillReloadsTheModelWhenScoringThrows(@org.junit.jupiter.api.io.TempDir final Path tmp) throws Exception {
        final var props = mock(BenchProperties.class);
        final var probe = mockProbeAndProps(props);
        final var oracle = mock(RunOracle.class);
        final var rb = new RunBench(props, mock(ReferenceAgent.class), mock(RecordingProxyFactory.class), oracle, mock(Reviews.class), probe);
        when(oracle.score(any(), any(), any(), any())).thenThrow(new RuntimeException("docker-gated check crashed"));

        try (var dockerMock = mockStatic(DockerService.class)) {
            dockerMock.when(() -> DockerService.dockerUp(anyInt(), anyInt())).thenReturn(true);

            assertThrows(RuntimeException.class, () -> rb.oracleWithDocker(Map.of("manage_docker", true), tmp, "task", tmp, Map.of()));

            dockerMock.verify(() -> DockerService.dockerDown(30));
            verify(probe).loadModel("http://127.0.0.1:9191/v1", "key", "m");
        }
    }

    /** emulates the real incident: Docker Desktop's backend crashed/never came back up. dockerUp()
     *  giving up (returning false) must not skip scoring (RunOracle's own "docker info" check is
     *  what actually gates individual checks) or skip the reload. */
    @Test
    void oracleWithDockerStillScoresAndReloadsWhenDockerNeverComesUp(@org.junit.jupiter.api.io.TempDir final Path tmp) throws Exception {
        final var props = mock(BenchProperties.class);
        final var probe = mockProbeAndProps(props);
        final var oracle = mock(RunOracle.class);
        final var rb = new RunBench(props, mock(ReferenceAgent.class), mock(RecordingProxyFactory.class), oracle, mock(Reviews.class), probe);
        when(oracle.score(any(), any(), any(), any())).thenReturn(Map.of("ok", true));

        try (var dockerMock = mockStatic(DockerService.class)) {
            dockerMock.when(() -> DockerService.dockerUp(anyInt(), anyInt())).thenReturn(false);   // gave up

            rb.oracleWithDocker(Map.of("manage_docker", true), tmp, "task", tmp, Map.of());

            verify(oracle).score(any(), any(), any(), any());
            dockerMock.verify(() -> DockerService.dockerDown(30));
            verify(probe).loadModel("http://127.0.0.1:9191/v1", "key", "m");
        }
    }

    @Test
    void oracleWithDockerDoesNothingDockerRelatedWhenManageDockerIsOff(@org.junit.jupiter.api.io.TempDir final Path tmp) throws Exception {
        final var props = mock(BenchProperties.class);
        final var probe = mock(ContextProbe.class);
        final var oracle = mock(RunOracle.class);
        final var rb = new RunBench(props, mock(ReferenceAgent.class), mock(RecordingProxyFactory.class), oracle, mock(Reviews.class), probe);
        when(oracle.score(any(), any(), any(), any())).thenReturn(Map.of("ok", true));

        try (var dockerMock = mockStatic(DockerService.class)) {
            rb.oracleWithDocker(Map.of("manage_docker", false), tmp, "task", tmp, Map.of());

            verifyNoInteractions(probe);
            dockerMock.verifyNoInteractions();
            verify(oracle).score(any(), any(), any(), any());
        }
    }

    @Test
    void oracleWithDockerThreadsTheConfiguredMemoryMibThroughToDockerUp(@org.junit.jupiter.api.io.TempDir final Path tmp) throws Exception {
        final var props = mock(BenchProperties.class);
        final var probe = mockProbeAndProps(props);
        final var oracle = mock(RunOracle.class);
        final var rb = new RunBench(props, mock(ReferenceAgent.class), mock(RecordingProxyFactory.class), oracle, mock(Reviews.class), probe);
        when(oracle.score(any(), any(), any(), any())).thenReturn(Map.of("ok", true));

        try (var dockerMock = mockStatic(DockerService.class)) {
            dockerMock.when(() -> DockerService.dockerUp(anyInt(), anyInt())).thenReturn(true);
            rb.oracleWithDocker(Map.of("manage_docker", true, "docker_memory_mib", 8192), tmp, "task", tmp, Map.of());
            dockerMock.verify(() -> DockerService.dockerUp(360, 8192));
        }
    }
}
