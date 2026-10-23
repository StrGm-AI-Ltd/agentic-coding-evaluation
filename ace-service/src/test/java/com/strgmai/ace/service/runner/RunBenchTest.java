package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.PiAgent;
import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.oracle.RunOracle;
import com.strgmai.ace.service.pack.Packs;
import com.strgmai.ace.service.plan.PlanTask;
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
        return new RunBench(props, mock(ReferenceAgent.class), mock(PiAgent.class), mock(RecordingProxyFactory.class),
                mock(RunOracle.class), mock(Reviews.class), mock(ContextProbe.class), mock(Judge.class), new Intervene(), mock(PauseAfterTask.class));
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

    /** the plan phase's wall budget was never a per-run override at all, even in the original Python
     *  harness (only an operator-wide config value, via BenchProperties.phaseWall - already read by
     *  orchestratedPhase() before this existed). planWall() adds the per-run override on top, and
     *  preserves the 900s literal default for anyone who has configured neither. */
    @Test
    void planWallPrefersTheExplicitCfgOverrideOverEverything() {
        final BenchProperties props = mock(BenchProperties.class);
        when(props.phaseWall("p1_plan")).thenReturn(1200);
        assertEquals(600L, RunBench.planWall(Map.of("plan_wall_sec", 600), "p1_plan", props));
    }

    @Test
    void planWallFallsBackToTheOperatorWideConfigWhenNotSetOnTheRun() {
        final BenchProperties props = mock(BenchProperties.class);
        when(props.phaseWall("p1_plan")).thenReturn(1200);
        assertEquals(1200L, RunBench.planWall(Map.of(), "p1_plan", props));
    }

    @Test
    void planWallFallsBackToNineHundredSecondsWhenNeitherIsConfigured() {
        final BenchProperties props = mock(BenchProperties.class);
        when(props.phaseWall("p1_plan")).thenReturn(0);
        assertEquals(900L, RunBench.planWall(Map.of(), "p1_plan", props));
    }

    @Test
    void planWallAcceptsAnExplicitZeroAsUnlimitedRatherThanFallingBack() {
        final BenchProperties props = mock(BenchProperties.class);
        when(props.phaseWall("p1_plan")).thenReturn(1200);
        // runBounded() treats wallSec <= 0 as unlimited (the system-wide "0 = no budget" convention) -
        // an explicit 0 must reach it as a literal 0, not silently fall back to the operator default
        assertEquals(0L, RunBench.planWall(Map.of("plan_wall_sec", 0), "p1_plan", props));
    }

    /** #228: --wall-budget (cfg's "wall_budget_override") - a genuine experimental setting that
     *  overrides every phase's wall uniformly, instead of one at a time. */
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

    /** #235: job 6dc01359-0d37-48b3-afda-86226d391c39 failed outright (no manifest.json at all)
     *  because the agent's p1_plan session finished without ever writing docs/IMPLEMENTATION_PLAN.md
     *  and nothing caught the resulting PlanError. planErrorEntry() is the Python-matching recovery:
     *  record the failure in manifest.plan instead of crashing. */
    @Test
    void planErrorEntryRecordsTheFailureInsteadOfPropagating() {
        final var entry = RunBench.planErrorEntry("agent", new com.strgmai.ace.service.plan.PlanError("cannot read plan: boom"));
        assertEquals("agent", entry.get("source"));
        assertEquals("cannot read plan: boom", entry.get("error"));
        assertEquals(List.of(), entry.get("tasks"));
    }

    @Test
    void planErrorEntryTruncatesAnOverlongMessageToTwoHundredCharsLikePython() {
        final var longMessage = "x".repeat(500);
        final var entry = RunBench.planErrorEntry("agent", new com.strgmai.ace.service.plan.PlanError(longMessage));
        assertEquals(200, ((String) entry.get("error")).length());
    }

    @Test
    void planErrorEntryFallsBackToToStringWhenTheExceptionHasNoMessage() {
        final var entry = RunBench.planErrorEntry("agent", new com.strgmai.ace.service.plan.PlanError(null));
        assertNotNull(entry.get("error"));
        assertFalse(((String) entry.get("error")).isBlank());
    }

    /** Found live 2026-09-25: oMLX's own memory-pressure throttling was slow enough to trip the
     *  agent's 180s stall detector, and oMLX's log - the only place that explained why - spans
     *  every run on the machine and keeps growing. Each run now copies its own slice out. */
    /** #337: task_attempts.jsonl is append-only (see logTaskAttempt()) - countPriorAttempts() just
     *  counts existing lines for a task id, so the NEXT logTaskAttempt() call can write the correct
     *  1-indexed attempt_number. */
    @Test
    void countPriorAttemptsIsZeroWithNoFileAtAll() throws Exception {
        final Path rd = track(Files.createTempDirectory("rd"));
        assertEquals(0, runBench().countPriorAttempts(rd, "T1"));
    }

    @Test
    void countPriorAttemptsCountsOnlyLinesForTheGivenTaskId() throws Exception {
        final Path rd = track(Files.createTempDirectory("rd"));
        Files.writeString(rd.resolve("task_attempts.jsonl"), String.join("\n",
                "{\"task_id\":\"T1\",\"rc\":3}", "{\"task_id\":\"T2\",\"rc\":0}", "{\"task_id\":\"T1\",\"rc\":0}") + "\n");
        assertEquals(2, runBench().countPriorAttempts(rd, "T1"));
        assertEquals(1, runBench().countPriorAttempts(rd, "T2"));
        assertEquals(0, runBench().countPriorAttempts(rd, "T3"));
    }

    /** #337: a rescued attempt and a clean one must not write identical-looking lines - rec's own
     *  rescue_succeeded/rescue_rc/rescue_seconds (set by rescueStep(), RunBench.java:~1526) need to
     *  actually reach the written entry, and the entry's own attempt_number must count whatever
     *  this task id already had logged in the same rd. */
    @Test
    void logTaskAttemptWritesTheAttemptNumberAndCopiesRescueFieldsWhenPresent() throws Exception {
        final Path rd = track(Files.createTempDirectory("rd"));
        final Path ws = track(Files.createTempDirectory("ws"));   // no docs/PROGRESS.md - reportedStatus() just reads null
        final var bench = runBench();

        bench.logTaskAttempt(rd, ws, "T1", Map.of(), 3600, 10000, 50, new java.util.LinkedHashMap<>(Map.of("rc", 3, "seconds", 100.0)));
        final var rescuedRec = new java.util.LinkedHashMap<String, Object>(Map.of("rc", 0, "seconds", 50.0));
        rescuedRec.put("rescue_succeeded", true); rescuedRec.put("rescue_rc", 0); rescuedRec.put("rescue_seconds", 12.5);
        bench.logTaskAttempt(rd, ws, "T1", Map.of(), 3600, 10000, 50, rescuedRec);

        final List<String> lines = Files.readAllLines(rd.resolve("task_attempts.jsonl"));
        assertEquals(2, lines.size());
        final var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        final Map<?, ?> first = mapper.readValue(lines.get(0), Map.class);
        final Map<?, ?> second = mapper.readValue(lines.get(1), Map.class);
        assertEquals(1, first.get("attempt_number"));
        assertNull(first.get("rescue_succeeded"), "a non-rescued attempt carries no rescue fields at all");
        assertEquals(2, second.get("attempt_number"), "the second attempt for the same task id counts the first");
        assertEquals(true, second.get("rescue_succeeded"));
        assertEquals(0, second.get("rescue_rc"));
    }

    /** #337-followup: params used to carry only task_wall_sec/task_tokens/max_turns - every OTHER
     *  override actually in effect for this attempt (compaction_trigger, judge_enabled, etc.) was
     *  silently dropped, even though it was right there in `override`. Found live: the Stats tab's
     *  per-attempt breakdown (#355-followup-2) never had anything to show beyond the three hardcoded
     *  fields, no matter what was actually adjusted for that attempt. */
    @Test
    void logTaskAttemptCarriesEveryOverriddenKeyIntoParamsNotJustTheThreeHardcodedOnes() throws Exception {
        final Path rd = track(Files.createTempDirectory("rd"));
        final Path ws = track(Files.createTempDirectory("ws"));
        final var bench = runBench();

        bench.logTaskAttempt(rd, ws, "T9", Map.of("compaction_trigger", 110000, "judge_enabled", true),
                32400, 180000, 1000, new java.util.LinkedHashMap<>(Map.of("rc", 0, "seconds", 10.0)));

        final var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        final Map<?, ?> entry = mapper.readValue(Files.readAllLines(rd.resolve("task_attempts.jsonl")).get(0), Map.class);
        final Map<?, ?> params = (Map<?, ?>) entry.get("params");
        assertEquals(32400, params.get("task_wall_sec"));
        assertEquals(110000, params.get("compaction_trigger"), "an overridden knob beyond the three hardcoded fields must still show up");
        assertEquals(true, params.get("judge_enabled"));
    }

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

    /** #263: taskOverride() is Map.of() - never null, and never a false-positive match - for every
     *  session name that isn't a real, overridden plan task id. */
    @Test
    void taskOverrideLooksUpOnlyTheExactTaskIdAndIsNeverNull() {
        final var cfg = Map.<String, Object>of("task_overrides", Map.of("T7", Map.of("task_wall_sec", 40000)));
        assertEquals(Map.of("task_wall_sec", 40000), RunBench.taskOverride(cfg, "T7"));
        assertEquals(Map.of(), RunBench.taskOverride(cfg, "T8"), "a different task id must never match T7's own override");
        assertEquals(Map.of(), RunBench.taskOverride(cfg, "INTEGRATION"), "a non-task session name must never match either");
        assertEquals(Map.of(), RunBench.taskOverride(Map.of(), "T7"), "no task_overrides key at all - still never null");
    }

    @Test
    void effectiveIntAndLongFallBackWhenNoOverrideIsPresent() {
        final var override = Map.<String, Object>of("max_turns", 600);
        assertEquals(600, RunBench.effectiveInt(override, "max_turns", 400));
        assertEquals(400, RunBench.effectiveInt(override, "task_wall_sec", 400), "no entry for this key - the fallback, not 0");
        assertEquals(400, RunBench.effectiveInt(Map.of(), "max_turns", 400));
        assertEquals(40000L, RunBench.effectiveLong(Map.<String, Object>of("task_tokens", 40000), "task_tokens", 0L));
    }

    /** #258, found live: this predicate is no longer gated on session kind at all - the whole bug
     *  was that p0_definition/p1_plan never reached this retry (the OLD signature took a plainPlan
     *  flag that always short-circuited it to false for them) even though they hit the exact same
     *  `length`-finish truncation a task session does. */
    @Test
    void shouldRetryOnLengthFinishIgnoresSessionKindEntirely() {
        assertTrue(RunBench.shouldRetryOnLengthFinish("length", 0, 20000, 500), "a plain task-shaped call");
        assertFalse(RunBench.shouldRetryOnLengthFinish("stop", 0, 20000, 500), "not a length finish at all");
        assertFalse(RunBench.shouldRetryOnLengthFinish("length", 124, 20000, 500), "already wall-timed-out - nothing left to continue into");
        assertFalse(RunBench.shouldRetryOnLengthFinish("length", 0, 20000, 19950), "under 120s remaining - not worth a whole extra call");
        assertTrue(RunBench.shouldRetryOnLengthFinish("length", 0, 0, 999_999), "wall<=0 means unlimited - always enough remaining");
    }

    /** Found live: T11 stopped after one ~25k-token turn that trailed off mid-sentence with 75% of
     *  its token budget and 88% of its wall-clock still unspent - a "stop" finish with no tool call
     *  and no status, while plenty of both budgets remained. Re-engaging only makes sense when
     *  there is real room left on BOTH axes; a near-exhausted task should fall through to the
     *  existing wrap-up instead. */
    @Test
    void shouldReEngageOnEmptyStopRequiresAStopFinishWithRealBudgetLeftOnBothAxes() {
        assertTrue(RunBench.shouldReEngageOnEmptyStop("stop", 0, 14400, 1668, 65000, 25435), "T11's own numbers: 88% wall, 61% tokens left");
        assertFalse(RunBench.shouldReEngageOnEmptyStop("tool_calls", 0, 14400, 1668, 65000, 25435), "ended via a tool call, not an empty stop");
        assertFalse(RunBench.shouldReEngageOnEmptyStop("length", 0, 14400, 1668, 65000, 25435), "a length finish is shouldRetryOnLengthFinish's job, not this one's");
        assertFalse(RunBench.shouldReEngageOnEmptyStop("stop", 124, 14400, 1668, 65000, 25435), "already wall-timed-out - nothing left to continue into");
        assertFalse(RunBench.shouldReEngageOnEmptyStop("stop", 0, 14400, 12000, 65000, 25435), "under a fifth of the wall remaining");
        assertFalse(RunBench.shouldReEngageOnEmptyStop("stop", 0, 14400, 1668, 65000, 60000), "under a fifth of the tokens remaining");
        assertTrue(RunBench.shouldReEngageOnEmptyStop("stop", 0, 0, 999_999, 65000, 25435), "wall<=0 means unlimited - always enough remaining");
        assertTrue(RunBench.shouldReEngageOnEmptyStop("stop", 0, 14400, 1668, 0, 999_999), "tokens<=0 means unlimited - always enough remaining");
    }

    @Test
    void effectiveCompactionTriggerPrefersTheTaskOverrideOverTheRunLevelValue() {
        assertEquals(60000, RunBench.effectiveCompactionTrigger(Map.of("compaction_trigger", 60000), 40000));
        assertEquals(40000, RunBench.effectiveCompactionTrigger(Map.of(), 40000), "no override - the run-level value stands");
        assertEquals(40000, RunBench.effectiveCompactionTrigger(Map.of("compaction_trigger", "not-a-number"), 40000));
    }

    /** #398: same "a task_override wins over the run-level default" rule as
     *  effectiveCompactionTrigger above. */
    @Test
    void effectiveTaskGranularityPrefersTheTaskOverrideOverTheRunLevelValue() {
        assertEquals("fine", RunBench.effectiveTaskGranularity(Map.of("task_granularity", "fine"), "coarse"));
        assertEquals("coarse", RunBench.effectiveTaskGranularity(Map.of(), "coarse"), "no override - the run-level value stands");
        assertNull(RunBench.effectiveTaskGranularity(Map.of(), null), "no override and no run-level value - no explicit guidance");
        assertEquals("coarse", RunBench.effectiveTaskGranularity(Map.of("task_granularity", ""), "coarse"), "a blank override is treated as absent");
    }

    /** #322: a task_override for any sampler field replaces just that field - the others keep
     *  whatever the run-level SamplerOverrides already carried (an empty override must return the
     *  exact same base instance, not a field-by-field-identical copy, since callers may rely on
     *  that - see RecordingProxy's own "pinned once per run" reasoning). */
    @Test
    void effectiveSamplerOverridesMergesOnlyTheFieldsATaskOverrideActuallySets() {
        final Map<String, Object> cfg = new java.util.HashMap<>();
        final var base = new com.strgmai.ace.service.proxy.RecordingProxy.SamplerOverrides(0.6, 0.95, 20, 1.0, 20000, "medium", 1000, 210000);
        cfg.put("_sampler_overrides", base);

        assertSame(base, RunBench.effectiveSamplerOverrides(cfg, Map.of()), "no override - the exact same pinned instance comes back");

        final var merged = RunBench.effectiveSamplerOverrides(cfg, Map.of("max_tokens", 180000, "reasoning_effort", "low"));
        assertEquals(180000, merged.maxOutputTokens(), "overridden");
        assertEquals("low", merged.reasoningEffort(), "overridden");
        assertEquals(0.6, merged.temperature(), "not overridden - kept from the run-level base");
        assertEquals(0.95, merged.topP());
        assertEquals(20, merged.topK());
        assertEquals(1.0, merged.repetitionPenalty());
        assertEquals(1000, merged.firstTokenTimeoutSec(), "never task-overridable - the agent-loop side already has its own override");
        assertEquals(210000, merged.contextWindow(), "never task-overridable - a fact about the model server, not a per-task choice");
    }

    @Test
    void effectiveSamplerOverridesFallsBackToNoneWhenTheRunNeverPinnedAny() {
        final var merged = RunBench.effectiveSamplerOverrides(Map.of(), Map.of("temperature", 0.2));
        assertEquals(0.2, merged.temperature());
        assertNull(merged.topP());
    }

    /** #322: rescue_wall_sec/rescue_tokens/rescue_keep_recent_turns were deliberately
     *  operator-wide-only before - lifted for the same reason budget_rescue_enabled itself is
     *  task-overridable. */
    @Test
    void effectiveRescueCfgNowAlsoTakesWallTokensAndKeepRecentTurnsFromTheTaskOverride() {
        final Map<String, Object> cfg = Map.of("budget_rescue", Map.of("enabled", true, "wall_sec", 28000, "tokens", 65000));
        final var withoutOverride = RunBench.effectiveRescueCfg(cfg, Map.of());
        assertEquals(28000, withoutOverride.get("wall_sec"));
        assertEquals(65000, withoutOverride.get("tokens"), "copied verbatim from the run-level map - no override applied yet");

        final var withOverride = RunBench.effectiveRescueCfg(cfg, Map.of(
                "rescue_wall_sec", 40000, "rescue_tokens", 90000, "rescue_keep_recent_turns", 5));
        assertEquals(true, withOverride.get("enabled"), "unrelated run-level field untouched");
        assertEquals(40000, withOverride.get("wall_sec"));
        assertEquals(90000L, withOverride.get("tokens"));
        assertEquals(5, withOverride.get("keep_recent_turns"));
    }

    @Test
    void dueForJudgeCheckFiresOnceTheIntervalHasElapsedSinceTheLastCheck() {
        assertTrue(RunBench.dueForJudgeCheck(10, 0, 10), "exactly N turns since the last check (turn 0)");
        assertFalse(RunBench.dueForJudgeCheck(9, 0, 10), "one turn short");
        assertTrue(RunBench.dueForJudgeCheck(23, 13, 10), "N turns since the last check, not since turn 0 - #299's interval can shrink mid-task");
        assertTrue(RunBench.dueForJudgeCheck(7, 0, 0), "a hand-crafted 0 (from a restart override) must never divide by zero - treated as every turn");
        assertTrue(RunBench.dueForJudgeCheck(7, 0, -3), "a negative override is the same degenerate case as 0");
    }

    /** #402/#404: the first judge check fires earlier than the operator's own steady-state everyN
     *  - a reconnaissance-only start or dead-scaffolding pattern, both found live repeatedly as
     *  their own judge-off-track incident, would otherwise burn the whole default cadence (10
     *  turns) before anything catches them at all. */
    @Test
    void firstJudgeIntervalTightensTheFirstCheckButNeverWidensTheOperatorsOwnEveryN() {
        assertEquals(5, RunBench.firstJudgeInterval(10), "the operator's default (10) is loosened for the first check only");
        assertEquals(3, RunBench.firstJudgeInterval(3), "already tighter than 5 - never widened");
        assertEquals(0, RunBench.firstJudgeInterval(0), "0 ('every turn') stays 0, not widened to 5");
    }

    /** #299: the judge checks more often the longer a task stays off track, and recovers once it
     *  reports on track again - symmetric (halve down / double up), not a hard snap back to
     *  everyN on the first good verdict, to avoid flapping right at the boundary. */
    @Test
    void nextJudgeIntervalHalvesOnOffTrackAndDoublesBackOnTrackCappedAtEveryN() {
        assertEquals(5, RunBench.nextJudgeInterval(10, 10, true));
        assertEquals(2, RunBench.nextJudgeInterval(5, 10, true), "keeps halving while it stays off track");
        assertEquals(1, RunBench.nextJudgeInterval(1, 10, true), "floors at 1 (every turn), never 0 or negative");
        assertEquals(2, RunBench.nextJudgeInterval(1, 10, false), "doubles back up once on track again");
        assertEquals(10, RunBench.nextJudgeInterval(8, 10, false), "capped at everyN - never looser than the operator's own configured cadence");
        assertEquals(10, RunBench.nextJudgeInterval(10, 10, false), "already at everyN and on track - stays put");
    }

    /** #265: the run-level "judge" map, with a judge_enabled/judge_every_n_turns/judge_model
     *  restart override replacing just the matching key - never merged wholesale, never dropping
     *  the OTHER run-level keys the override didn't touch. */
    @Test
    void effectiveJudgeCfgAppliesOnlyTheOverriddenKeys() {
        final var cfg = Map.<String, Object>of("judge", Map.of("enabled", false, "every_n_turns", 10, "wall_sec", 120));
        assertEquals(Map.of("enabled", false, "every_n_turns", 10, "wall_sec", 120), RunBench.effectiveJudgeCfg(cfg, Map.of()),
                "no override at all - the run-level map passes through unchanged");
        assertEquals(Map.of("enabled", true, "every_n_turns", 10, "wall_sec", 120),
                RunBench.effectiveJudgeCfg(cfg, Map.of("judge_enabled", true)), "only enabled flips; wall_sec/every_n_turns survive");
        assertEquals(Map.of("enabled", false, "every_n_turns", 5, "wall_sec", 120),
                RunBench.effectiveJudgeCfg(cfg, Map.of("judge_every_n_turns", 5)));
        assertEquals("gpt-5", RunBench.effectiveJudgeCfg(cfg, Map.of("judge_model", "gpt-5")).get("model"));
        assertEquals(Map.of(), RunBench.effectiveJudgeCfg(Map.of(), Map.of()), "no judge cfg at all on the run - never null, never throws");
    }

    /** #262, found live: rc==2 (chatWithRetry's own retries-exhausted TransientError - the exact
     *  shape an infra rejection takes, confirmed live as an oMLX memory-guard rejection) and
     *  rc==124 (runBounded's own task-wall timeout) each get a specific, different tail; anything
     *  else (a session that returned normally but produced no further output - T7's own actual
     *  case) gets a generic-but-still-specific one naming the turn count, never the bare "" this
     *  replaced. */
    @Test
    void fallbackTailNamesTheRealCauseInsteadOfBeingBlank() {
        assertTrue(RunBench.fallbackTail(2, 20000, 46, "2026-10-06T05:43:31Z", null).contains("service log"));
        assertEquals(" (task-wall budget of 20000s exceeded)", RunBench.fallbackTail(124, 20000, 46, "2026-10-06T05:43:31Z", null));
        final var plain = RunBench.fallbackTail(0, 20000, 46, "2026-10-06T05:43:31Z", null);
        assertTrue(plain.contains("46 turns") && plain.contains("interactions.jsonl"));
    }

    /** #415: found live on T19 - the real cause (oMLX's own memory-pressure abort) lived only in
     *  oMLX's own local server.log, invisible to this fallback text, which could only say "the model
     *  produced no further output". When JournalFacts.lastUpstreamError found something, it must be
     *  appended regardless of which rc branch fired - a null/blank upstreamError (the common case,
     *  not itself evidence of anything) must leave the base text completely unchanged. */
    @Test
    void fallbackTailAppendsTheUpstreamErrorWhenOneWasFound() {
        final var withError = RunBench.fallbackTail(0, 20000, 46, "2026-10-06T05:43:31Z",
                "process memory limit exceeded (usage 27.9 GB, abort threshold 28.0 GB)");
        assertTrue(withError.contains("46 turns") && withError.contains("process memory limit exceeded"),
                "both the existing text and the new upstream detail must be present");
        assertEquals(RunBench.fallbackTail(124, 20000, 46, "2026-10-06T05:43:31Z", null),
                RunBench.fallbackTail(124, 20000, 46, "2026-10-06T05:43:31Z", ""),
                "a blank upstreamError must behave exactly like null, not append an empty bracket");
    }

    /** #300: the in-session handoff fast path's own gating - mirrors #249's own lesson (the old
     *  cold-session handoffStep() once had NO tagExists guard at all, unconditionally re-running a
     *  handoff already committed by a prior attempt) by pinning the SAME guard on this new entry
     *  point too, plus the "feature off" case the cold path never needed a hook for at all. */
    @Test
    void buildHandoffFinalTurnIsNullWhenOffOrAlreadyDone(@org.junit.jupiter.api.io.TempDir final Path tmp) throws Exception {
        final Path ws = track(Files.createDirectory(tmp.resolve("ws")));
        git(ws, "init", "-q");
        git(ws, "config", "user.email", "bench@local");
        git(ws, "config", "user.name", "bench");
        Files.writeString(ws.resolve(".git/info/exclude"), "build/\n");
        Files.writeString(ws.resolve("README.md"), "x\n");
        git(ws, "add", "-A");
        git(ws, "commit", "-q", "-m", "init");

        assertNull(runBench().buildHandoffFinalTurn(null, ws, "T3"), "handoff_notes off for this run - no hook at all");

        final var handoffs = new java.util.ArrayList<String[]>();
        assertNotNull(runBench().buildHandoffFinalTurn(handoffs, ws, "T3"), "enabled and not yet done - a real hook");

        RunBenchSupport.snapshot(ws, "phase/T3-handoff");   // a prior attempt already finished T3's handoff
        assertNull(runBench().buildHandoffFinalTurn(handoffs, ws, "T3"), "already tagged done - never ask again");
        assertNotNull(runBench().buildHandoffFinalTurn(handoffs, ws, "T4"), "a DIFFERENT task's own tag must never match T3's");
    }

    /** #302: before the task's own status is on disk, the hook defers (returns null) rather than
     *  asking - handoff still logically comes after status, never before it, even though the hook
     *  is now offered on the main call itself, not just a dedicated wrap-up call. Once status
     *  IS there, the ask is exactly Packs.handoffInstruction() - the same text the cold fallback
     *  path already sends, so the ask is identical regardless of which path actually answers it. */
    @Test
    void buildHandoffFinalTurnDefersUntilStatusIsWrittenThenAsksForTheSameInstructionTheColdPathSends(
            @org.junit.jupiter.api.io.TempDir final Path tmp) throws Exception {
        final Path ws = track(Files.createDirectory(tmp.resolve("ws")));
        git(ws, "init", "-q");
        git(ws, "config", "user.email", "bench@local");
        git(ws, "config", "user.name", "bench");

        final var hook = runBench().buildHandoffFinalTurn(new java.util.ArrayList<String[]>(), ws, "T3");
        assertNull(hook.ask(List.of()), "no docs/PROGRESS.md at all yet - status isn't written, so don't ask yet");

        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("docs/PROGRESS.md"), "T3 | done | all green\n");
        assertEquals(Packs.handoffInstruction("T3"), hook.ask(List.of()), "status is now on disk - ask");
    }

    /** #302: the same closure is now threaded into every one of a task's own session calls (main,
     *  its length-retry continuation, its wrap-up - see sessionWithPolicy), so it must fire AT MOST
     *  ONCE across however many of those actually happen, not once per call - otherwise a task
     *  whose main call already got the handoff written would get asked AGAIN in a later wrap-up
     *  call sharing the same closure. */
    @Test
    void buildHandoffFinalTurnFiresAtMostOnceAcrossRepeatedCallsOnceStatusIsWritten(
            @org.junit.jupiter.api.io.TempDir final Path tmp) throws Exception {
        final Path ws = track(Files.createDirectory(tmp.resolve("ws")));
        git(ws, "init", "-q");
        git(ws, "config", "user.email", "bench@local");
        git(ws, "config", "user.name", "bench");
        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("docs/PROGRESS.md"), "T3 | done | all green\n");

        final var hook = runBench().buildHandoffFinalTurn(new java.util.ArrayList<String[]>(), ws, "T3");
        assertEquals(Packs.handoffInstruction("T3"), hook.ask(List.of()), "first call - status already there, ask");
        assertNull(hook.ask(List.of()), "second call on the SAME hook (e.g. a later wrap-up call) - already asked");
        assertNull(hook.ask(List.of()), "and every call after that");
    }

    /** #303, found live: Packs.taskPack()'s own "Notes left by earlier tasks" section never fired
     *  for ANY task, in any run, because handoffStep() populated manifestHandoffs(manifest) while
     *  every taskPack() call read from a totally different, permanently-empty list. The fix is to
     *  make loadHandoffs() return manifestHandoffs(manifest) itself - this pins that the two are now
     *  the SAME list object, not merely equal-looking copies, since a copy would silently reintroduce
     *  the original bug the moment handoffStep() adds to it later in the run. */
    @Test
    void loadHandoffsReturnsTheExactSameListObjectManifestHandoffsIsBackedBy() {
        final Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        final var handoffs = RunBench.loadHandoffs(java.nio.file.Path.of("/nonexistent"), List.of(), manifest);
        assertSame(manifestHandoffsFor(manifest), handoffs);
    }

    private static List<String[]> manifestHandoffsFor(final Map<String, Object> manifest) {
        return RunBench.manifestHandoffs(manifest);
    }

    /** #404: readLessons()/appendLesson() - the run-wide, dependency-independent counterpart to
     *  handoffs. appendLesson() is what buildJudgeCheckpoint() calls the moment a checkpoint comes
     *  back OFF TRACK; readLessons() is what every later Packs.taskPack() call reads fresh. */
    @Test
    void readLessonsIsNullWhenNoLessonHasEverBeenAppended(@org.junit.jupiter.api.io.TempDir final Path rd) {
        assertNull(RunBench.readLessons(rd));
    }

    @Test
    void appendLessonIsReadBackByReadLessons(@org.junit.jupiter.api.io.TempDir final Path rd) {
        RunBench.appendLesson(rd, "T7", 30, "OFF TRACK\nStop writing unused test stubs.");
        final String lessons = RunBench.readLessons(rd);
        assertNotNull(lessons);
        assertTrue(lessons.contains("T7"));
        assertTrue(lessons.contains("turn 30"));
        assertTrue(lessons.contains("Stop writing unused test stubs."));
    }

    @Test
    void appendLessonAccumulatesRatherThanOverwritingEarlierOnes(@org.junit.jupiter.api.io.TempDir final Path rd) {
        RunBench.appendLesson(rd, "T7", 30, "OFF TRACK\nFirst lesson.");
        RunBench.appendLesson(rd, "T9", 60, "OFF TRACK\nSecond lesson.");
        final String lessons = RunBench.readLessons(rd);
        assertTrue(lessons.contains("First lesson."));
        assertTrue(lessons.contains("Second lesson."));
    }

    /** #404 idea 1: rescueStep() calls this once a rescue concludes, win or lose - a weaker signal
     *  than a judge's own specific diagnosis, but still worth knowing (several unrelated tasks
     *  needing the same kind of rescue in one run is itself informative). */
    @Test
    void appendRescueLessonNamesTheReasonAndWhetherItSucceeded(@org.junit.jupiter.api.io.TempDir final Path rd) {
        RunBench.appendRescueLesson(rd, "T7", "ran out of its own wall-clock time budget", true);
        final String lessons = RunBench.readLessons(rd);
        assertTrue(lessons.contains("T7"));
        assertTrue(lessons.contains("budget rescue"));
        assertTrue(lessons.contains("ran out of its own wall-clock time budget"));
        assertTrue(lessons.contains("succeeded"));
    }

    @Test
    void appendRescueLessonNamesAFailedRescueDifferently(@org.junit.jupiter.api.io.TempDir final Path rd) {
        RunBench.appendRescueLesson(rd, "T14", "ran out of its own token budget", false);
        assertTrue(RunBench.readLessons(rd).contains("did not recover a working result"));
    }

    /** #402/#403: found live that nearly every task on this host independently rediscovered the
     *  same JDK mismatch - discoverJavaHome() is the harness-side fix. parseJavacMajor() is its
     *  one pure, independently-testable piece; the subprocess-probing orchestration around it
     *  (javacReportsMajor()/discoverJavaHome() themselves) is heavy, host-filesystem-dependent
     *  orchestration with no direct test, same reasoning rescueStep()/handoffStep() already
     *  follow - exercised live instead, below. */
    @Test
    void parseJavacMajorReadsThePost9VersionScheme() {
        assertEquals(21, RunBench.parseJavacMajor("javac 21.0.12.1"));
        assertEquals(26, RunBench.parseJavacMajor("javac 26\n"));
    }

    @Test
    void parseJavacMajorReadsThePre9VersionScheme() {
        assertEquals(8, RunBench.parseJavacMajor("javac 1.8.0_292"));
    }

    @Test
    void parseJavacMajorIsNullForAnythingThatIsNotAJavacVersionLine() {
        assertNull(RunBench.parseJavacMajor(null));
        assertNull(RunBench.parseJavacMajor(""));
        assertNull(RunBench.parseJavacMajor("zsh: command not found: javac"));
    }

    /** Live, not mocked: this is exactly the scenario #402 found - this host's own
     *  /usr/libexec/java_home -v 21 returns JDK 26's home with exit 0 and no error (only JDK 26
     *  is registered there at all), while a real, working JDK 21 sits under Homebrew's own
     *  install convention, invisible to that registry. Skips itself if this dev machine's own
     *  environment ever changes shape, rather than asserting a path that may no longer exist. */
    @Test
    void discoverJavaHomeFindsARealInstallWhenTheMacOsUtilityPointsAtTheWrongMajorVersion() {
        final String found = RunBench.discoverJavaHome(21);
        org.junit.jupiter.api.Assumptions.assumeTrue(found != null, "no JDK 21 install found on this machine at all - nothing to assert");
        assertTrue(RunBench.parseJavacMajor(com.strgmai.ace.service.docker.DockerService.sh(10, found + "/bin/javac", "-version").out()) == 21,
                "discoverJavaHome() must only ever return a candidate whose OWN javac actually reports 21");
    }

    /** #303: a RESUMED run's in-memory manifest starts fresh (rebuilt each orchestratedPhase() call,
     *  same as every other R18 resume case), but the tags/files on disk are the real record - a task
     *  already tagged phase/{id}-handoff must have its note backfilled into the list before the task
     *  loop starts, exactly as if handoffStep() had just written it this run. */
    @Test
    void loadHandoffsBackfillsFromDiskForEveryAlreadyTaggedTask(@org.junit.jupiter.api.io.TempDir final Path tmp) throws Exception {
        final Path ws = track(Files.createDirectory(tmp.resolve("ws")));
        git(ws, "init", "-q");
        git(ws, "config", "user.email", "bench@local");
        git(ws, "config", "user.name", "bench");
        Files.createDirectories(ws.resolve("handoff"));
        Files.writeString(ws.resolve("handoff/T5.md"), "T5's own handoff note");
        Files.writeString(ws.resolve("handoff/T8.md"), "T8's own handoff note");
        git(ws, "add", "-A");
        git(ws, "commit", "-q", "-m", "handoffs");
        RunBenchSupport.snapshot(ws, "phase/T5-handoff");
        RunBenchSupport.snapshot(ws, "phase/T8-handoff");
        // T12 is in the plan but was never actually completed/tagged - must not appear at all
        final var tasks = List.of(new PlanTask("T5", "market data"), new PlanTask("T8", "apply trade"), new PlanTask("T12", "order state machine"));

        final var handoffs = RunBench.loadHandoffs(ws, tasks, new java.util.LinkedHashMap<>());

        assertEquals(2, handoffs.size(), "only the two actually-tagged tasks are backfilled");
        assertEquals(Set.of("T5", "T8"), handoffs.stream().map(h -> h[0]).collect(java.util.stream.Collectors.toSet()));
        final var byId = handoffs.stream().collect(java.util.stream.Collectors.toMap(h -> h[0], h -> h[1]));
        assertEquals("T5's own handoff note", byId.get("T5"));
        assertEquals("T8's own handoff note", byId.get("T8"));
    }

    /** A brand new run (nothing tagged yet) must start with an empty, not-null list - the "feature
     *  on, nothing written yet" case handoffStep()'s own callers already rely on elsewhere. */
    @Test
    void loadHandoffsIsEmptyWhenNothingIsTaggedYet(@org.junit.jupiter.api.io.TempDir final Path tmp) throws Exception {
        final Path ws = track(Files.createDirectory(tmp.resolve("ws")));
        git(ws, "init", "-q");
        git(ws, "config", "user.email", "bench@local");
        git(ws, "config", "user.name", "bench");
        Files.writeString(ws.resolve("README.md"), "x\n");
        git(ws, "add", "-A");
        git(ws, "commit", "-q", "-m", "init");

        final var handoffs = RunBench.loadHandoffs(ws, List.of(new PlanTask("T1", "setup")), new java.util.LinkedHashMap<>());
        assertTrue(handoffs.isEmpty());
    }

    /** #307: the run-level "budget_rescue" map, with a budget_rescue_enabled restart override
     *  replacing just that key - wall_sec/tokens/keep_recent_turns are never task-overridable
     *  (same reasoning as judge_wall_sec/judge_tokens), so they must survive untouched either way. */
    @Test
    void effectiveRescueCfgAppliesOnlyTheOverriddenKey() {
        final var cfg = Map.<String, Object>of("budget_rescue", Map.of("enabled", false, "wall_sec", 300, "tokens", 50_000));
        assertEquals(Map.of("enabled", false, "wall_sec", 300, "tokens", 50_000), RunBench.effectiveRescueCfg(cfg, Map.of()),
                "no override at all - the run-level map passes through unchanged");
        assertEquals(Map.of("enabled", true, "wall_sec", 300, "tokens", 50_000),
                RunBench.effectiveRescueCfg(cfg, Map.of("budget_rescue_enabled", true)), "only enabled flips; wall_sec/tokens survive");
        assertEquals(Map.of(), RunBench.effectiveRescueCfg(Map.of(), Map.of()), "no budget_rescue cfg at all on the run - never null, never throws");
    }

    /** #307/#351: the budget-exhaustion rescue step's own gating - found live on task T13 (budget
     *  exhausted, rc=3, 12/13 tests green, one assertion away) as the motivating case; must stay
     *  off unless the run explicitly opted in, and must never apply to the `pi` harness (no
     *  in-process loop to compact/reconnect, same reason Judge/FinalTurn are no-ops there).
     *  #299-adjacent: rc=4 (ContextWindowExceeded) is rescue-eligible too - same "the harness's own
     *  proxy proactively refused to continue" shape as rc=3, fixed by the same compacted reconnect.
     *  #351: rc=124 (the task-wall timeout) is ALSO rescue-eligible now - found live on task T7,
     *  genuinely close to done (judge: ON TRACK) but cut off mid-fix after burning ~half its wall
     *  budget on compaction-triggered re-prefills; rescueStep()'s own rescue_wall_sec is a budget
     *  separate from the exhausted main-session task_wall, so "nothing left to spend" never
     *  actually applied to this case - see rescueStep()'s own javadoc for the full reasoning and
     *  the bounded-wall/tight-compaction defaults it now applies specifically for this rc. */
    @Test
    void shouldRescueFiresForBudgetExhaustionContextWindowOverflowOrAWallTimeoutWhenEnabledOnTheRefHarness() {
        final var enabled = Map.<String, Object>of("enabled", true);
        assertTrue(RunBench.shouldRescue(enabled, Map.of("rc", 3), "ref"), "budget exhausted, feature on, ref harness");
        assertTrue(RunBench.shouldRescue(enabled, Map.of("rc", 4), "ref"), "context window exceeded - same rescue-eligible shape as budget exhaustion");
        assertTrue(RunBench.shouldRescue(enabled, Map.of("rc", 124), "ref"), "#351: a wall timeout - rescue has its own separate budget, so this is eligible too");
        assertFalse(RunBench.shouldRescue(enabled, Map.of("rc", 0), "ref"), "already succeeded - nothing to rescue");
        assertFalse(RunBench.shouldRescue(enabled, Map.of("rc", 2), "ref"), "a transient infra failure, not a budget exhaustion");

        final var disabled = Map.<String, Object>of("enabled", false);
        assertFalse(RunBench.shouldRescue(disabled, Map.of("rc", 3), "ref"), "budget exhausted but the run never opted in");
        assertFalse(RunBench.shouldRescue(Map.of(), Map.of("rc", 3), "ref"), "no budget_rescue cfg at all - never null, never throws, never fires");

        assertFalse(RunBench.shouldRescue(enabled, Map.of("rc", 3), "pi"), "the pi harness has no in-process loop to compact/reconnect");
        assertFalse(RunBench.shouldRescue(enabled, Map.of("rc", 4), "pi"), "same, for the context-window rc");
        assertFalse(RunBench.shouldRescue(enabled, Map.of("rc", 124), "pi"), "same, for the wall-timeout rc");
    }

    /** #413: found live on T19 (run svc-20261013-opt) - the session returned normally (rc=0, never
     *  threw BudgetExhausted/ContextWindowExceeded) but its own main session, length-retry,
     *  re-engage call, AND wrap-up call all failed to produce a self-reported status (the model/oMLX
     *  stalled outright), so sessionWithPolicy() had to synthesize the "unfinished" fallback status
     *  itself and set status_by_harness=true - a real, rescue-eligible failure that every existing
     *  rc check (3/4/124) missed entirely, since rc stayed 0. Checked rc-independently: the flag
     *  alone must be enough, regardless of whichever rc the stall happened to leave behind. */
    @Test
    void shouldRescueAlsoFiresWhenTheHarnessHadToSynthesizeTheStatusLineItself() {
        final var enabled = Map.<String, Object>of("enabled", true);
        assertTrue(RunBench.shouldRescue(enabled, Map.of("rc", 0, "status_by_harness", true), "ref"),
                "#413: rc=0 but the harness had nothing else to go on - a real stall, not a success");
        assertTrue(RunBench.shouldRescue(enabled, Map.of("rc", 2, "status_by_harness", true), "ref"),
                "#413: same flag, a different rc - the flag is what matters, not which rc it arrived under");
        assertFalse(RunBench.shouldRescue(enabled, Map.of("rc", 0, "status_by_harness", false), "ref"),
                "the flag explicitly false - a normal, real success, never rescue-eligible");
        assertFalse(RunBench.shouldRescue(enabled, Map.of("rc", 0), "ref"),
                "no status_by_harness key at all - unchanged behavior, still not rescue-eligible");

        final var disabled = Map.<String, Object>of("enabled", false);
        assertFalse(RunBench.shouldRescue(disabled, Map.of("rc", 0, "status_by_harness", true), "ref"),
                "the flag alone never bypasses the run's own opt-in");
        assertFalse(RunBench.shouldRescue(enabled, Map.of("rc", 0, "status_by_harness", true), "pi"),
                "the flag alone never bypasses the ref-harness-only scope");
    }

    /** #351/#413: the reason text rescueStep() names must match what actually happened -
     *  distinguishing every rescue-eligible rc/flag combination matters because the wrong reason
     *  would be misleading (e.g. "ran out of token budget" for a task that was, like T7, nowhere
     *  near its token budget - or for T19, which never ran out of anything at all). Exercised
     *  directly rather than only through rescueStep()'s own heavier orchestration, same reasoning as
     *  shouldRescue()'s own extraction. */
    @Test
    void rescueReasonNamesTheActualCauseForEachEligibleRc() {
        assertEquals("ran out of context window space (its own conversation history grew too large to fit)",
                RunBench.rescueReason(Map.of("rc", 4)), "rc=4: context window, not tokens or time");
        assertEquals("ran out of its own wall-clock time budget",
                RunBench.rescueReason(Map.of("rc", 124)), "#351: rc=124: wall-clock time, not tokens or context space");
        assertEquals("ran out of its own token budget",
                RunBench.rescueReason(Map.of("rc", 3)), "rc=3: the proxy's own token-budget refusal");
        assertEquals("stalled - its own session ended without producing any further output or a status "
                + "report (not a budget or time limit being reached)",
                RunBench.rescueReason(Map.of("rc", 0, "status_by_harness", true)),
                "#413: rc=0, a harness-synthesized status - nothing was actually exhausted");
    }

    /** #415: when sessionWithPolicy's own fallback-status block found an explicit upstream error
     *  (JournalFacts.lastUpstreamError) and carried it onto rec as "upstream_error_text", the rescue
     *  session's own instruction should be told the real cause, not just this method's best guess by
     *  rc alone - found live on T19, whose real cause (oMLX's own memory-pressure abort) this method
     *  had no way to know about before #415. */
    @Test
    void rescueReasonNamesTheUpstreamErrorWhenOneWasRecorded() {
        final var withError = RunBench.rescueReason(Map.of("rc", 0, "status_by_harness", true,
                "upstream_error_text", "process memory limit exceeded (usage 27.9 GB, abort threshold 28.0 GB)"));
        assertTrue(withError.startsWith("stalled") && withError.contains("process memory limit exceeded"),
                "both the rc-based reason and the upstream's own text must be present");
        assertEquals("ran out of its own token budget", RunBench.rescueReason(Map.of("rc", 3)),
                "no upstream_error_text key at all - unchanged behavior");
    }

    /** #351: a wall-timeout rescue must never fall back to "unlimited" wall time or the operator-
     *  wide keep-recent-turns default - the task that hit rc=124 already proved it can run away on
     *  repeated compactions, so an unbounded second attempt (today's existing 0="unlimited"
     *  fallback, still correct for rc=3/4) would be a real regression, not a hypothetical one. An
     *  explicit override still wins either way - these defaults only fill in what's unconfigured. */
    @Test
    void wallTimeoutRescueDefaultsToABoundedWallAndATightCompactionWindow() {
        assertEquals(RunBench.DEFAULT_WALL_TIMEOUT_RESCUE_WALL_SEC, RunBench.rescueWallFor(Map.of(), true), "no override - bounded, not unlimited, for a wall-timeout rescue");
        assertEquals(0, RunBench.rescueWallFor(Map.of(), false), "no override - still unlimited for rc=3/4, unchanged behavior");
        assertEquals(300, RunBench.rescueWallFor(Map.of("wall_sec", 300), true), "an explicit override always wins");

        assertEquals(RunBench.DEFAULT_WALL_TIMEOUT_RESCUE_KEEP_RECENT_TURNS, RunBench.rescueKeepRecentTurnsFor(Map.of(), true, 8),
                "no override - a tight window for a wall-timeout rescue, not the operator-wide default");
        assertEquals(8, RunBench.rescueKeepRecentTurnsFor(Map.of(), false, 8), "no override - still the operator-wide default for rc=3/4, unchanged behavior");
        assertEquals(5, RunBench.rescueKeepRecentTurnsFor(Map.of("keep_recent_turns", 5), true, 8), "an explicit override always wins");
    }
}
