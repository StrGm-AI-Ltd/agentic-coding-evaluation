package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.PiAgent;
import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.docker.DockerService;
import com.strgmai.ace.service.metrics.Trajectory;
import com.strgmai.ace.service.oracle.RunOracle;
import com.strgmai.ace.service.pack.Packs;
import com.strgmai.ace.service.plan.PlanError;
import com.strgmai.ace.service.plan.PlanParser;
import com.strgmai.ace.service.plan.PlanTask;
import com.strgmai.ace.service.proxy.RecordingProxy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Port of runner/run_bench.py's run_once — now at full scope. Isolation: each run gets a fresh
 *  workspace + scrubbed HOME with the Docker shim on PATH; snapshots are committed per phase and
 *  the manifest (OUTSIDE the workspace) carries the SHAs. Step 0 probes the window this setup
 *  offers and derives every knob from it. Both modes: monolithic (one growing session, structural
 *  compaction at the trigger) and orchestrated (stable pack + task packs, parallel waves in
 *  worktrees with per-task journals and a merge, a FIX step when the merge broke something, an
 *  integration task, handoff notes, a wrap-up stop protocol, the length-finish continuation). End
 *  of run: optional self/trajectory reviews, the oracle (build checks in the pinned container,
 *  the compose runtime checks, the black-box suite), trajectory metrics, validity. */
@Component
public class RunBench {
    private static final Logger log = LoggerFactory.getLogger(RunBench.class);
    private final BenchProperties props;
    private final ReferenceAgent agent;
    private final PiAgent piAgent;
    private final RecordingProxyFactory proxies;
    private final RunOracle oracle;
    private final Reviews reviews;
    private final ContextProbe probe;
    private final Judge judge;
    private final Intervene intervene;
    private final PauseAfterTask pauseAfterTask;
    private final ObjectMapper json = new ObjectMapper();

    public RunBench(BenchProperties props, ReferenceAgent agent, PiAgent piAgent, RecordingProxyFactory proxies, RunOracle oracle, Reviews reviews, ContextProbe probe, Judge judge, Intervene intervene, PauseAfterTask pauseAfterTask) {
        this.props = props; this.agent = agent; this.piAgent = piAgent; this.proxies = proxies; this.oracle = oracle; this.reviews = reviews; this.probe = probe; this.judge = judge; this.intervene = intervene; this.pauseAfterTask = pauseAfterTask;
    }

    //todo phases should not be hardcoded
    public static final List<String> PHASES = List.of("p0_definition", "p1_plan", "p2_implementation");
    static final Map<String, List<String>> RUNG_PHASES = Map.of("L7_full_platform", List.of("p0_definition", "p1_plan", "p2_implementation"),
            "L3p_point_in_time", List.of("p1_plan", "p2_implementation"));

    static List<String> phasesFor(String task) { return RUNG_PHASES.getOrDefault(task, List.of("implement")); }

    /** Found live 2026-10-03: --phases (JobCfgFactory's cfg "phases") restricts a rung's own
     *  multi-phase sequence to an explicit comma-separated subset - e.g. resuming past an
     *  already-completed plan to re-run just the implementation - unset/blank means every phase the
     *  rung defines, matching queue.py's own "all" default. Package-private: directly unit-tested. */
    static List<String> phasesWanted(final Map<String, Object> cfg, final List<String> rungPhases) {
        final Object v = cfg.get("phases");
        if (!(v instanceof String s) || s.isBlank()) return rungPhases;
        final Set<String> wanted = Set.of(s.split(","));
        return rungPhases.stream().filter(wanted::contains).toList();
    }

    /** p1_plan/p2_implementation always run in orchestrated mode on a rung that defines them: p1_plan
     *  feeds docs/IMPLEMENTATION_PLAN.md, which every orchestration step depends on. --phases
     *  deliberately excluding either here was never exercised even in the original Python harness (no
     *  test covers it) - failing loudly beats silently ignoring the operator's choice (only
     *  p0_definition is optional); a rung that never had p1_plan/p2_implementation to begin with
     *  (phasesFor() falls back to "implement") is unaffected either way. Package-private: directly
     *  unit-tested. */
    static void requirePlanAndImplementationPhases(final List<String> rungPhases, final List<String> wanted, final String task) {
        if (rungPhases.contains("p1_plan") && !wanted.contains("p1_plan")
                || rungPhases.contains("p2_implementation") && !wanted.contains("p2_implementation"))
            throw new IllegalStateException("orchestrated mode always runs p1_plan and p2_implementation; "
                    + "--phases cannot exclude either (only p0_definition is optional): task=" + task + " phases=" + wanted);
    }

    /** #235: an agent that never writes (or writes an unparseable) docs/IMPLEMENTATION_PLAN.md is a
     *  real, if unproductive, model outcome - not a reason to crash the whole run. Matches Python's
     *  orchestrate(): record the failure in manifest.plan.error (truncated to 200 chars, same as
     *  Python's str(e)[:200]) rather than letting PlanError propagate. Validity.java's orchestrated
     *  branch already reads this key and marks the run INVALID ("plan unparseable"). Package-private:
     *  directly unit-tested. */
    static Map<String, Object> planErrorEntry(final String planSource, final PlanError e) {
        final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        return Map.of("source", planSource, "error", msg.substring(0, Math.min(200, msg.length())), "tasks", List.of());
    }

    static final String IMPLEMENT_TEXT = "Read task/PROMPT.md and do exactly what it asks. Implement it, write tests proving the acceptance "
            + "criteria, run them until green, and record what you did and how you verified it in docs/PROGRESS.md.";
    public static final Map<String, String[]> PHASE_TEXT = Map.of(
            "p0_definition", new String[]{"docs/TASK_DEFINITION.md", "Read task/PROMPT.md. Do PHASE 0 ONLY: write docs/TASK_DEFINITION.md. Write no code."},
            "p1_plan", new String[]{"docs/IMPLEMENTATION_PLAN.md", "Read task/PROMPT.md and docs/TASK_DEFINITION.md. Do PHASE 1 ONLY: write docs/IMPLEMENTATION_PLAN.md as ordered subtasks with ids (T1, T2, ...), each with a goal, dependencies, and a verifiable acceptance criterion. Write no code."},
            "p2_implementation", new String[]{"docs/PROGRESS.md", "Read task/PROMPT.md and docs/IMPLEMENTATION_PLAN.md. Do PHASE 2: implement subtasks one at a time in dependency order; for each, implement it, write tests proving its acceptance criterion, run them until green, then append the result to docs/PROGRESS.md. Continue until every subtask is done."},
            "implement", new String[]{"docs/PROGRESS.md", IMPLEMENT_TEXT});

    static String nowIso() { return Instant.now().toString(); }

    /** `T3 | done | ...` / `T3 | blocked | ...` status lines the task instruction asks for.
     *  Excludes the harness's own synthetic fallback line (Packs.fallbackStatus) - this method
     *  decides whether that fallback still needs to be WRITTEN, so it must never recognize its own
     *  past output as an already-written report. See reportedStatus() for the UI-facing twin that
     *  DOES want to see it - there it is informative, not something to avoid recognizing. */
    static String progressStatus(Path ws, final String tid) {
        final var r = scanProgressStatus(ws, tid, true);
        return r == null ? null : r.status();
    }

    /** `T3 | status | detail` - the free-text detail half of a self-reported status line: how it
     *  was verified (done), what's missing (partial), why (blocked), or - for the harness's own
     *  "unfinished" fallback - the fixed explanation of what cut the session off. Exactly as
     *  written, trailing "**" bold-close stripped (#257's same leak, a different field). */
    public record ReportedStatus(String status, String detail) {}

    /** #261: the same self-reported status line as progressStatus(), but for DISPLAY - including
     *  the harness's own "unfinished" fallback (Packs.fallbackStatus), unlike progressStatus()
     *  above. An operator watching a run wants to see that a task's own session never got to
     *  self-certify (even its dedicated wrap-up chance came back empty - see this class's own
     *  fallback-writing call sites), not have that silently read the same as a genuine "done" -
     *  and WHY, from the same line's own detail half (#262). */
    public static ReportedStatus reportedStatus(final Path ws, final String tid) {
        return scanProgressStatus(ws, tid, false);
    }

    private static ReportedStatus scanProgressStatus(final Path ws, final String tid, final boolean excludeHarnessFallback) {
        final Path p = ws.resolve("docs/PROGRESS.md");
        if (!Files.isRegularFile(p)) return null;
        try {
            ReportedStatus result = null;
            final Pattern re = Pattern.compile("^\\s*(?:[-*]\\s*)?\\*{0,3}\\s*\\|?\\s*\\*{0,3}" + Pattern.quote(tid)
                    + "\\*{0,3}\\s*\\|\\s*\\*{0,3}(\\w+)\\*{0,3}(?:\\s*\\|\\s*\\*{0,3}(.*))?$", Pattern.CASE_INSENSITIVE);
            for (String line : Files.readAllLines(p)) {
                final java.util.regex.Matcher m = re.matcher(line);
                if (m.find() && !(excludeHarnessFallback && line.contains("budget exhausted before a status was written"))) {
                    final String detail = m.group(2) == null ? "" : m.group(2).strip().replaceAll("\\*{1,3}$", "");
                    result = new ReportedStatus(m.group(1).toLowerCase(), detail);
                }
            }
            return result;
        } catch (Exception e) { return null; }
    }

    /** #263: taskId's own entry in cfg's task_overrides map (set via the restart-task endpoint),
     *  or Map.of() if this task has none - never null, so every call site below can key off it
     *  directly without its own null guard. Looking this up by task id is naturally a no-op for
     *  every non-task session name (p0_definition, p1_plan, INTEGRATION, a FIX id, ...): none of
     *  those ever appear as a key in task_overrides, since only the restart endpoint ever writes
     *  one, and only ever keyed by a real plan task id. */
    static Map<String, Object> taskOverride(final Map<String, Object> cfg, final String taskId) {
        return cfg.get("task_overrides") instanceof Map<?, ?> all && all.get(taskId) instanceof Map<?, ?> o
                ? (Map<String, Object>) o : Map.of();
    }

    static int effectiveInt(final Map<String, Object> override, final String key, final int fallback) {
        return override.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    static long effectiveLong(final Map<String, Object> override, final String key, final long fallback) {
        return override.get(key) instanceof Number n ? n.longValue() : fallback;
    }

    /** #322: compaction_trigger is the same class of per-task-session knob as the wall/token
     *  budgets above (same "a task_override wins over the run-level default" rule), but it has no
     *  single run-level home the way task_wall_sec/task_tokens do - every call site that needs it
     *  (sessionWithPolicy, handoffStep, rescueStep, parallelPlanStep) falls back to its own
     *  cfg.get("compaction_trigger")/props.compactionTrigger() independently, so this takes that
     *  already-resolved run-level value as the fallback rather than re-deriving it. */
    static int effectiveCompactionTrigger(final Map<String, Object> override, final int runLevel) {
        return effectiveInt(override, "compaction_trigger", runLevel);
    }

    /** #322: temperature/top_p/top_k/repetition_penalty/max_tokens/reasoning_effort are per-call
     *  sampler knobs, pinned run-wide once as cfg's "_sampler_overrides" (RecordingProxy.
     *  SamplerOverrides) and reused at every proxies.start(...) call site. A task_override for any
     *  of them replaces just that field for this one task's own calls (main session, its
     *  continuations, its handoff, its rescue) - firstTokenTimeoutSec/contextWindow are left as
     *  the run-level value in both cases, neither is task-overridable (the former has its own,
     *  separate agent-loop-side override already; the latter is a fact about the model server's
     *  own capacity, not a per-task choice). */
    static RecordingProxy.SamplerOverrides effectiveSamplerOverrides(final Map<String, Object> cfg, final Map<String, Object> override) {
        final RecordingProxy.SamplerOverrides base = cfg.get("_sampler_overrides") instanceof RecordingProxy.SamplerOverrides so
                ? so : RecordingProxy.SamplerOverrides.NONE;
        if (override.isEmpty()) return base;
        return new RecordingProxy.SamplerOverrides(
                override.get("temperature") instanceof Number t ? t.doubleValue() : base.temperature(),
                override.get("top_p") instanceof Number tp ? tp.doubleValue() : base.topP(),
                override.get("top_k") instanceof Number tk ? tk.intValue() : base.topK(),
                override.get("repetition_penalty") instanceof Number rp ? rp.doubleValue() : base.repetitionPenalty(),
                override.get("max_tokens") instanceof Number mt ? mt.intValue() : base.maxOutputTokens(),
                override.get("reasoning_effort") instanceof String re && !re.isBlank() ? re : base.reasoningEffort(),
                base.firstTokenTimeoutSec(), base.contextWindow());
    }

    /** #258: a session that died on a `length` finish gets one continuation with what is LEFT of
     *  its budget - rc==124 (already wall-timed-out) is excluded since there is nothing left to
     *  continue INTO, and wall<=0 means unlimited (the system-wide "0 = no budget" convention, so
     *  there's always "enough" remaining regardless of elapsedSec). No longer gated on session
     *  kind - p0_definition/p1_plan face the exact same per-call output ceiling as a task session,
     *  so excluding them (as this used to) just meant a truncated plan-writing turn silently
     *  killed the whole run instead of getting the same recovery chance a task already had. */
    static boolean shouldRetryOnLengthFinish(final String finish, final int rc, final long wall, final long elapsedSec) {
        return "length".equals(finish) && rc != 124 && (wall <= 0 || wall - elapsedSec > 120);
    }

    /** Found live: a session that stops with no tool call ("stop" finish) and never reports a
     *  status either genuinely finished without writing the line, or lost the thread mid-reasoning
     *  (a single ~25k-token turn trailed off mid-sentence with 75% of its token budget and 88% of
     *  its wall still unspent). The wrap-up below tells the agent to STOP and just report - exactly
     *  wrong when real budget remains, since it throws away the room to actually finish. Re-engage
     *  instead, gated on at least a fifth of BOTH budgets still being left - not worth a whole extra
     *  call otherwise, same reasoning as shouldRetryOnLengthFinish's own 120s floor. rc==124 (already
     *  wall-timed-out) is excluded the same way: nothing left to continue into. */
    static boolean shouldReEngageOnEmptyStop(final String finish, final int rc, final long wall, final long elapsedSec,
                                             final long tokens, final long spent) {
        if (!"stop".equals(finish) || rc == 124) return false;
        final boolean wallOk = wall <= 0 || wall - elapsedSec > wall * 0.2;
        final boolean tokensOk = tokens <= 0 || tokens - spent > tokens * 0.2;
        return wallOk && tokensOk;
    }

    /** #265: the run-level "judge" cfg map, with any judge_enabled/judge_every_n_turns/judge_model
     *  restart override (RESTART_OVERRIDE_KEYS' own flat names) replacing the corresponding nested
     *  key. judge_wall_sec/judge_tokens are deliberately NOT task-overridable - the judge's own
     *  session budget is a run/operator-level choice, not exposed on the restart form. */
    static Map<String, Object> effectiveJudgeCfg(final Map<String, Object> cfg, final Map<String, Object> taskOverride) {
        final Map<String, Object> runLevel = cfg.get("judge") instanceof Map<?, ?> j ? (Map<String, Object>) j : Map.of();
        final Map<String, Object> out = new LinkedHashMap<>(runLevel);
        if (taskOverride.get("judge_enabled") instanceof Boolean b) out.put("enabled", b);
        if (taskOverride.get("judge_every_n_turns") instanceof Number n) out.put("every_n_turns", n.intValue());
        if (taskOverride.get("judge_model") instanceof String s && !s.isBlank()) out.put("model", s);
        return out;
    }

    /** #265: whether `turn` is due for a judge check, given how many turns have passed since the
     *  last one and the CURRENT interval (which #299's adaptive logic below can shrink/grow over
     *  a task's own lifetime - no longer a fixed everyN, so a stateless modulo check can't express
     *  it). interval&lt;=0 means every turn (0/negative is already rejected by RunSpec's own
     *  validation before this is ever built from a real run, but a restart override is validated
     *  far more loosely, so this stays safe rather than risking a divide-by-zero on a hand-crafted
     *  0 - there is no division here at all now, but the same degenerate input must still mean
     *  "every turn", not "never"). */
    static boolean dueForJudgeCheck(final int turn, final int lastCheckedTurn, final int interval) {
        return interval <= 0 || turn - lastCheckedTurn >= interval;
    }

    /** #402/#404: the first judge check fires at whichever is smaller, this or the operator's own
     *  everyN - a reconnaissance-only start or dead-scaffolding pattern, both found live
     *  repeatedly, would otherwise burn the whole default cadence before anything catches them at
     *  all. Never WIDENS the operator's own everyN (Math.min) - and interval<=0 ("every turn",
     *  see dueForJudgeCheck's own degenerate-input handling) still wins outright either way. #299's
     *  own adaptive doubling (nextJudgeInterval) already grows it back to everyN on the first ON
     *  TRACK verdict, so there's no separate "back to normal" step needed. */
    static final int FIRST_JUDGE_CHECK_TURN = 5;
    static int firstJudgeInterval(final int everyN) { return Math.min(FIRST_JUDGE_CHECK_TURN, everyN); }

    /** #299: the next interval after a check - halved (floor 1) the moment the judge calls the
     *  task off track, so a drifting session gets re-checked sooner and sooner the longer it
     *  stays off track; doubled back up (capped at the run's own configured everyN - never looser
     *  than what the operator actually asked for) once it reports on track again. Symmetric
     *  recovery rather than snapping straight back to everyN on the first good verdict: the issue
     *  itself only specifies the tightening direction, and a hard reset risks flapping (tighten,
     *  reset, tighten, reset) if the agent's own trajectory oscillates right at the boundary. */
    static int nextJudgeInterval(final int currentInterval, final int everyN, final boolean offTrack) {
        return offTrack ? Math.max(1, currentInterval / 2) : Math.min(everyN, currentInterval * 2);
    }

    /** builds this task's own per-turn checkpoint - an operator's own mid-task intervention
     *  (Intervene) ALWAYS takes priority and always gets checked, every turn, regardless of
     *  whether this task's own judge is enabled; the judge logic below it only ever runs when
     *  nothing is pending. #299: the interval starts at the configured everyN and adapts turn by
     *  turn via nextJudgeInterval() above, based on the verdict Judge.isOffTrack() reads off each
     *  real check's own recommendation - skipped (interval left unchanged) on a null
     *  recommendation, which means the judge call itself failed (Judge.checkpoint()'s own
     *  try/catch), not that it found anything to report. */
    private ReferenceAgent.JudgeCheckpoint buildJudgeCheckpoint(final Map<String, Object> cfg, final String runId, final Path ws, final Path rd,
            final String taskId, final String goal, final long effWall, final long effTokens, final Map<String, Object> taskOverride) {
        final Map<String, Object> judgeCfg = effectiveJudgeCfg(cfg, taskOverride);
        final boolean judgeEnabled = Boolean.TRUE.equals(judgeCfg.get("enabled"));
        final int everyN = judgeCfg.get("every_n_turns") instanceof Number n ? n.intValue() : 10;
        final ReferenceAgent.JudgeCheckpoint raw = judgeEnabled
                ? judge.checkpoint(cfg, runId, ws, rd, proxies, taskId, goal, effWall, effTokens, System.currentTimeMillis(), judgeCfg) : null;
        final int[] lastCheckedTurn = {0};
        final int[] interval = {firstJudgeInterval(everyN)};   // #402/#404: see its own javadoc
        return (turn, trajectory) -> {
            final String intervention = intervene.poll(runId, taskId);
            if (intervention != null) return intervention;
            if (raw == null || !dueForJudgeCheck(turn, lastCheckedTurn[0], interval[0])) return null;
            lastCheckedTurn[0] = turn;
            final String recommendation = raw.check(turn, trajectory);
            if (recommendation != null) {
                final boolean offTrack = Judge.isOffTrack(recommendation);
                final int next = nextJudgeInterval(interval[0], everyN, offTrack);
                if (next != interval[0])
                    log.info("run {}: judge interval for task {} {} {} -> {} turns", runId, taskId, offTrack ? "tightened" : "relaxed", interval[0], next);
                interval[0] = next;
                if (offTrack) appendLesson(rd, taskId, turn, recommendation);
            }
            return recommendation;
        };
    }

    /** #300/#302: the task's own in-session handoff ask - null when handoff_notes is off for this
     *  run (`handoffs`, built by the caller from that cfg flag - see its own declaration), or this
     *  task's handoff is already done (same tagExists guard handoffStep() itself checks first). In
     *  those null cases nothing changes - a call site simply never gets a real hook.
     *
     *  Otherwise, the SAME closure is threaded into every one of this task's own session calls
     *  (main, its length-retry continuation, its wrap-up - see sessionWithPolicy) rather than just
     *  the wrap-up call: `asked` makes it fire AT MOST ONCE across however many of those actually
     *  happen for this one task, and the `progressStatus(ws, taskId) == null` check defers it until
     *  the task's own self-reported status line is ALREADY on disk - handoff still logically comes
     *  after status, never before it, same as the original wrap-up-only design, but now it fires
     *  on whichever of those three calls turns out to genuinely be last: the main call itself, if
     *  the task self-reports status without ever needing a wrap-up (found live on T9 - no wrap-up
     *  ran, so the old wrap-up-only wiring left it with no in-session chance at all and fell all the
     *  way back to the cold continueSession=true session); the wrap-up call otherwise, exactly as
     *  before. ReferenceAgent.run()'s own `!"length".equals(finish)` guard (see its javadoc) keeps a
     *  truncated response on any of those calls from ever reaching this at all. The instruction text
     *  is the exact same Packs.handoffInstruction() the cold fallback path in handoffStep() already
     *  sends, so the ask itself is identical regardless of which path actually answers it. */
    ReferenceAgent.FinalTurn buildHandoffFinalTurn(final List<String[]> handoffs, final Path ws, final String taskId) {
        if (handoffs == null || RunBenchSupport.tagExists(ws, "phase/" + taskId + "-handoff")) return null;
        final boolean[] asked = {false};
        return trajectory -> {
            if (asked[0] || progressStatus(ws, taskId) == null) return null;
            asked[0] = true;
            return Packs.handoffInstruction(taskId);
        };
    }

    /** #262, found live: the harness's own fallback status line always carried an empty tail, so
     *  "budget exhausted" showed even when the real cause (confirmed live: an oMLX memory-guard
     *  rejection, nothing to do with any configured budget) was available or inferable. rc==2 is
     *  chatWithRetry's own retries-exhausted TransientError (the exact shape an infra rejection
     *  takes); rc==124 is runBounded's own task-wall timeout; anything else reaching here is a
     *  session that returned normally but produced no further output on its last turn (T7's own
     *  actual case) - a real, if rarer, anomaly worth pointing at the journal for, not silence.
     *
     *  #415: `upstreamError` (JournalFacts.lastUpstreamError's result for this window, nullable) is
     *  appended to whichever branch fires above, regardless of rc - found live on T19 (a silent
     *  oMLX memory-pressure abort, "process limit exceeded..." - see the Second Brain ACE note §24)
     *  that this very fallback text used to describe only as "the model produced no further output",
     *  with the real cause recoverable only via manual oMLX server.log archaeology. Most of the time
     *  this is null (a silent stall sends nothing at all, as T19's own case did) - not itself
     *  evidence of anything; when it IS present, it is the single most specific fact this method can
     *  report. */
    static String fallbackTail(final int rc, final long wallSec, final int turns, final String endIso, final String upstreamError) {
        final String base = switch (rc) {
            case 2 -> " (the model's own call failed and its retries were exhausted - see the "
                    + "service log around " + endIso + " for the underlying error, e.g. an "
                    + "upstream model-server rejection)";
            case 124 -> " (task-wall budget of " + wallSec + "s exceeded)";
            default -> " (the model produced no further output after " + turns
                    + " turns - check interactions.jsonl for this session around " + endIso + ")";
        };
        return upstreamError == null || upstreamError.isBlank() ? base : base + " [upstream error: " + upstreamError + "]";
    }

    /** #263: one JSON line per attempt at a plan task's own main session, appended to
     *  rd/task_attempts.jsonl - every run of a task, not just a restarted/overridden one, so a
     *  future "why did this fail" investigation (#261/#262's own motivating case) starts from a
     *  real history instead of manual interactions.jsonl archaeology. Best-effort: a logging
     *  failure here must never fail the run itself.
     *
     *  #337: also carries this attempt's own ordinal (attempt_number, 1-indexed - counts lines
     *  already in the file for this task id, so a crash-resumed or operator-restarted task's
     *  second-or-later go reads as a visibly distinct outcome from a clean first-try pass) and,
     *  when rescueStep() fired on this same attempt, its rescue_succeeded/rescue_rc/rescue_seconds
     *  - rec already carries them (set at RunBench.java:~1526) but this method never copied them
     *  before, so a rescued pass and a clean one wrote identical-looking lines. */
    void logTaskAttempt(final Path rd, final Path ws, final String taskId, final Map<String, Object> override,
                                final int effTaskWall, final long effTaskTokens, final int effMaxTurns, final Map<String, Object> rec) {
        try {
            final Map<String, Object> params = new LinkedHashMap<>();
            params.put("task_wall_sec", effTaskWall);
            params.put("task_tokens", effTaskTokens);
            params.put("max_turns", effMaxTurns);
            // #337-followup: override can carry any of RESTART_OVERRIDE_KEYS (compaction_trigger,
            // judge_*, budget_rescue_enabled, sampler knobs, rescue_*, wrapup_*,
            // first_token_timeout_sec) - every one of them was genuinely in effect for THIS
            // attempt, not just the three hardcoded above (task_wall_sec/task_tokens/max_turns
            // redundantly repeat whatever override already set, since eff* already resolved it)
            params.putAll(override);
            final ReportedStatus reported = reportedStatus(ws, taskId);
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("task_id", taskId);
            entry.put("attempt_number", countPriorAttempts(rd, taskId) + 1);
            entry.put("attempt_started", rec.get("start_iso"));
            entry.put("attempt_ended", rec.get("end_iso"));
            entry.put("params", params);
            entry.put("overridden", !override.isEmpty());
            entry.put("rc", rec.get("rc"));
            entry.put("seconds", rec.get("seconds"));
            entry.put("reported_status", reported == null ? null : reported.status());
            entry.put("reported_detail", reported == null ? null : reported.detail());
            if (rec.get("rescue_succeeded") instanceof Boolean b) entry.put("rescue_succeeded", b);
            if (rec.get("rescue_rc") instanceof Number n) entry.put("rescue_rc", n);
            if (rec.get("rescue_seconds") instanceof Number n) entry.put("rescue_seconds", n);
            Files.writeString(rd.resolve("task_attempts.jsonl"), json.writeValueAsString(entry) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { log.warn("could not log task attempt for {}: {}", taskId, e.toString()); }
    }

    /** #337: how many lines already exist for this task id in THIS run's own task_attempts.jsonl -
     *  the file is append-only (see logTaskAttempt() above), so this is exactly "how many attempts
     *  has this task already had before the one about to be written." Missing/unreadable file, or
     *  an unparseable line, reads as zero/skipped (a brand new run), same as every other
     *  best-effort read in this file. */
    long countPriorAttempts(final Path rd, final String taskId) {
        final Path file = rd.resolve("task_attempts.jsonl");
        if (!Files.isRegularFile(file)) return 0;
        try {
            long count = 0;
            for (final String l : Files.readAllLines(file)) {
                if (l.isBlank()) continue;
                try { if (taskId.equals(json.readTree(l).path("task_id").asText(null))) count++; }
                catch (Exception e) { /* an unparseable line doesn't count as this task's attempt */ }
            }
            return count;
        } catch (Exception e) { return 0; }
    }

    /** the task's own prompt (tasks/&lt;task&gt;/PROMPT.md, vendored as a resource) when it exists,
     *  else the generic default (tasks/PROMPT.md, the port of the Python original's top-level
     *  task/PROMPT.md) - port of run_bench.py's own tp-then-fallback lookup. */
    //todo task shouldn't be simple strings
    InputStream promptResource(String task) {
        final InputStream perTask = getClass().getResourceAsStream("/tasks/" + task + "/PROMPT.md");
        return perTask != null ? perTask : getClass().getResourceAsStream("/tasks/PROMPT.md");
    }

    /** Copies the task's prompt into ws/task/PROMPT.md - every later phase reads task/PROMPT.md
     *  relative to the workspace - and returns its text. ws/task must be created directly: its
     *  PARENT (ws) existing is not enough for the file to be writable into it. */
    String setUpTaskPrompt(Path ws, String task) throws IOException {
        Files.createDirectories(ws.resolve("task"));
        final Path taskPrompt = ws.resolve("task/PROMPT.md");
        try (InputStream promptSrc = promptResource(task)) { Files.copy(promptSrc, taskPrompt, StandardCopyOption.REPLACE_EXISTING); }
        return Files.readString(taskPrompt);
    }

    //todo the run scenario should not be hardcoded
    //todo refactor
    @SuppressWarnings("unchecked")
    public Map<String, Object> runOnce(final Map<String, Object> cfg, final String runId, String task, final String mode, final String planSource) throws Exception {
        final var resultsDir = Path.of((String) cfg.getOrDefault("results_root", props.resultsDir()));
        final Path rd = resultsDir.resolve(runId);
        Files.createDirectories(rd);
        final var wsRoot = Path.of((String) cfg.getOrDefault("workspace_root", props.workspaceRoot())).resolve(runId);
        final Path ws = wsRoot.resolve("workspace"), home = wsRoot.resolve("home");
        Files.createDirectories(home.resolve(".pi/agent"));
        final String promptText = setUpTaskPrompt(ws, task);
        // #228: --java-home (JobCfgFactory -> cfg's "java_home") is a per-run override and must win
        // over the operator-wide BenchProperties default - this used to always overwrite it unconditionally
        if (!(cfg.get("java_home") instanceof String jh) || jh.isBlank()) {
            final int javaMajor = cfg.get("java_major") instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(cfg.getOrDefault("java_major", 21)));
            cfg.put("java_home", props.javaHome() == null || props.javaHome().isBlank() ? discoverJavaHome(javaMajor) : props.javaHome());
        }
        Map<String, Object> capInfo = null;
        try {
            // ---- step 0: probe the window this setup offers, derive every knob (ports apply_context) ----
            Map<String, Object> probeRec = null, derived = Map.of();
            Map<String, Object> probeSansCurve;
            if (Boolean.TRUE.equals(cfg.get("context_probe"))) {
                Map<String, Object> ensured = probe.ensure(props, Boolean.TRUE.equals(cfg.get("context_probe_fresh")),
                        cfg.get("task_wall_sec") instanceof Number n ? n.intValue() : null, null);
                probeRec = (Map<String, Object>) ensured.get("probe");
                derived = (Map<String, Object>) ensured.get("derived");
                capInfo = (Map<String, Object>) ensured.get("cap_info");
                if (derived == null || Boolean.TRUE.equals(derived.get("too_small")))
                    throw new IllegalStateException("context probe failed: " + ensured.getOrDefault("error", "window below context.min_usable: this setup is not benchmarkable as configured"));
                probeSansCurve = new LinkedHashMap<>(probeRec);
                probeSansCurve.remove("curve");
                Files.writeString(rd.resolve("context_probe.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(probeSansCurve));
                Packs.setWindow((Integer) derived.get("usable_context"));
                Packs.setScale(((Number) derived.get("pack_scale")).doubleValue(), Map.of("contract", Packs.CAPS.get("contract")));
                cfg.put("usable_context", derived.get("usable_context"));
                cfg.put("max_output_tokens", derived.get("max_output_tokens"));
                cfg.put("compaction_trigger", derived.get("compaction_trigger_tokens"));
                List<double[]> decodeCurve = new ArrayList<>();   // convert the probe record ONCE (R8's time model)
                for (Object o : (List<?>) probeRec.getOrDefault("curve", List.of())) {
                    final Map<String, Object> c = (Map<String, Object>) o;
                    if ("200".equals(String.valueOf(c.get("status"))) && c.get("prompt_tokens") instanceof Number && c.get("decode_tps") instanceof Number)
                        decodeCurve.add(new double[]{((Number) c.get("prompt_tokens")).doubleValue(), ((Number) c.get("decode_tps")).doubleValue()});
                }
                cfg.put("_decode_curve", decodeCurve);
            } else {
                // the job's own pinned window (queue runs, per arm) wins over the operator's global one.
                // #298: effectiveContextWindow(), not the raw accessor - this codebase's own pervasive
                // "0 = unlimited" convention (every other budget knob throughout this class) means an
                // operator setting ace.context-window: 0 to mean "no cap" expects that, not for the
                // window to collapse to a literal 0; effectiveContextWindow() already treats
                // null-or-non-positive as "unset" so this default applies the same way either case.
                int window = cfg.get("context_window") instanceof Number n ? n.intValue()
                        : props.effectiveContextWindow() == null ? 262144 : props.effectiveContextWindow();
                // Map.of() rejects a null value outright - min_decode_tps IS null here: skipping the probe
                // (step 0's whole job is measuring it) means there is no measured decode throughput
                final Map<String, Object> derivedNoProbe = new LinkedHashMap<>();
                derivedNoProbe.put("usable_context", window);
                derivedNoProbe.put("pack_scale", Math.max(0.25, Math.min(1.0, window / 65536.0)));
                derivedNoProbe.put("max_output_tokens", Math.min(props.maxOutputTokens(), (int) (window * 0.125)));
                derivedNoProbe.put("compaction_trigger_tokens", (int) (window * 0.43));
                derivedNoProbe.put("task_tokens", (int) (window * 1.25 / 1000) * 1000);
                derivedNoProbe.put("min_decode_tps", null);
                derivedNoProbe.put("max_parallel", 1);
                derived = derivedNoProbe;
                Packs.setWindow(window);
                Packs.setScale((Double) derived.get("pack_scale"), Map.of());
                cfg.put("usable_context", window);
                cfg.put("max_output_tokens", derivedNoProbe.get("max_output_tokens"));
            }
            // an explicit --max-tokens is an operator override, not a measurement: it wins over
            // whatever step 0 (probed or not) derived
            if (cfg.get("max_tokens_override") instanceof Number mt) cfg.put("max_output_tokens", mt.intValue());
            // 0 means unlimited (the system-wide "0 = no budget" convention)
            final int firstTokenTimeoutSec = cfg.get("first_token_timeout_sec") instanceof Number n3 ? n3.intValue() : 0;
            cfg.put("first_token_timeout_ms", firstTokenTimeoutSec * 1000L);
            // pinned once per run and reused at every proxies.start(...) call site (RunBench, Reviews) -
            // the run's own sampler knobs (#72): null fields fall back to the operator-wide ace.*
            // default, or are simply omitted from the request when no such default exists (see
            // RecordingProxy.forward()). firstTokenTimeoutSec (#92) is not a sampler knob, but rides the
            // same "one bag of per-run proxy config, pinned once" vehicle rather than a second one -
            // it bounds RecordingProxy's own upstream connect wait, which used to be a hardcoded 290s
            // regardless of what --first-token-timeout was actually set to.
            cfg.put("_sampler_overrides", new RecordingProxy.SamplerOverrides(
                    cfg.get("temperature") instanceof Number t ? t.doubleValue() : null,
                    cfg.get("top_p") instanceof Number tp ? tp.doubleValue() : null,
                    cfg.get("top_k") instanceof Number tk ? tk.intValue() : null,
                    cfg.get("repetition_penalty") instanceof Number rp ? rp.doubleValue() : null,
                    cfg.get("max_output_tokens") instanceof Number mo ? mo.intValue() : null,
                    cfg.get("reasoning_effort") instanceof String re && !re.isBlank() ? re : null,
                    firstTokenTimeoutSec,
                    cfg.get("usable_context") instanceof Number uc ? uc.intValue() : null));
            // 0 means unlimited (the system-wide "0 = no budget" convention)
            final int taskWall = cfg.get("task_wall_sec") instanceof Number n ? n.intValue() : 0;
            long taskTokens = cfg.get("task_tokens") instanceof Number n2 ? n2.longValue()
                    : ((Number) derived.getOrDefault("task_tokens", 0)).longValue();
            if (!(cfg.get("compaction_trigger") instanceof Number)) cfg.put("compaction_trigger", props.compactionTrigger());
            // #236: harness=pi needs its own HOME seeded with a models.json before any session runs -
            // this is the one-time per-run setup (mirrors Python's run_once() setup + fresh_home());
            // the per-call baseUrl patch happens separately, right before each individual pi invocation
            if ("pi".equals(cfg.get("harness")))
                PiAgent.seedHome(home, (String) cfg.getOrDefault("model", props.model()),
                        (Integer) cfg.get("usable_context"), (Integer) cfg.get("max_output_tokens"));

            final Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schema_version", BenchProperties.RESULT_SCHEMA);
            manifest.put("run_id", runId);
            manifest.put("task", task);
            manifest.put("mode", mode);
            manifest.put("plan_source", planSource);
            manifest.put("harness_version", BenchProperties.HARNESS_VERSION);
            manifest.put("started", nowIso());
            manifest.put("derived", derived);
            manifest.put("snapshots", new LinkedHashMap<String, String>());
            manifest.put("phases", new ArrayList<Map<String, Object>>());
            manifest.put("tasks", new ArrayList<Map<String, Object>>());
            manifest.put("waves", new ArrayList<Map<String, Object>>());
            manifest.put("docker_windows", new ArrayList<Map<String, Object>>());
            manifest.put("provenance", Map.of("model", cfg.getOrDefault("model", props.model()), "harness", cfg.getOrDefault("harness", "ref"),
                    "harness_version", BenchProperties.HARNESS_VERSION, "java_home", cfg.get("java_home"),
                    "runner", System.getProperty("ace.build", "ace-service")));
            // was always read for comparability (StatsService.KEY_FIELDS) but never actually written -
            // every run compared equal (both null) on this field, giving it no real protective value.
            // A run-level override replaces ReferenceAgent's own per-phase-kind DEFAULT_REASONING
            // table entirely (design rule 5: "record what was sent, not what was configured");
            // unset stays "default"
            manifest.put("reasoning_policy", cfg.get("reasoning_effort") instanceof String re && !re.isBlank() ? re : "default");
            // #265, found live: this fires on every run() invocation - the original job start AND every
            // resume-after-pause, force-moving the SAME tag each time (RunBenchSupport.snapshot does
            // `git tag -f`) - so it marks "where THIS attempt began," not "the run's true beginning."
            // It was named "phase/start" and so matched nearestPhaseTagSha's own "phase/*" glob,
            // silently winning over the real task-boundary tag (e.g. phase/T3) it's nearer to after any
            // pause/resume - diffAgainstTag (the UI's Changes tab) and Judge.currentDiffSummary() both
            // call nearestPhaseTagSha, so both were silently showing only the diff since the last
            // resume, not the task's real cumulative diff. Named outside the "phase/*" namespace so it
            // can never collide with a real phase/task boundary again.
            ((Map<String, String>) manifest.get("snapshots")).put("start", RunBenchSupport.snapshot(ws, "run/start"));
            Path journal = rd.resolve("interactions.jsonl");

            DockerService.dockerTools(home.toString());   // CLI plugins (R7), before the HOME is copied for parallel tasks
            cfg.put("_agent_env", RunBenchSupport.scrubbedEnv(home.toString(), runId, (String) cfg.get("java_home")));   // isolation reaches the agent

            if ("orchestrated".equals(mode)) orchestratedPhase(cfg, runId, rd, ws, home, journal, manifest, planSource, promptText, taskWall, taskTokens, task);
            else monolithicPhases(cfg, runId, rd, ws, journal, manifest, promptText, task);
            writeManifest(rd, manifest);
            // R17: bundle the agent's actual solution NOW - this is the FINAL bundle (#306's own
            // snapshotRunProgress() already took one after every task/wave along the way, but this is
            // the one that must land AFTER the whole run, not just after the last task). Nothing after
            // this point ever adds anything worth re-bundling for: oracle scoring never commits at all, and
            // Reviews.selfReview()/trajectoryReview() revert any source the reviewer touched (git checkout
            // back to their own pre-review snapshot) before their own commit, so the tracked source there
            // is byte-for-byte identical to what's bundled here already; their own report artifacts
            // (SELF_REVIEW.md, trajectory_review.json, ...) are separately Files.copy()'d straight into rd
            // regardless of git. Bundling here, before any of that runs, is also the safest point there
            // is: oracle scoring runs the agent's OWN generated docker compose/build commands inside ws,
            // and containers default to root, so anything they write back through a bind mount can leave
            // root-owned files behind that silently break a LATER git bundle over the same tree (confirmed
            // live: every one of 16 successfully-completed runs in the DB had no workspace.bundle at all,
            // despite this identical command succeeding in isolation).
            bundleWorkspace(ws, rd);
            // rd/source/ - a PLAIN, ready-to-run directory (docker-compose.yml, Dockerfile, src/, ...),
            // not just a .bundle a human would need to `git clone` before they could actually use it.
            // Found live 2026-09-28: ws lives under $TMPDIR, survives only until the OS decides to sweep
            // it - a run from days earlier had already lost its working tree AND part of its own git
            // object store by the time anyone went looking, needing manual git-internals surgery to
            // recover. Gates the ws/home cleanup below exactly like bundleWorkspace's own comment argues
            // for the bundle: never delete the only copy of what a run produced without a confirmed,
            // actually-usable replacement already sitting in rd.
            final boolean sourceExported = exportSource(ws, rd);

            // ---- reviews (scored for CALIBRATION against the oracle; they never replace it) ----
            final Map<String, Object> reviewCfg = cfg.get("review") instanceof Map<?, ?> r ? (Map<String, Object>) r : Map.of();
            if (Boolean.TRUE.equals(reviewCfg.get("enabled"))) {
                final List<PlanTask> tasks = Files.isRegularFile(ws.resolve("docs/IMPLEMENTATION_PLAN.md")) ? PlanParser.parseFile(ws.resolve("docs/IMPLEMENTATION_PLAN.md")) : List.of();
                manifest.put("self_review", reviews.selfReview(cfg, ws, rd, manifest, tasks, promptText, proxies,
                        (String) reviewCfg.get("model"), Boolean.TRUE.equals(reviewCfg.get("blind"))));
                writeManifest(rd, manifest);
            }
            final Map<String, Object> trajCfg = cfg.get("trajectory_review") instanceof Map<?, ?> t ? (Map<String, Object>) t : Map.of();
            if (Boolean.TRUE.equals(trajCfg.get("enabled"))) {
                manifest.put("trajectory_review", reviews.trajectoryReview(cfg, ws, rd, manifest, (String) trajCfg.get("model"), proxies));
                writeManifest(rd, manifest);
            }

            // ---- oracle: the pinned-container build checks, the compose runtime checks, the black-box suite ----
            manifest.put("ended", nowIso());
            manifest.put("journal", journal.toString());
            final Map<String, Object> facts = JournalFacts.facts(journal.toString(), null, null, List.of(), List.of(), null);
            manifest.put("journal_facts", facts);
            manifest.put("validity", Validity.validity(manifest, facts, props.maxErrorRate()));
            writeManifest(rd, manifest);
            final Map<String, Object> oracleReport = oracle.score(ws, task, (String) cfg.get("system_base_url"), manifest);
            Files.writeString(rd.resolve("oracle.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(oracleReport));
            // --efficiency-weight: unlike review/trajectory/parallel-plan's weight, this isn't tied to a
            // feature that conditionally ran - it's measurable on every run, so it's recorded here rather
            // than inside a feature-specific session method (same shape Reviews.java uses for its own
            // *_config weights, just with no corresponding session to hang it off of)
            if (cfg.get("efficiency_weight") instanceof Number ew)
                Reviews.manifestMap(manifest, "efficiency_config").put("weight", ew.doubleValue());
            // ---- metrics.json: the leaderboard block + per-step scores (collect.py) ----
            Files.writeString(rd.resolve("metrics.json"), json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(com.strgmai.ace.service.metrics.Collect.collect(rd, manifest)));
            // ---- trajectory metrics from the journal ----
            final Map<String, Object> summary = reviews.renderTrajectory(rd, manifest);
            manifest.put("decode_tps_median", summary.get("decode_tps_median"));
            manifest.put("decode_tps_median_short", summary.get("decode_tps_median_short"));
            manifest.put("contention", Map.of("docker_up", DockerService.dockerRunning(), "docker_windows", manifest.get("docker_windows")));
            writeManifest(rd, manifest);
            //todo ACE should be model provider agnostic
            captureOmlxLog(rd, manifest);
            // ws is never deleted: it's the run's own git repo, and the ONLY place a task's commit
            // (manifest.snapshots.<id>) can be `git show`n directly - workspace.bundle/source/ in rd
            // are a second, resilient copy for when ws's $TMPDIR location gets swept, not a replacement
            // for being able to inspect the real thing. home has no such role (just the scrubbed HOME
            // copy agent sessions ran under) and is still cleaned up once a usable copy of the source
            // is confirmed sitting in rd.
            if (sourceExported) deleteRecursive(home);
            return manifest;
        } finally {
            // #340: restore whatever max_context_window override existed before THIS job's own
            // prepareCap() raised it (null means none existed) - success, failure, or exception,
            // so an operator who isn't running ACE right now gets their own setting back instead
            // of whatever this job last measured. No-op if prepareCap() never actually raised it.
            if (capInfo != null)
                probe.restoreCap((String) cfg.getOrDefault("model", props.model()), props.endpoint(),
                        props.apiKey() == null ? "" : props.apiKey(), capInfo);
        }
    }

    /** Snapshots the run's own slice of the oMLX server log into rd/omlx_server.log, when
     *  ace.omlx-server-log is configured. oMLX's own log spans every run on the machine and grows
     *  without bound, so anything worth citing later (a throttling episode, an abort) is gone once
     *  it rotates unless it's copied out here. Best-effort like bundleWorkspace above: a missing
     *  config, missing file, or any read failure must never fail the run - this is diagnostic, not
     *  part of the scored result. Lines are matched by oMLX's own "yyyy-MM-dd HH:mm:ss,SSS" prefix;
     *  an unprefixed line (request-log continuation, a bare uvicorn line) inherits the in/out-of-range
     *  verdict of the last dated line before it, so it stays grouped with the entry it belongs to. */
    void captureOmlxLog(final Path rd, final Map<String, Object> manifest) {
        final String logPath = props.omlxServerLog();
        if (logPath == null || logPath.isBlank()) return;
        final Path src = Path.of(logPath);
        if (!Files.isRegularFile(src)) { log.debug("omlx server log not found at {}, skipping capture", src); return; }
        try {
            final var zone = java.time.ZoneId.systemDefault();
            final LocalDateTime startLocal = LocalDateTime.ofInstant(Instant.parse((String) manifest.get("started")), zone);
            final LocalDateTime endLocal = LocalDateTime.ofInstant(Instant.parse((String) manifest.get("ended")), zone);
            final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
            final List<String> slice = new ArrayList<>();
            boolean inRange = false;
            for (String line : Files.readAllLines(src)) {
                if (line.length() >= 23) {
                    try {
                        final LocalDateTime ts = LocalDateTime.parse(line.substring(0, 23), fmt);
                        inRange = !ts.isBefore(startLocal) && !ts.isAfter(endLocal);
                    } catch (Exception notADatedLine) { /* keep the previous verdict */ }
                }
                if (inRange) slice.add(line);
            }
            Files.write(rd.resolve("omlx_server.log"), slice);
        } catch (Exception e) {
            log.warn("could not capture omlx server log from {}: {}", src, e.toString());
        }
    }

    /** git-bundles the CURRENT state of ws into rd/workspace.bundle - the durable copy of the run's
     *  actual source, since ws itself is a scratch dir due for deletion. Written to a temp file and
     *  only moved into place once confirmed non-empty, so a failed or partial attempt (R17: git
     *  bundle create over a tree docker has left root-owned files in, among other possible causes)
     *  never clobbers an earlier, good bundle from calling this again later in the same run. */
    private static void bundleWorkspace(final Path ws, final Path rd) {
        final Path tmp = rd.resolve("workspace.bundle.tmp");
        try {
            Files.deleteIfExists(tmp);
            final DockerService.Sh r = DockerService.sh(600, "git", "-C", ws.toString(), "bundle", "create", tmp.toString(), "--all");
            if (r.rc() == 0 && Files.isRegularFile(tmp) && Files.size(tmp) > 0) {
                Files.move(tmp, rd.resolve("workspace.bundle"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } else {
                log.error("workspace bundle failed for {} (rc={}): {}", ws, r.rc(), r.out());
            }
        } catch (Exception e) {
            log.warn("could not bundle workspace {}: {}", ws, e.toString());
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Exception e) { log.debug("could not remove leftover bundle temp file {}: {}", tmp, e.toString()); }
        }
    }

    /** exports the run's own final, git-committed source into rd/source/ - a PLAIN directory (not
     *  a .bundle) that needs no `git clone`/`git fetch` to actually use: docker-compose.yml,
     *  Dockerfile, src/, build.gradle, ... exactly as committed, ready to `cd` into and run.
     *  `git archive` only emits TRACKED files - build/, .gradle/, and everything else in
     *  .git/info/exclude (RunBenchSupport.snapshot()) is skipped, so this is the agent's actual
     *  source, not its build output.
     *
     *  Found live 2026-09-28: ws lives under $TMPDIR, so it survives only until the OS decides to
     *  reclaim it - the run's ONLY copy of its own generated code before this, unless bundleWorkspace
     *  above had already run AND someone was willing to `git clone` the bundle back out later. A run
     *  from days earlier had lost its working tree entirely, plus two blobs from its own object
     *  store, by the time anyone went looking - recoverable only by hand, and only because most of
     *  the object store happened to survive. Returns whether it actually succeeded, so the caller
     *  (see the ws/home cleanup below) never deletes the only copy of what a run produced without a
     *  confirmed, immediately-usable replacement already sitting in rd. */
    static boolean exportSource(final Path ws, final Path rd) {
        final Path dest = rd.resolve("source");
        final Path tar = rd.resolve("source.tar.tmp");
        try {
            Files.deleteIfExists(tar);
            final DockerService.Sh archiveR = DockerService.sh(120, "git", "-C", ws.toString(), "archive", "--format=tar", "--output=" + tar, "HEAD");
            if (archiveR.rc() != 0 || !Files.isRegularFile(tar) || Files.size(tar) == 0) {
                log.error("exporting the run's source failed at `git archive` for {} (rc={}): {}", ws, archiveR.rc(), archiveR.out());
                return false;
            }
            deleteRecursive(dest);
            Files.createDirectories(dest);
            final DockerService.Sh untarR = DockerService.sh(120, "tar", "-xf", tar.toString(), "-C", dest.toString());
            if (untarR.rc() != 0) {
                log.error("exporting the run's source failed at `tar -x` for {} (rc={}): {}", ws, untarR.rc(), untarR.out());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("could not export the run's source from {}: {}", ws, e.toString());
            return false;
        } finally {
            try { Files.deleteIfExists(tar); } catch (Exception e) { log.debug("could not remove leftover source tar {}: {}", tar, e.toString()); }
        }
    }

    /** #306, found live: manifest.json/workspace.bundle/rd/source/ used to be written ONCE, only
     *  after the ENTIRE run (every task, every wave, INTEGRATION) finished - a run whose later
     *  tasks take hours (T8 alone took 6+, T13 8.88 before exhausting its own budget) left every
     *  EARLIER task's already-committed, tagged, genuinely-done work with no plain, ready-to-run
     *  export and no structured manifest record at all, recoverable only by reaching into `ws`'s own
     *  git history directly - exactly what answering "is T13's code still there?" required, live.
     *  Both git(archive|bundle) calls these three wrap are cheap (seconds, for this repo's size)
     *  against a task that takes hours, so re-running them after every task is not worth gating on a
     *  "has anything actually changed" check - this is always called right after a task's own
     *  rc/handoff are already settled, so `manifest` is never mid-update when it's written out. */
    private void snapshotRunProgress(final Path ws, final Path rd, final Map<String, Object> manifest) throws Exception {
        writeManifest(rd, manifest);
        bundleWorkspace(ws, rd);
        exportSource(ws, rd);
    }

    static void deleteRecursive(Path p) {
        // a failed delete here leaks the run's scratch workspace/HOME on disk - harmless per-run,
        // but silently accumulates across many runs with nothing pointing at why
        try (var w = Files.walk(p)) {
            w.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (Exception e) { log.debug("could not delete {}: {}", x, e.toString()); }
            });
        } catch (Exception e) { log.warn("could not walk {} for cleanup: {}", p, e.toString()); }
    }

    private void writeManifest(final Path rd, final Map<String, Object> manifest) throws Exception {
        Files.writeString(rd.resolve("manifest.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
    }

    // ---------------- monolithic mode: one growing session per phase; the rung picks the phases
    private void monolithicPhases(final Map<String, Object> cfg, final String runId, final Path rd, final Path ws, final Path journal, final Map<String, Object> manifest, final String promptText, String task) throws Exception {
        final String rules = Boolean.TRUE.equals(cfg.get("system_rules")) ? Packs.hygiene() : null;
        List<String> phases = phasesFor(task);   // non-L7 rungs run the single `implement` phase with the rung's budget
        final String impl = phases.contains("p2_implementation") ? "p2_implementation" : "implement";
        final Map<String, Integer> rung = rungBudgets(cfg, task);
        final Integer wallOverride = wallBudgetOverride(cfg);
        for (String pid : phasesWanted(cfg, phases)) {
            int wall = "p1_plan".equals(pid) ? rung.getOrDefault("plan_sec", 900) : rung.getOrDefault("sec", 14400);
            if ("implement".equals(pid) || "p2_implementation".equals(pid)) wall = implWall(cfg, rung);
            if (wallOverride != null) wall = wallOverride;   // --wall-budget: every phase's wall, uniformly
            long tokens = "implement".equals(pid) || "p2_implementation".equals(pid)
                    ? implTokens(cfg, pid, props) : planTokens(cfg, pid, props);
            final boolean isImpl = pid.equals(impl);
            final var proxy = proxies.start(journal, tokens, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
            Map<String, Object> rec;
            try {
                rec = sessionWithPolicy(cfg, runId, pid, PHASE_TEXT.get(pid)[1] + (isImpl ? "\n\n" + Packs.MONO_STATUS_INSTRUCTION : ""),
                        wall, tokens, rd, ws, journal, proxy, rules, null, null, !isImpl);
            } finally { proxy.stop(); }
            rec.put("artifact", PHASE_TEXT.get(pid)[0]);
            rec.put("artifact_present", Files.isRegularFile(ws.resolve(PHASE_TEXT.get(pid)[0])));
            ((List<Map<String, Object>>) manifest.get("phases")).add(rec);
            ((Map<String, String>) manifest.get("snapshots")).put(pid.split("_")[0], RunBenchSupport.snapshot(ws, "phase/" + pid.split("_")[0]));
            if (Files.isRegularFile(ws.resolve(PHASE_TEXT.get(pid)[0])) && !isImpl)
                Files.copy(ws.resolve(PHASE_TEXT.get(pid)[0]), rd.resolve(Path.of(PHASE_TEXT.get(pid)[0]).getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            // Python runs every phase regardless of the previous rc: validity, not the loop, judges it
        }
    }

    /** ladder.json budgets: L7-style from config; rung budget split plan/implementation for the rest.
     *  0 means unlimited (the system-wide "0 = no budget" convention) - both when ladder.json omits
     *  budget_sec for this task and when the whole rung is genuinely unlimited, plan/impl split to 0
     *  too rather than a stale 900s/negative remainder. The plan portion is whatever planWall() below
     *  resolves to (a per-run override, else the operator's own config, else 900s), so impl_sec's
     *  remainder stays consistent with whichever plan wall is actually in effect. */
    private Map<String, Integer> rungBudgets(final Map<String, Object> cfg, String task) {
        try {
            final var rung = json.readTree(getClass().getResourceAsStream("/tasks/ladder.json")).path(task);
            final int sec = rung.path("budget_sec").asInt(0);
            final int plan = sec > 0 ? (int) planWall(cfg, "p1_plan", props) : 0;
            final Map<String, Integer> out = new LinkedHashMap<>();
            out.put("sec", sec);
            out.put("plan_sec", plan);
            out.put("impl_sec", sec > 0 ? sec - plan : 0);
            return out;
        } catch (Exception e) {
            log.warn("could not read ladder.json budgets for task {}; falling back to unlimited: {}", task, e.toString());
            return Map.of("sec", 0, "plan_sec", 0, "impl_sec", 0);
        }
    }

    /** #173: an experiment's --impl-wall (JobCfgFactory -> cfg's "impl_wall_sec") matches a
     *  monolithic arm's single implementation-session budget to an orchestrated arm's N-tasks x
     *  per-task budget (design rule 10) - it must win over the rung's own default when the run
     *  explicitly set one for that comparison. Package-private: directly unit-tested. */
    static int implWall(final Map<String, Object> cfg, final Map<String, Integer> rung) {
        return cfg.get("impl_wall_sec") instanceof Number iw ? iw.intValue() : rung.get("impl_sec");
    }

    /** the token-budget counterpart to implWall() above, falling back to the operator-wide
     *  BenchProperties.phaseTokens(pid) default (itself 0/unlimited unless configured) rather than
     *  the rung. */
    static long implTokens(final Map<String, Object> cfg, final String pid, final BenchProperties props) {
        return cfg.get("impl_tokens") instanceof Number it ? it.longValue() : props.phaseTokens(pid);
    }

    /** #228: --plan-tokens (cfg's "plan_tokens") is the p0_definition/p1_plan counterpart to
     *  implTokens() above - an explicit per-run override for the planning/definition phases' token
     *  budget, falling back to the same operator-wide BenchProperties.phaseTokens(pid) default. */
    static long planTokens(final Map<String, Object> cfg, final String pid, final BenchProperties props) {
        return cfg.get("plan_tokens") instanceof Number pt ? pt.longValue() : props.phaseTokens(pid);
    }

    /** the wall-clock counterpart to planTokens() above: --plan-wall (cfg's "plan_wall_sec") is a
     *  per-run override for the p0_definition/p1_plan phases' wall budget. Falls back to the
     *  operator-wide BenchProperties.phaseWall(pid) config when set (itself already read by
     *  orchestratedPhase() before this existed), and only then to the literal 900s this always
     *  defaulted to - so an operator who has configured nothing sees no behavior change. */
    static long planWall(final Map<String, Object> cfg, final String pid, final BenchProperties props) {
        if (cfg.get("plan_wall_sec") instanceof Number pw) return pw.longValue();
        final int configured = props.phaseWall(pid);
        return configured > 0 ? configured : 900;
    }

    /** #228: --wall-budget (cfg's "wall_budget_override") overrides every phase's wall budget
     *  uniformly - a genuine experimental setting like any other budget override, just one that
     *  happens to apply to every phase at once instead of a single one (#241: no longer marks the
     *  run INVALID for using it). */
    static Integer wallBudgetOverride(final Map<String, Object> cfg) {
        return cfg.get("wall_budget_override") instanceof Number wb ? wb.intValue() : null;
    }

    /** #236: selects which agent actually performs a session, instead of every call site hardcoding
     *  ReferenceAgent - the ONLY thing sessionWithPolicy/runBounded's generic Future/timeout
     *  machinery needs is this exact return type, so neither of them had to change at all. Every
     *  call site swap below is a one-line change: agent.run(...) -> runAgentSession(cfg, ...). */
    private ReferenceAgent.SessionResult runAgentSession(final Map<String, Object> cfg, final String name, final String instruction,
            final long wallSec, final Long tokenBudget, final Path sessionDir, final String sessionId, final boolean continueSession,
            final String appendSystem, final String cwd, final String proxyBase, final Consumer<String> abortProxy,
            final long firstTokenTimeoutMs, final int compactionTrigger, final int maxTurns, final String model,
            final Map<String, String> extraEnv) throws Exception {
        return runAgentSession(cfg, name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase,
                abortProxy, firstTokenTimeoutMs, compactionTrigger, maxTurns, model, extraEnv, null);
    }

    /** #265: the judge-aware form - only the task loop's own main-session call site ever passes a
     *  real JudgeCheckpoint; every other call site (p0/p1/PARALLEL_PLAN/wrap-up/continue/handoff/
     *  reviews) goes through the convenience overload above with judge=null. PiAgent is an external
     *  subprocess harness with no in-process turn loop to hook into at all (the judge needs live
     *  access to msgs, a LangChain4j-specific, in-process concept) - the judge is silently ignored
     *  on that branch rather than threaded through, since this feature cannot apply there. */
    private ReferenceAgent.SessionResult runAgentSession(final Map<String, Object> cfg, final String name, final String instruction,
            final long wallSec, final Long tokenBudget, final Path sessionDir, final String sessionId, final boolean continueSession,
            final String appendSystem, final String cwd, final String proxyBase, final Consumer<String> abortProxy,
            final long firstTokenTimeoutMs, final int compactionTrigger, final int maxTurns, final String model,
            final Map<String, String> extraEnv, final ReferenceAgent.JudgeCheckpoint judge) throws Exception {
        return runAgentSession(cfg, name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase,
                abortProxy, firstTokenTimeoutMs, compactionTrigger, maxTurns, model, extraEnv, judge, null);
    }

    /** #300: adds the in-session handoff fast path's own FinalTurn hook (see its javadoc) on top of
     *  the judge-aware form above - only sessionWithPolicy's own wrap-up call site ever passes a
     *  real one (see its javadoc for why only there), everything else goes through one of the
     *  convenience overloads above with finalTurn=null. PiAgent ignores this the same way it
     *  already ignores judge, for the same reason (no in-process loop to hook into). */
    private ReferenceAgent.SessionResult runAgentSession(final Map<String, Object> cfg, final String name, final String instruction,
            final long wallSec, final Long tokenBudget, final Path sessionDir, final String sessionId, final boolean continueSession,
            final String appendSystem, final String cwd, final String proxyBase, final Consumer<String> abortProxy,
            final long firstTokenTimeoutMs, final int compactionTrigger, final int maxTurns, final String model,
            final Map<String, String> extraEnv, final ReferenceAgent.JudgeCheckpoint judge, final ReferenceAgent.FinalTurn finalTurn) throws Exception {
        return "pi".equals(cfg.get("harness"))
                ? piAgent.run(name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase,
                        abortProxy, firstTokenTimeoutMs, compactionTrigger, maxTurns, model, extraEnv)
                : agent.run(name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase,
                        abortProxy, firstTokenTimeoutMs, compactionTrigger, maxTurns, model, extraEnv, judge, finalTurn);
    }

    private Map<String, Object> sessionWithPolicy(Map<String, Object> cfg, String runId, String name, String instruction,
                                                  long wall, long tokens, Path rd, Path ws, Path journal,
                                                  RecordingProxyFactory.ProxySession proxy, String appendSystem, String sessionId,
                                                  Map<String, String> envOverride) throws Exception {
        return sessionWithPolicy(cfg, runId, name, instruction, wall, tokens, rd, ws, journal, proxy, appendSystem, sessionId, envOverride, false);
    }

    private Map<String, Object> sessionWithPolicy(Map<String, Object> cfg, String runId, String name, String instruction,
                                                  long wall, long tokens, Path rd, Path ws, Path journal,
                                                  RecordingProxyFactory.ProxySession proxy, String appendSystem, String sessionId,
                                                  Map<String, String> envOverride, boolean plainPlan) throws Exception {
        return sessionWithPolicy(cfg, runId, name, instruction, wall, tokens, rd, ws, journal, proxy, appendSystem, sessionId, envOverride, plainPlan, null);
    }

    private Map<String, Object> sessionWithPolicy(Map<String, Object> cfg, String runId, String name, String instruction,
                                                  long wall, long tokens, Path rd, Path ws, Path journal,
                                                  RecordingProxyFactory.ProxySession proxy, String appendSystem, String sessionId,
                                                  Map<String, String> envOverride, boolean plainPlan, ReferenceAgent.JudgeCheckpoint judge) throws Exception {
        return sessionWithPolicy(cfg, runId, name, instruction, wall, tokens, rd, ws, journal, proxy, appendSystem, sessionId, envOverride, plainPlan, judge, null);
    }

    /** a session + the stop protocol: budget clock, one `continue` on a length finish with the
     *  remaining budget, a wrap-up continuation when no status was written, the harness's fallback
     *  line, the budget-refusal verdict, the docker window. plainPlan=true for definition/plan
     *  phases: no clock, no docker note, no wrap-up, no verification (R4 C-3). `judge` (#265) is
     *  only ever non-null from the task loop's own main-session call - the continuation/wrap-up
     *  calls further down this same method never get one, by design (see the task loop's own
     *  comment for why). `finalTurn` (#300/#302) is likewise only ever non-null from the task loop's
     *  own call, but - unlike `judge` - IS forwarded to every one of main/continuation/wrap-up below:
     *  it's a one-shot, stateful closure (buildHandoffFinalTurn) that only actually asks once the
     *  task's own status is already on disk, so it safely fires on whichever of those three calls
     *  turns out to be genuinely last, never more than once. */
    private Map<String, Object> sessionWithPolicy(Map<String, Object> cfg, String runId, String name, String instruction,
                                                  long wall, long tokens, Path rd, Path ws, Path journal,
                                                  RecordingProxyFactory.ProxySession proxy, String appendSystem, String sessionId,
                                                  Map<String, String> envOverride, boolean plainPlan, ReferenceAgent.JudgeCheckpoint judge,
                                                  ReferenceAgent.FinalTurn finalTurn) throws Exception {
        final Map<String, String> env = envOverride != null ? envOverride : (Map<String, String>) cfg.get("_agent_env");
        // the run's OWN requested model (--model=): a single worker JVM handles every queued job in
        // turn, so the operator-wide default (props.model()) is not per-run - passing null here would
        // silently run every task against whatever model happens to be configured process-wide instead
        // of the model this run actually asked for (a real, previously silent mismatch)
        final String runModel = (String) cfg.get("model");
        // #263: taskOverride is Map.of() for every non-task session name (p0_definition, FIX ids,
        // INTEGRATION, ...) - the lookup is naturally a no-op there, only a restarted plan task's
        // own id ever has an entry.
        final Map<String, Object> override = taskOverride(cfg, name);
        // 0 means unlimited (the system-wide "0 = no budget" convention)
        final long firstTokenTimeoutMs = override.get("first_token_timeout_sec") instanceof Number sec ? sec.longValue() * 1000L
                : cfg.get("first_token_timeout_ms") instanceof Number ftt ? ftt.longValue() : 0L;
        final int compactionTrigger = effectiveCompactionTrigger(override,
                cfg.get("compaction_trigger") instanceof Number ct ? ct.intValue() : props.compactionTrigger());
        // #95: unlike every other phase/session budget, the turn cap used to be a hardcoded
        // ReferenceAgent-local constant with no run-level override at all
        final int maxTurns = effectiveInt(override, "max_turns",
                cfg.get("max_turns") instanceof Number mt ? mt.intValue() : ReferenceAgent.DEFAULT_MAX_TURNS);
        final long t0 = System.currentTimeMillis();
        // wall <= 0 means unlimited (the system-wide "0 = no budget" convention) - a "hard deadline"
        // of right now would be actively misleading, not just an edge case of the normal wording
        String full = plainPlan ? instruction
                : instruction + "\n\n" + (wall > 0
                        ? Packs.budgetSection(name, LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                                LocalDateTime.now().plusSeconds(wall).format(DateTimeFormatter.ofPattern("HH:mm")), (int) (wall / 60))
                        : Packs.unlimitedBudgetSection(name))
                + "\n\n" + Packs.DOCKER_NOTE;
        final String canonicalSessionId = UUID.nameUUIDFromBytes(("agentbench/" + runId + "/" + name).getBytes()).toString();
        final String sid = sessionId == null ? canonicalSessionId : sessionId;
        ReferenceAgent.SessionResult rec = RunBenchSupport.runBounded(wall, name, rd.resolve("sessions"), sid, proxy.abort(), () -> runAgentSession(cfg, name, full, wall, tokens,
                rd.resolve("sessions"), sid, false, appendSystem, ws.toString(), proxy.base(), proxy.abort(),
                firstTokenTimeoutMs, compactionTrigger, maxTurns, runModel, env, judge, finalTurn));
        // P-1: a session that died on a `length` finish gets one continuation with what is LEFT (R4
        // C-7). wall/tokens <= 0 means unlimited (the system-wide "0 = no budget" convention): there
        // is always "enough" remaining wall-clock, and what's LEFT of an unlimited token budget is
        // still unlimited, not a collapsed-to-minimum 1000.
        // #258, found live: this used to be gated !plainPlan, so p0_definition/p1_plan never got
        // this retry at all - a truncated plan-writing turn just silently killed the whole run
        // (orchestratedPhase() then throws PlanError trying to parse the never-written file). p0/p1
        // face the exact same per-call output ceiling as a task session; nothing about this retry
        // is task-specific, so there was never a reason to exclude them.
        if (shouldRetryOnLengthFinish(rec.finish(), rec.rc(), wall, (System.currentTimeMillis() - t0) / 1000)) {
            final long spent = ((Number) JournalFacts.facts(journal.toString(), rec.start().toString(), nowIso(), null, null, null).getOrDefault("completion_tokens", 0L)).longValue();
            long remaining = tokens <= 0 ? 0 : Math.max(1000, tokens - spent);   // what is LEFT, not a fresh budget (R4 C-7); a fresh proxy enforces it
            final var contProxy = proxies.start(journal, remaining, null, effectiveSamplerOverrides(cfg, override));
            try {
                final long contWall = wall <= 0 ? 0 : wall - (System.currentTimeMillis() - t0) / 1000;
                rec = RunBenchSupport.runBounded(contWall, name + "-continue", rd.resolve("sessions"), sid, contProxy.abort(), () -> runAgentSession(cfg, name + "-continue",
                        "Your previous turn was cut off at the output limit. Continue the task from where you stopped; be concise and act with tools.",
                        contWall, remaining, rd.resolve("sessions"), sid, true, appendSystem, ws.toString(), contProxy.base(), contProxy.abort(),
                        firstTokenTimeoutMs, compactionTrigger, maxTurns, runModel, env, null, finalTurn));
            } finally { contProxy.stop(); }
        }
        final Map<String, Object> out = RunBench.sessionRecord(rec, name);
        String reported = plainPlan
                ? (Files.isRegularFile(ws.resolve(name.startsWith("p0") ? "docs/TASK_DEFINITION.md" : "docs/IMPLEMENTATION_PLAN.md")) ? "done" : null)
                : progressStatus(ws, name);
        if (!plainPlan && reported == null) {
            final long spentSoFar = ((Number) JournalFacts.facts(journal.toString(), rec.start().toString(), nowIso(), null, null, null).getOrDefault("completion_tokens", 0L)).longValue();
            if (shouldReEngageOnEmptyStop(rec.finish(), rec.rc(), wall, (System.currentTimeMillis() - t0) / 1000, tokens, spentSoFar)) {
                final long remaining = tokens <= 0 ? 0 : Math.max(1000, tokens - spentSoFar);
                final var reengageProxy = proxies.start(journal, remaining, null, effectiveSamplerOverrides(cfg, override));
                try {
                    final long reengageWall = wall <= 0 ? 0 : wall - (System.currentTimeMillis() - t0) / 1000;
                    rec = RunBenchSupport.runBounded(reengageWall, name + "-reengage", rd.resolve("sessions"), sid, reengageProxy.abort(), () -> runAgentSession(cfg, name + "-reengage",
                            Packs.reEngageInstruction(name), reengageWall, remaining, rd.resolve("sessions"), sid, true, appendSystem, ws.toString(), reengageProxy.base(), reengageProxy.abort(),
                            firstTokenTimeoutMs, compactionTrigger, maxTurns, runModel, env, null, finalTurn));
                } finally { reengageProxy.stop(); }
                reported = progressStatus(ws, name);
            }
        }
        if (!plainPlan && reported == null && rec.rc() == 2) {
            // TransientError: the model call itself never got a response (retries already exhausted
            // in chatWithRetry) - the session ended in ~seconds, nowhere near its wall budget, so this
            // is an infrastructure failure, not "ran out of time to write status". A wrap-up call would
            // go through the exact same broken path and fail identically; skip straight to the
            // harness-authored fallback status below instead of burning another retry cycle.
            log.warn("session {} ended rc=2 (turns={}); skipping the wrap-up call, it would hit the same failure", name, rec.turns());
        } else if (!plainPlan && reported == null) {   // the wrap-up: status writing only, wall scaled by the decode slowdown at the context where the session ended (R8)
            final Long pt = JournalFacts.lastPromptTokens(journal.toString(), rec.start().toString());
            final Double here = pt == null ? null : ContextProbe.decodeAt(curve(cfg), pt);
            final Double shortDps = ((Map<String, Object>) manifestOf(cfg).get("derived")).get("decode_tps_short") instanceof Number n ? n.doubleValue() : null;
            final double scale = shortDps != null && here != null && here > 0 ? Math.min(4.0, Math.max(1.0, shortDps / here)) : 1.0;
            final String wrapInstr = name.startsWith("p") || name.equals("implement") ? Packs.MONO_WRAPUP_INSTRUCTION : Packs.wrapupInstruction(name);
            // the wrap-up is a continuation on top of the task budget: its own proxy with its own token
            // budget (run_bench run_task) - 0 means unlimited (the system-wide "0 = no budget" convention)
            final long wrapupTokens = effectiveLong(override, "wrapup_tokens", cfg.get("wrapup_tokens") instanceof Number wt ? wt.longValue() : 0L);
            final var wrapProxy = proxies.start(journal, wrapupTokens, null, effectiveSamplerOverrides(cfg, override));
            // 0 means unlimited (the system-wide "0 = no budget" convention)
            final int wrapupBase = effectiveInt(override, "wrapup_wall_sec", cfg.get("wrapup_wall_sec") instanceof Number wb ? wb.intValue() : 0);
            final long wrapWall = (long) (wrapupBase * scale);
            final ReferenceAgent.SessionResult w;   // assigned exactly once below; a legal blank final
            // #300/#302: finalTurn also reaches the main call and its length-retry continuation
            // above (see this method's own javadoc) - this is simply the THIRD place it might fire,
            // not special-cased for that reason any more. Still withheld specifically here if `rec`
            // (the main session's own result, set above - never this wrap-up's own `w`) already
            // timed out (rc==124): the task loop's own handoffStep() call skips a timed-out task the
            // same way, and firing it here would ask for a handoff the task loop was never going to
            // credit anyway.
            final ReferenceAgent.FinalTurn wrapFinalTurn = rec.rc() == 124 ? null : finalTurn;
            try {
                w = RunBenchSupport.runBounded(wrapWall, name + "-wrapup", rd.resolve("sessions"), canonicalSessionId, wrapProxy.abort(),
                        () -> runAgentSession(cfg, name + "-wrapup", wrapInstr, wrapWall, wrapupTokens, rd.resolve("sessions"), canonicalSessionId,
                                true, appendSystem, ws.toString(), wrapProxy.base(), wrapProxy.abort(), firstTokenTimeoutMs, compactionTrigger, maxTurns, runModel, env, null, wrapFinalTurn));
            } finally { wrapProxy.stop(); }
            out.put("wrapup_rc", w.rc());
            out.put("wrapup_seconds", w.seconds());
            out.put("wrapup_scale", Math.round(scale * 100) / 100.0);
            reported = progressStatus(ws, name);
        }
        if (!plainPlan && reported == null) {   // the harness owns the status trail
            // #262, found live: this used to always pass "" here, so "budget exhausted" showed even
            // when the real cause (confirmed live: an oMLX memory-guard rejection, nothing to do with
            // any configured budget) was available or inferable. rec still describes the MAIN
            // session specifically (not the wrap-up's own w, scoped to the branch above and absent
            // entirely when rc==2 skipped the wrap-up) - that is exactly what decided there would be
            // no status line, so it is the right thing to name here regardless of which branch ran.
            // #415: whatever the upstream itself said (an explicit OpenAI-shaped "error" - see
            // SseAssembler.errorText), if anything - most of the time this is null (T19's own case)
            final String upstreamError = JournalFacts.lastUpstreamError(journal.toString(), rec.start().toString());
            final String tail = fallbackTail(rec.rc(), wall, rec.turns(), rec.end().toString(), upstreamError);
            final Path pp = ws.resolve("docs/PROGRESS.md");
            Files.createDirectories(pp.getParent());
            final String existing = Files.isRegularFile(pp) ? Files.readString(pp) : "";
            Files.writeString(pp, (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n") + Packs.fallbackStatus(name, tail) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            reported = "unfinished";
            out.put("status_by_harness", true);
            if (upstreamError != null && !upstreamError.isBlank()) out.put("upstream_error_text", upstreamError);
        }
        // the proxy's 429 refusal is the budget's own verdict: over_budget, never INVALID (R4 C-3)
        final Map<String, Object> wf = JournalFacts.facts(journal.toString(), rec.start().toString(), nowIso(), null, null, null);
        out.put("requests", wf.get("requests"));
        if (((Number) wf.getOrDefault("budget_refusals", 0)).intValue() > 0) {
            out.put("token_budget_exhausted", true);
            out.put("over_budget", true);
            if (rec.rc() != 0 && rec.rc() != 124) out.put("exit_after_refusal", true);   // rc=3 after the refusal (R5 C-9)
        }
        out.put("reported", reported);
        out.put("finish", rec.finish());
        if (!plainPlan) {   // the harness-run verification is the implementation phase's truthful record (M-1)
            out.put("verification", VerifyTask.verify(ws, cfg, rd.resolve("verify/" + name + ".log")));
            out.put("done_verified", VerifyTask.doneVerified(reported, (Map<String, Object>) out.get("verification")));
        }
        return out;
    }

    static List<double[]> curve(final Map<String, Object> cfg) {
        return cfg.get("_decode_curve") instanceof List<?> l && (!l.isEmpty() && l.get(0) instanceof double[])
                ? (List<double[]>) l : new ArrayList<>();
    }

    private Map<String, Object> manifestOf(Map<String, Object> cfg) { return (Map<String, Object>) cfg.get("_manifest"); }

    // ---------------- orchestrated mode: stable pack, task packs, parallel waves, merge, FIX, integration
    private void orchestratedPhase(Map<String, Object> cfg, String runId, Path rd, Path ws, Path home, Path journal,
                                   Map<String, Object> manifest, String planSource, String promptText, int taskWall, long taskTokens,
                                   String task) throws Exception {
        cfg.put("_manifest", manifest);
        final List<String> rungPhases = phasesFor(task);
        final List<String> wanted = phasesWanted(cfg, rungPhases);
        requirePlanAndImplementationPhases(rungPhases, wanted, task);
        // --wall-budget: every phase's wall, uniformly - the same override monolithicPhases() applies
        final Integer wallOverride = wallBudgetOverride(cfg);
        final long p0Wall = wallOverride != null ? wallOverride : planWall(cfg, "p0_definition", props);
        final long p1Wall = wallOverride != null ? wallOverride : planWall(cfg, "p1_plan", props);
        if (wanted.contains("p0_definition")) {   // L7-style rungs define before they plan (Python run_once loops the rung's phases)
            Map<String, Object> rec;
            // R18: resume past this phase if a prior attempt of THIS SAME run already finished it
            if (RunBenchSupport.tagExists(ws, "phase/p0") && Files.isRegularFile(ws.resolve(PHASE_TEXT.get("p0_definition")[0]))) {
                log.info("run {}: phase/p0 already completed in a prior attempt, resuming past it", runId);
                rec = new LinkedHashMap<>(Map.of("id", "p0_definition", "rc", 0, "resumed", true));
                ((Map<String, String>) manifest.get("snapshots")).put("p0", RunBenchSupport.gitOut(ws, "rev-parse", "phase/p0"));
            } else {
                // same reasoning as #263's task-loop override (see below): the proxy's own token
                // budget is sized at creation, so a restarted p0_definition's override must apply
                // here too, not just inside sessionWithPolicy
                final var p0Override = taskOverride(cfg, "p0_definition");
                final int effP0Wall = effectiveInt(p0Override, "task_wall_sec", (int) p0Wall);
                final long effP0Tokens = effectiveLong(p0Override, "task_tokens", planTokens(cfg, "p0_definition", props));
                final var proxy = proxies.start(journal, effP0Tokens, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
                try {
                    rec = sessionWithPolicy(cfg, runId, "p0_definition", PHASE_TEXT.get("p0_definition")[1],
                            effP0Wall, effP0Tokens, rd, ws, journal, proxy, null, null, null, true);
                } finally { proxy.stop(); }
                ((Map<String, String>) manifest.get("snapshots")).put("p0", RunBenchSupport.snapshot(ws, "phase/p0"));
            }
            rec.put("artifact", PHASE_TEXT.get("p0_definition")[0]);
            rec.put("artifact_present", Files.isRegularFile(ws.resolve(PHASE_TEXT.get("p0_definition")[0])));
            ((List<Map<String, Object>>) manifest.get("phases")).add(rec);
            if (Files.isRegularFile(ws.resolve("docs/TASK_DEFINITION.md")))
                Files.copy(ws.resolve("docs/TASK_DEFINITION.md"), rd.resolve("TASK_DEFINITION.md"), StandardCopyOption.REPLACE_EXISTING);
        }
        String planSha;
        if ("reference".equals(planSource)) {
            final Path ref = rd.resolve("IMPLEMENTATION_PLAN.md");
            if (!Files.isRegularFile(ref)) {
                // tasks/<task>/REFERENCE_PLAN.md (vendored as a resource) when the task has one - only
                // L3p_point_in_time does today - else the prompt itself stands in for the plan
                try (InputStream refSrc = getClass().getResourceAsStream("/tasks/" + task + "/REFERENCE_PLAN.md")) {
                    if (refSrc != null) Files.copy(refSrc, ref, StandardCopyOption.REPLACE_EXISTING);
                    else Files.writeString(ref, Files.readString(ws.resolve("task/PROMPT.md")));
                }
            }
            Files.createDirectories(ws.resolve("docs"));
            Files.copy(ref, ws.resolve("docs/IMPLEMENTATION_PLAN.md"), StandardCopyOption.REPLACE_EXISTING);
            planSha = RunBenchSupport.snapshot(ws, "phase/p1");
        } else {
            Map<String, Object> p1rec;
            // R18: resume past the agent-authored plan if a prior attempt already finished it
            if (RunBenchSupport.tagExists(ws, "phase/p1") && Files.isRegularFile(ws.resolve("docs/IMPLEMENTATION_PLAN.md"))) {
                log.info("run {}: phase/p1 already completed in a prior attempt, resuming past it", runId);
                p1rec = new LinkedHashMap<>(Map.of("id", "p1_plan", "rc", 0, "resumed", true));
                planSha = RunBenchSupport.gitOut(ws, "rev-parse", "phase/p1");
            } else {
                final var p1Override = taskOverride(cfg, "p1_plan");
                final int effP1Wall = effectiveInt(p1Override, "task_wall_sec", (int) p1Wall);
                final long effP1Tokens = effectiveLong(p1Override, "task_tokens", planTokens(cfg, "p1_plan", props));
                final var proxy = proxies.start(journal, effP1Tokens, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
                try {
                    p1rec = sessionWithPolicy(cfg, runId, "p1_plan", PHASE_TEXT.get("p1_plan")[1],
                            effP1Wall, effP1Tokens, rd, ws, journal, proxy, null, null, null, true);
                } finally { proxy.stop(); }
                planSha = RunBenchSupport.snapshot(ws, "phase/p1");
            }
            p1rec.put("artifact", PHASE_TEXT.get("p1_plan")[0]);
            p1rec.put("artifact_present", Files.isRegularFile(ws.resolve("docs/IMPLEMENTATION_PLAN.md")));
            ((List<Map<String, Object>>) manifest.get("phases")).add(p1rec);
            // mirrors TASK_DEFINITION.md's own copy right after p0_definition above - without this,
            // an agent-generated plan lives only in ws (the agent's live, ephemeral workspace) and is
            // never visible in rd until exportSource() runs, at the very end of the whole run. The
            // "reference" plan source above already lands at this same rd path before the task loop
            // even starts; this just gives agent-generated plans the same live visibility (e.g. for a
            // job-scoped "show me the plan" API call while the run is still in progress).
            if (Files.isRegularFile(ws.resolve("docs/IMPLEMENTATION_PLAN.md")))
                Files.copy(ws.resolve("docs/IMPLEMENTATION_PLAN.md"), rd.resolve("IMPLEMENTATION_PLAN.md"), StandardCopyOption.REPLACE_EXISTING);
        }
        if ("reference".equals(planSource)) {   // the reference plan is a recorded phase too (rc=0, 0 s)
            Map<String, Object> p1rec = new LinkedHashMap<>(Map.of("id", "p1_plan", "rc", 0, "seconds", 0.0,
                    "start_iso", nowIso(), "end_iso", nowIso(), "artifact", PHASE_TEXT.get("p1_plan")[0],
                    "artifact_present", Files.isRegularFile(ws.resolve("docs/IMPLEMENTATION_PLAN.md"))));
            p1rec.put("reference_plan", planSource);
            ((List<Map<String, Object>>) manifest.get("phases")).add(p1rec);
        }
        final List<PlanTask> tasks;
        try {
            tasks = PlanParser.parseFile(ws.resolve("docs/IMPLEMENTATION_PLAN.md"));
        } catch (final PlanError e) {
            // Python's orchestrate() catches (PlanError, OSError) here and records manifest.plan.error
            // instead of crashing the run - an agent that never wrote (or wrote an unparseable)
            // docs/IMPLEMENTATION_PLAN.md is a real, if unproductive, model outcome, not a harness
            // failure. Validity.java's orchestrated branch already reads manifest.plan.error and marks
            // the run INVALID ("plan unparseable") for exactly this shape - returning early here, same
            // as Python, lets the rest of runOnce() (oracle scoring, writeManifest) still record the run.
            log.warn("run {}: plan unparseable or missing: {}", runId, e.toString());
            manifest.put("plan", planErrorEntry(planSource, e));
            return;
        }
        manifest.put("plan", Map.of("source", planSource, "sha", planSha,
                "tasks", tasks.stream().map(t -> Map.of("id", t.id, "title", t.title == null ? "" : t.title, "deps", t.deps == null ? List.of() : t.deps, "checks", t.checks == null ? List.of() : t.checks)).toList()));
        final String stable = Packs.stablePack(promptText, tasks);
        Files.createDirectories(rd.resolve("packs"));   // orchestrated-only: monolithic mode never writes here
        Files.writeString(rd.resolve("packs/stable.md"), stable);
        final Map<String, String[]> snapshots = new LinkedHashMap<>();
        Map<String, Object> wave = new LinkedHashMap<>();   // wave bookkeeping

        // ---- the parallelisation-plan step (R6): the agent schedules its own tasks; the harness evaluates
        Map<String, Object> parallelPlan = null;
        if (Boolean.TRUE.equals(cfg.getOrDefault("parallel_plan_enabled", true)) && tasks.size() > 1) {
            parallelPlan = parallelPlanStep(cfg, runId, rd, ws, journal, manifest, tasks, stable);
            // #228: --parallel-weight (cfg's "parallel_plan" map) - Collect.java already reads
            // manifest's "parallel_plan_config.weight" for agent_result_pct (mirrors review_config/
            // trajectory_review_config, written the same way by Reviews.java) - "applies only when
            // the step ran" (queue.py's own help text), hence this lives inside the enabled branch
            if (cfg.get("parallel_plan") instanceof Map<?, ?> ppCfg && ppCfg.get("weight") instanceof Number w)
                Reviews.manifestMap(manifest, "parallel_plan_config").put("weight", w.doubleValue());
        }
        List<List<PlanTask>> planWaves = PlanParser.waves(tasks);
        final Map<String, List<String>> ownership = parallelPlan != null && parallelPlan.get("ownership") instanceof Map<?, ?> o ? (Map<String, List<String>>) o : Map.of();
        final boolean useAgentWaves = parallelPlan != null && Boolean.TRUE.equals(parallelPlan.get("used"));
        if (useAgentWaves) {
            final Map<String, PlanTask> byId = new LinkedHashMap<>();
            tasks.forEach(t -> byId.put(t.id, t));
            planWaves = ((List<List<String>>) parallelPlan.get("waves")).stream().map(wv -> wv.stream().map(byId::get).filter(Objects::nonNull).toList()).toList();
        }
        int parallel = cfg.get("parallel") instanceof Number n ? n.intValue() : 1;
        final Object mp = ((Map<String, Object>) manifest.get("derived")).get("max_parallel");
        if (Boolean.TRUE.equals(cfg.get("parallel_auto")) && mp instanceof Number n2) parallel = Math.max(1, n2.intValue());
        // #236: concurrent pi sessions would race on the one shared per-run models.json's baseUrl
        // field (see PiAgent) - rejected outright rather than silently corrupting one session's model
        // routing, matching this codebase's "never silently substitute" convention
        if (parallel > 1 && "pi".equals(cfg.get("harness")))
            throw new IllegalStateException("harness=pi does not support parallel>1 (parallel=" + parallel + "): concurrent pi sessions would race on the shared per-run models.json");
        manifest.put("parallel", parallel > 1 ? parallel : null);
        manifest.put("waves", new ArrayList<Map<String, Object>>());
        // the DECLARED task order, written before any task session starts: manifest.waves only ever
        // records waves that actually ran through runParallelWave (parallel<=1 skips it entirely, so
        // a same-wave pair like T2/T4 would leave no trace there) - this is what actually explains a
        // live run's step order (e.g. T3 legitimately starting right after T4, not T2, when T3 only
        // depends on T2: both are in wave 2, run one at a time in list order under parallel=1)
        Files.writeString(rd.resolve("execution_order.json"),
                json.writeValueAsString(planWaves.stream().map(w -> w.stream().map(t -> t.id).toList()).toList()));

        String prev = null; Path prevSession = null; String prevVerified = null;
        // #303: loadHandoffs returns manifestHandoffs(manifest) itself, backfilled from disk for
        // any task already tagged done - see its own javadoc for why both of those matter
        final List<String[]> handoffs = Boolean.TRUE.equals(cfg.get("handoff_notes")) ? loadHandoffs(ws, tasks, manifest) : null;
        List<String[]> mergeConflicts = new ArrayList<>();
        for (List<PlanTask> w : planWaves) {
            if (w.isEmpty()) continue;
            if (w.size() == 1 || parallel <= 1) {   // ---- sequential task (the orchestrated mode as before) ----
                for (PlanTask t : w) {
                    Map<String, Object> rec;
                    // R18: resume past this task if a prior attempt of THIS SAME run already
                    // finished it (the service restarted mid-run, the job got requeued, this method
                    // is starting over) - re-running it would throw away real agent work already
                    // paid for and redo it from a blank session
                    if (RunBenchSupport.tagExists(ws, "phase/" + t.id)) {
                        log.info("run {}: phase/{} already completed in a prior attempt, resuming past it instead of re-running", runId, t.id);
                        rec = new LinkedHashMap<>(Map.of("id", t.id, "rc", 0, "resumed", true));
                        final String after = RunBenchSupport.gitOut(ws, "rev-parse", "phase/" + t.id);
                        ((Map<String, String>) manifest.get("snapshots")).put(t.id, after);
                        snapshots.put(t.id, new String[]{String.valueOf(manifestSnap(manifest, "p1", planSha)), after});
                        mergeConflicts.clear();
                    } else {
                        RunBenchSupport.clearStaleProgressLine(ws, t.id);
                        final String tp = Packs.taskPack(ws, t, tasks, snapshots, doneSet(snapshots), prev, prevSession, handoffs, readLessons(rd), prevVerified, mergeConflicts.isEmpty() ? null : mergeConflicts, null);
                        Files.writeString(rd.resolve("packs/" + t.id + ".md"), tp);
                        // #263: a restarted task's own task_wall_sec/task_tokens override (set via
                        // the restart-task endpoint) takes effect right here, since the proxy's own
                        // token-budget enforcement is sized at creation, not re-checked against
                        // whatever sessionWithPolicy is later told - overriding only there would
                        // leave the OLD, un-overridden token budget still enforced underneath it.
                        final var taskOverride = taskOverride(cfg, t.id);
                        final int effTaskWall = effectiveInt(taskOverride, "task_wall_sec", taskWall);
                        final long effTaskTokens = effectiveLong(taskOverride, "task_tokens", taskTokens);
                        final var proxy = proxies.start(journal, effTaskTokens, null, effectiveSamplerOverrides(cfg, taskOverride));
                        final var judgeCheckpoint = buildJudgeCheckpoint(cfg, runId, ws, rd, t.id, t.goal, effTaskWall, effTaskTokens, taskOverride);
                        final var handoffFinalTurn = buildHandoffFinalTurn(handoffs, ws, t.id);
                        try {
                            rec = sessionWithPolicy(cfg, runId, t.id, Packs.taskInstruction(t.id) + "\n\n" + tp,
                                    effTaskWall, effTaskTokens, rd, ws, journal, proxy, rd.resolve("packs/stable.md").toString(), null, null, false, judgeCheckpoint, handoffFinalTurn);
                        } finally { proxy.stop(); intervene.clear(runId, t.id); }
                        // #307: before anything downstream (the phase tag, logTaskAttempt, handoffStep)
                        // ever looks at this task's own final state - a successful rescue changes what
                        // that state actually is, so everything after this point must see it, not the
                        // pre-rescue one
                        rescueStep(cfg, runId, t, rd, ws, journal, rec, taskOverride);
                        final String after = RunBenchSupport.snapshot(ws, "phase/" + t.id);
                        ((Map<String, String>) manifest.get("snapshots")).put(t.id, after);
                        snapshots.put(t.id, new String[]{String.valueOf(manifestSnap(manifest, "p1", planSha)), after});
                        mergeConflicts.clear();
                        final int effMaxTurns = effectiveInt(taskOverride, "max_turns",
                                cfg.get("max_turns") instanceof Number mt ? mt.intValue() : ReferenceAgent.DEFAULT_MAX_TURNS);
                        logTaskAttempt(rd, ws, t.id, taskOverride, effTaskWall, effTaskTokens, effMaxTurns, rec);
                    }
                    rec.put("title", t.title);
                    ((List<Map<String, Object>>) manifest.get("tasks")).add(rec);
                    // #351: rescueStep() above already had its chance to flip rc away from 124 (a
                    // successful rescue updates rec's own "rc" in place, same as it does for rc=3/4) -
                    // so this still reads the POST-rescue state. Only a task that is STILL timed out
                    // after that gets the harness-authored fallback handoff instead of the real one.
                    if (handoffs != null) {
                        if (((int) rec.get("rc")) != 124) handoffStep(cfg, runId, t, rd, ws, journal, manifest, rec);
                        else fallbackHandoffStep(rd, ws, manifest, t, rec);
                    }
                    snapshotRunProgress(ws, rd, manifest);
                    prev = t.id;
                    prevSession = agentSessionFile(rd, runId, t.id);
                    prevVerified = VerifyTask.verifyText(VerifyTask.verify(ws, cfg, rd.resolve("verify/" + t.id + ".log")));
                    // this task is now fully committed (phase tag, manifest, handoff, snapshot) -
                    // the safe, between-tasks moment a "pause after the current task" request
                    // (distinct from the immediate /pause, which can land mid-task and discard
                    // real in-progress work) actually takes effect, via the exact same pause()
                    // machinery that endpoint already uses.
                    pauseAfterTask.checkAndPauseIfRequested(runId);
                }
                continue;
            }
            // ---- parallel wave: every task in its own worktree + HOME copy + tagged journal; then merge into the main workspace ----
            wave = runParallelWave(cfg, runId, rd, ws, home, journal, manifest, tasks, snapshots, w, parallel, ownership,
                    promptText, stable, taskWall, taskTokens, handoffs, prevVerified, mergeConflicts, planSha);
            snapshotRunProgress(ws, rd, manifest);
            prev = null; prevSession = null;
            prevVerified = "after the merged wave " + w.stream().map(t -> t.id).toList() + ": " + wave.get("verification_text");
            mergeConflicts = (List<String[]>) wave.get("merge_conflicts");
            // the whole wave is now fully committed and merged - the safe, between-waves moment
            // a "pause after the current task" request takes effect (see the sequential loop's
            // own identical call above for why).
            pauseAfterTask.checkAndPauseIfRequested(runId);
        }
        // ---- integration: the fixed final task ----
        Map<String, Object> rec;
        final String after;
        if (RunBenchSupport.tagExists(ws, "phase/INTEGRATION")) {   // R18: see the sequential task loop above
            log.info("run {}: phase/INTEGRATION already completed in a prior attempt, resuming past it", runId);
            rec = new LinkedHashMap<>(Map.of("id", "INTEGRATION", "rc", 0, "resumed", true));
            after = RunBenchSupport.gitOut(ws, "rev-parse", "phase/INTEGRATION");
        } else {
            final String tp = Packs.taskPack(ws, new PlanTask("INTEGRATION", "integrate and verify"), tasks, snapshots, doneSet(snapshots), prev, prevSession, handoffs, readLessons(rd), prevVerified, mergeConflicts.isEmpty() ? null : mergeConflicts, null);
            Files.writeString(rd.resolve("packs/INTEGRATION.md"), tp);
            final var proxy = proxies.start(journal, taskTokens, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
            try {
                rec = sessionWithPolicy(cfg, runId, "INTEGRATION", Packs.INTEGRATION_INSTRUCTION + "\n\n" + tp,
                        (long) (taskWall * 0.2), taskTokens, rd, ws, journal, proxy, rd.resolve("packs/stable.md").toString(), null, null);
            } finally { proxy.stop(); }
            after = RunBenchSupport.snapshot(ws, "phase/INTEGRATION");
        }
        rec.put("title", "integration");
        ((Map<String, String>) manifest.get("snapshots")).put("INTEGRATION", after);
        ((List<Map<String, Object>>) manifest.get("tasks")).add(rec);
        manifest.put("snapshots_p2", after);
    }

    String manifestSnap(Map<String, Object> manifest, String key, String dflt) {
        return ((Map<String, String>) manifest.get("snapshots")).getOrDefault(key, dflt);
    }

    static Set<String> doneSet(Map<String, String[]> snapshots) { return snapshots.keySet(); }

    Path agentSessionFile(Path rd, String runId, String id) {
        for (Path p : sessionFiles(rd.resolve("sessions")))
            if (p.getFileName().toString().contains(UUID.nameUUIDFromBytes(("agentbench/" + runId + "/" + id).getBytes()).toString())) return p;
        return null;
    }
    static List<Path> sessionFiles(final Path dir) {
        try (var s = Files.list(dir)) { return s.filter(Files::isRegularFile).toList(); } catch (Exception e) { return List.of(); }
    }

    private void handoffStep(final Map<String, Object> cfg, final String runId, final PlanTask t, final Path rd, final Path ws, final Path journal, final Map<String, Object> manifest, Map<String, Object> rec) throws Exception {
        final Path hp = ws.resolve("handoff").resolve(t.id + ".md");
        // R18: resume past this handoff if a prior attempt of THIS SAME run already finished it.
        // #249, found live: unlike every other phase, this one had no tagExists check at all - a
        // restarted run unconditionally re-ran EVERY handoff from scratch, even ones already
        // committed. Since ws survives every restart now, this isn't just wasted budget: a
        // resumed handoff session can find itself staring at a workspace whose LATER tasks are
        // already fully implemented (from a prior attempt that got further before being
        // interrupted), flatly contradicting its own "write for a colleague who implements the
        // remaining tasks" framing - one session spent 3+ hours trying to reconcile that instead of
        // just stopping, because nothing here told it the handoff was already done.
        if (RunBenchSupport.tagExists(ws, "phase/" + t.id + "-handoff")) {
            log.info("run {}: phase/{}-handoff already completed in a prior attempt, resuming past it", runId, t.id);
            // loadHandoffs() already backfilled this task's note into the manifest's handoffs list
            // before the task loop started (this branch is only ever reached for a task whose
            // -handoff tag already existed at invocation start, which loadHandoffs() scans for) -
            // adding it here too would duplicate the same "### Handoff from {id}" block in every
            // later task's pack (found live: T11's pack carried T1's note twice after a restart).
            if (Files.isRegularFile(hp)) {
                rec.put("handoff_chars", Files.readString(hp).length());
            }
            rec.put("handoff_seconds", 0.0);
            rec.put("handoff_rc", 0);
            return;
        }
        // #300: the task's own wrap-up turn may already have answered this ask inline - see the
        // FinalTurn hook built by buildHandoffFinalTurn() and threaded into sessionWithPolicy's own
        // wrap-up call. Same live session/connection as the task's own work, so there is no cold
        // continueSession=true call to make here at all; only the bookkeeping the cold path below
        // does after ITS OWN call is left to do.
        if (Files.isRegularFile(hp)) {
            recordHandoffContent(ws, manifest, rec, t.id, hp);
            rec.put("handoff_seconds", 0.0);
            rec.put("handoff_rc", 0);
            RunBenchSupport.snapshot(ws, "phase/" + t.id + "-handoff");   // so a future resume can skip this (see tagExists check above)
            return;
        }
        // #322: same task_override lookup sessionWithPolicy/rescueStep already do for this same
        // task id - handoffStep never received it before since nothing here needed it until now.
        final var override = taskOverride(cfg, t.id);
        // 0 means unlimited (the system-wide "0 = no budget" convention)
        final long handoffTokens = cfg.get("handoff_tokens") instanceof Number ht ? ht.longValue() : 0L;
        final var proxy = proxies.start(journal, handoffTokens, null, effectiveSamplerOverrides(cfg, override));
        final long firstTokenTimeoutMs = cfg.get("first_token_timeout_ms") instanceof Number n ? n.longValue() : 0L;   // 0 = unlimited
        final int compactionTrigger = effectiveCompactionTrigger(override,
                cfg.get("compaction_trigger") instanceof Number ct ? ct.intValue() : props.compactionTrigger());
        final int maxTurns = cfg.get("max_turns") instanceof Number mt ? mt.intValue() : ReferenceAgent.DEFAULT_MAX_TURNS;
        try {
            final String handoffSid = UUID.nameUUIDFromBytes(("agentbench/" + runId + "/" + t.id).getBytes()).toString();
            // 0 means unlimited (the system-wide "0 = no budget" convention)
            final int handoffWall = cfg.get("handoff_wall_sec") instanceof Number hw ? hw.intValue() : 0;
            ReferenceAgent.SessionResult h = RunBenchSupport.runBounded(handoffWall, t.id + "-handoff", rd.resolve("sessions"), handoffSid, proxy.abort(),
                    () -> runAgentSession(cfg, t.id + "-handoff", Packs.handoffInstruction(t.id), handoffWall, handoffTokens,
                            rd.resolve("sessions"), handoffSid, true, rd.resolve("packs/stable.md").toString(), ws.toString(),
                            proxy.base(), proxy.abort(), firstTokenTimeoutMs, compactionTrigger, maxTurns, (String) cfg.get("model"),
                            (Map<String, String>) cfg.get("_agent_env")));
            recordHandoffContent(ws, manifest, rec, t.id, hp);
            rec.put("handoff_seconds", h.seconds());
            rec.put("handoff_rc", h.rc());
            RunBenchSupport.snapshot(ws, "phase/" + t.id + "-handoff");   // so a future resume can skip this (see tagExists check above)
        } finally { proxy.stop(); }
    }

    /** the handoff file's content, once it exists: added to the manifest's own running list and
     *  hash-objected into git (one hash-object, not the old double call that wrote the object
     *  twice) - shared by handoffStep()'s own cold-session tail and its #300 fast-path branch
     *  above, the two places this is ever computed for the FIRST time (the tagExists "already
     *  completed" branch above deliberately skips the sha - see its own comment). A no-op when the
     *  file doesn't exist (the model, despite being asked, wrote nothing - callers still record
     *  handoff_seconds/handoff_rc/the completion tag regardless, same as always). */
    private void recordHandoffContent(final Path ws, final Map<String, Object> manifest, final Map<String, Object> rec,
                                       final String taskId, final Path hp) throws Exception {
        if (!Files.isRegularFile(hp)) return;
        final String txt = Files.readString(hp);
        manifestHandoffs(manifest).add(new String[]{taskId, txt});
        String sha;
        try { sha = RunBenchSupport.gitOut(ws, "hash-object", "-w", hp.toString()); }
        catch (Exception e) { log.warn("git hash-object failed for handoff {}: {}", taskId, e.toString()); sha = null; }
        rec.put("handoff_sha", sha == null || sha.isEmpty() ? null : sha);
        rec.put("handoff_chars", txt.length());
    }

    /** #351: when a task is STILL timed out (rc==124) after rescueStep() has had its chance -
     *  either budget_rescue wasn't enabled, or the rescue attempt itself didn't finish in time -
     *  handoffStep() is never called for it (see the task-loop call site above), so without this,
     *  a dependent task gets only the existing one-line docs/PROGRESS.md fallback (#262) and
     *  nothing in its own "Notes left by earlier tasks" section at all. Harness-authored, like
     *  Packs.fallbackStatus() - no LLM call, so it can never fail the same way the wrap-up/rescue
     *  attempts that led here already did. Best-effort: a failure here must never fail the run. */
    private void fallbackHandoffStep(final Path rd, final Path ws, final Map<String, Object> manifest, final PlanTask t, final Map<String, Object> rec) {
        final Path hp = ws.resolve("handoff").resolve(t.id + ".md");
        if (Files.isRegularFile(hp)) return;   // a successful same-session FinalTurn/rescue write already covered this
        try {
            final String checkpoint = lastJudgeCheckpoint(rd, t.id);
            final String text = "Harness-authored fallback (no model turn available): " + t.id + " hit its task-wall timeout after "
                    + rec.get("turns") + " turns and " + rec.get("compactions") + " compactions, before any status could be written.\n\n"
                    + (checkpoint == null ? "No judge checkpoint was recorded for this task."
                            : "Last judge checkpoint for this task:\n\n" + checkpoint)
                    + "\n\nSee docs/PROGRESS.md and this task's own session log for whatever work exists.";
            Files.createDirectories(hp.getParent());
            Files.writeString(hp, text);
            recordHandoffContent(ws, manifest, rec, t.id, hp);
            rec.put("handoff_seconds", 0.0);
            rec.put("handoff_rc", 0);
            rec.put("handoff_by_harness", true);
            RunBenchSupport.snapshot(ws, "phase/" + t.id + "-handoff");
        } catch (final Exception e) {
            log.warn("could not write fallback handoff for {}: {}", t.id, e.toString());
        }
    }

    /** #351: this task's own most recent judge checkpoint (judge/{id}-...-turn{N}.md, see Judge.java
     *  and BenchController's own richer /tasks/{taskId}/judge endpoint), picked by file
     *  modification time rather than parsing the attempt-stamp/turn-number naming convention that
     *  endpoint already handles - this fallback only needs SOME recent checkpoint text, not a full
     *  timeline. Trimmed to its own "## Recommendation" section when present (the judge's actual
     *  verdict, not the full prompt it saw). Returns null when no judge checkpoint exists at all,
     *  or on any read failure - best-effort, same reasoning as fallbackHandoffStep() itself. */
    static String lastJudgeCheckpoint(final Path rd, final String taskId) {
        final Path dir = rd.resolve("judge");
        if (!Files.isDirectory(dir)) return null;
        try (var stream = Files.list(dir)) {
            final Path latest = stream
                    .filter(f -> f.getFileName().toString().startsWith(taskId + "-") && f.getFileName().toString().endsWith(".md"))
                    .max(Comparator.comparingLong(f -> {
                        try { return Files.getLastModifiedTime(f).toMillis(); } catch (IOException e) { return 0L; }
                    }))
                    .orElse(null);
            if (latest == null) return null;
            final String content = Files.readString(latest);
            final int recIdx = content.indexOf("## Recommendation");
            final String checkpoint = recIdx >= 0 ? content.substring(recIdx + "## Recommendation".length()).strip() : content.strip();
            return checkpoint.length() > 600 ? checkpoint.substring(0, 600) + "…" : checkpoint;
        } catch (final IOException e) {
            return null;
        }
    }

    /** #307: the run-level "budget_rescue" cfg map, with any budget_rescue_enabled restart override
     *  (RESTART_OVERRIDE_KEYS' own flat name) replacing the corresponding nested key -
     *  rescue_wall_sec/rescue_tokens/rescue_keep_recent_turns are deliberately NOT task-overridable,
     *  same reasoning as effectiveJudgeCfg()'s own judge_wall_sec/judge_tokens exclusion: the
     *  rescue session's own budget is a run/operator-level choice, not exposed on the restart form. */
    static Map<String, Object> effectiveRescueCfg(final Map<String, Object> cfg, final Map<String, Object> taskOverride) {
        final Map<String, Object> runLevel = cfg.get("budget_rescue") instanceof Map<?, ?> r ? (Map<String, Object>) r : Map.of();
        final Map<String, Object> out = new LinkedHashMap<>(runLevel);
        if (taskOverride.get("budget_rescue_enabled") instanceof Boolean b) out.put("enabled", b);
        // #322: previously only "enabled" was task-overridable here - the rescue session's own
        // wall/token/keep-recent-turns knobs were deliberately operator-wide-only. Lifted: a task
        // that's already shown it needs more room (this is read right after that same task's own
        // main session just exhausted its budget) should be able to get a correspondingly larger
        // rescue attempt too, not just a bigger main-session budget next time.
        if (taskOverride.get("rescue_wall_sec") instanceof Number w) out.put("wall_sec", w.intValue());
        if (taskOverride.get("rescue_tokens") instanceof Number t) out.put("tokens", t.longValue());
        if (taskOverride.get("rescue_keep_recent_turns") instanceof Number k) out.put("keep_recent_turns", k.intValue());
        return out;
    }

    /** #307/#351/#413: whether a task's own final rc earns it a rescue attempt - extracted as a pure
     *  predicate (same reasoning as isJudgeTurn/shouldRetryOnLengthFinish above) since rescueStep()
     *  itself is heavy orchestration with no direct test, same as handoffStep(). Three rc values
     *  are eligible: 3 (BudgetExhausted) and 4 (ContextWindowExceeded, #299-adjacent: found live
     *  that disabling compaction leaves a long-running session permanently stuck the instant it
     *  hits the window, since every later request would ALSO overflow it - a compacted reconnect
     *  is exactly the fix either rc needs), and - since #351 - 124 (the task-wall timeout).
     *  124 was originally excluded on the theory that a task whose own wall budget is spent has
     *  "nothing left to spend" - wrong on inspection: rescueStep()'s own rescue_wall_sec is a
     *  SEPARATE budget from the main session's task_wall, so that reasoning doesn't actually hold.
     *  Found live on T7 (run svc-20261013-opt): the task was genuinely close to done (judge: ON
     *  TRACK, "1 test failure left") but burned ~half its 9h wall budget on compaction-triggered
     *  re-prefills before the hard timeout cut it off mid-fix - exactly the situation a bounded,
     *  aggressively-compacted rescue reconnect (see rescueStep()'s own javadoc) is built for.
     *
     *  #413: a FOURTH, rc-independent path is also eligible: `rec`'s own "status_by_harness" flag
     *  (sessionWithPolicy()'s own marker, set exactly when the main session, its length-retry, its
     *  re-engage call, AND its dedicated wrap-up call all failed to produce a self-reported status,
     *  forcing the harness to synthesize the "unfinished | budget exhausted before a status was
     *  written" fallback line itself). Found live on T19 (run svc-20261013-opt): rc==0 (a normal,
     *  non-exceptional return - the session never threw BudgetExhausted/ContextWindowExceeded at
     *  all), yet the task's own final turns show the model/oMLX stalling outright (a truncated,
     *  finish-less reply, then two fully empty ones, each pegged at the run's own
     *  --first-token-timeout ceiling) - real, substantial work (a full docker-compose.yml,
     *  Dockerfiles, db-init scripts) was already committed, but with no status line, no
     *  verification, and no handoff note for the tasks depending on it, since rc==0 fell outside
     *  every existing rc check above. status_by_harness is exactly the signal the rc checks were
     *  trying to approximate - the harness genuinely has nothing else to go on - so it is checked
     *  directly instead of trying to enumerate every rc a silent stall might arrive under.
     *
     *  Never when the feature is off; never on the `pi` harness (no in-process loop to
     *  compact/reconnect). `rescueCfg` is the task's own EFFECTIVE config (effectiveRescueCfg()'s
     *  result), not the raw run-level map directly, so a restarted task can flip this independent
     *  of the run's own original choice. */
    static boolean shouldRescue(final Map<String, Object> rescueCfg, final Map<String, Object> rec, final String harness) {
        return Boolean.TRUE.equals(rescueCfg.get("enabled"))
                && ((rec.get("rc") instanceof Integer rc && (rc == 3 || rc == 4 || rc == 124))
                        || Boolean.TRUE.equals(rec.get("status_by_harness")))
                && !"pi".equals(harness);
    }

    /** #307/#351/#413: the instruction must name the REAL reason accurately - "ran out of budget" would
     *  be misleading for rc=4, a different problem (too much accumulated history, not too much
     *  spent) that the agent would otherwise have no way to know actually happened; rc=124 is a
     *  third, distinct problem again (wall-clock time, not tokens or context space at all - #351
     *  found this live on T7, which still had well under half its token budget spent). A fourth
     *  reason, found live on T19: none of rc 3/4/124 at all (rc==0, a normal return) - the session
     *  simply stalled and produced nothing a status-written fallback could skip past (see
     *  shouldRescue()'s own javadoc for the full story) - telling the model "ran out of X" here
     *  would be actively wrong, since nothing was actually exhausted. Extracted as a pure function,
     *  same reasoning as shouldRescue() itself. */
    static String rescueReason(final Map<String, Object> rec) {
        final String base = Integer.valueOf(4).equals(rec.get("rc"))
                ? "ran out of context window space (its own conversation history grew too large to fit)"
                : Integer.valueOf(124).equals(rec.get("rc"))
                ? "ran out of its own wall-clock time budget"
                : Integer.valueOf(3).equals(rec.get("rc"))
                ? "ran out of its own token budget"
                : "stalled - its own session ended without producing any further output or a status "
                + "report (not a budget or time limit being reached)";
        // #415: rec's own "upstream_error_text" (sessionWithPolicy's fallback-status block, set from
        // JournalFacts.lastUpstreamError) - when the model server itself reported something, the
        // rescue session should be told the real cause instead of just this method's own best guess
        return rec.get("upstream_error_text") instanceof String s && !s.isBlank()
                ? base + " (the model server itself reported: " + s + ")" : base;
    }

    /** #351/#413: a wall-timeout OR harness-fallback-status rescue must never fall back to
     *  "unlimited" - a task that just proved it can run away on repeated compactions (rc=124), or
     *  that just proved its own session can go silent entirely (status_by_harness, #413), cannot be
     *  trusted with an unbounded second attempt. 1200s (20min) is a deliberately small cap: the
     *  rescue's whole premise is a CHEAP, aggressively-compacted reconnect (see
     *  DEFAULT_WALL_TIMEOUT_RESCUE_KEEP_RECENT_TURNS below), not a second full task-wall's worth of
     *  room. An explicit `rescue_wall_sec` override always wins, for every rc alike. */
    static final int DEFAULT_WALL_TIMEOUT_RESCUE_WALL_SEC = 1200;

    static int rescueWallFor(final Map<String, Object> rescueCfg, final boolean rescuingBounded) {
        // 0 means unlimited (the system-wide "0 = no budget" convention) for every OTHER rescue
        // reason - but never for a bounded rescue (see DEFAULT_WALL_TIMEOUT_RESCUE_WALL_SEC)
        return rescueCfg.get("wall_sec") instanceof Number w ? w.intValue()
                : rescuingBounded ? DEFAULT_WALL_TIMEOUT_RESCUE_WALL_SEC : 0;
    }

    /** #351/#413: a wall-timeout OR harness-fallback-status rescue reconnects to the task with the
     *  LARGEST accumulated history in the whole run (that is how it got here either way) - the
     *  operator-wide keep-recent-turns default is tuned for a reactive, in-session compaction
     *  during otherwise-normal progress, not for minimizing a cold reconnect's own re-prefill cost
     *  under a tight rescue wall. Keep only the last 2 turns: enough for the model to see its own
     *  most recent attempt and the harness's just-computed verification output, cheap enough to fit
     *  the bounded wall above even on a session that was already deep into repeated compactions. */
    static final int DEFAULT_WALL_TIMEOUT_RESCUE_KEEP_RECENT_TURNS = 2;

    static int rescueKeepRecentTurnsFor(final Map<String, Object> rescueCfg, final boolean rescuingBounded, final int operatorDefault) {
        return rescueCfg.get("keep_recent_turns") instanceof Number krt ? krt.intValue()
                : rescuingBounded ? DEFAULT_WALL_TIMEOUT_RESCUE_KEEP_RECENT_TURNS : operatorDefault;
    }

    /** #307/#351/#413: the budget-exhaustion rescue step - the second "fail recovery" mechanism
     *  alongside the Judge (#265). When a task's own main session chain ends via rc=3
     *  (BudgetExhausted), rc=4 (ContextWindowExceeded), rc=124 (the task-wall timeout, since #351),
     *  or the harness had to synthesize its own "unfinished" status because nothing else produced
     *  one at all (`status_by_harness`, since #413 - see shouldRescue()'s own javadoc) - give it ONE
     *  more dedicated session, on its own separate bounded budget, before accepting that final
     *  state. Opt-in (cfg "budget_rescue"."enabled") - a real, visible, restart-tunable knob,
     *  same philosophy as the Judge, never a silent default - and scoped to the `ref` harness only:
     *  PiAgent has no in-process turn loop and no concept of `continueSession`-time compaction to
     *  hook into, the same reason Judge/FinalTurn are PiAgent no-ops elsewhere in this file.
     *
     *  Deliberately NOT a cold `continueSession=true` reconnect to the raw history (that is exactly
     *  the #300 stall mechanism, on the task with the LARGEST accumulated history in the whole run)
     *  and NOT a trajectory-free fresh pack either (loses whatever the session just tried and ruled
     *  out immediately before running out of budget - a real risk, not hypothetical: a live judge
     *  checkpoint on this exact codebase once had to stop a session oscillating on the same fix four
     *  times in a row). The middle ground: reconnect, but compact the loaded history down to its
     *  most recent turns BEFORE the first request (`ReferenceAgent`'s own `compactOnLoadKeepRecentTurns`
     *  - this IS issue #300's original suggested fix, finally with a caller that genuinely needs a
     *  cold reconnect rather than the in-session injection handoff/wrap-up use instead). On top of
     *  that, the instruction names the CURRENT, concrete problem explicitly - the harness's own just-
     *  computed verification output - since the model's own last tool call before running out of
     *  budget was almost certainly not "run the tests"; nothing in the trajectory, compacted or not,
     *  reliably surfaces that on its own.
     *
     *  Tracked separately (`rescue_seconds`/`rescue_rc`/`rescue_succeeded` on `rec`, mirroring
     *  `wrapup_seconds`) for the later, still-not-built scoring half (§19 of the project's own
     *  Second Brain note): this method only builds the mechanism and the data a scoring pass would
     *  need, not the scoring formula itself. A successful rescue (`rc()==0`) updates `rec`'s own
     *  "rc"/"verification" in place, so everything the caller does afterward - the phase snapshot,
     *  `logTaskAttempt`, `handoffStep` - sees the POST-rescue state, not the exhausted one. */
    private void rescueStep(final Map<String, Object> cfg, final String runId, final PlanTask t, final Path rd, final Path ws,
                             final Path journal, final Map<String, Object> rec, final Map<String, Object> taskOverride) throws Exception {
        final Map<String, Object> rescueCfg = effectiveRescueCfg(cfg, taskOverride);
        if (!shouldRescue(rescueCfg, rec, (String) cfg.get("harness"))) return;
        // #413: a harness-synthesized status (the session stalled outright, rc often 0) gets the
        // same bounded treatment as a wall timeout - see rescueWallFor()/rescueKeepRecentTurnsFor()
        final boolean rescuingBounded = Integer.valueOf(124).equals(rec.get("rc")) || Boolean.TRUE.equals(rec.get("status_by_harness"));
        final String reason = rescueReason(rec);
        final String verificationSummary = VerifyTask.verifyText((Map<String, Object>) rec.get("verification"));
        // 0 means unlimited (the system-wide "0 = no budget" convention)
        final long rescueTokens = rescueCfg.get("tokens") instanceof Number n ? n.longValue() : 0L;
        final var proxy = proxies.start(journal, rescueTokens, null, effectiveSamplerOverrides(cfg, taskOverride));
        final long firstTokenTimeoutMs = cfg.get("first_token_timeout_ms") instanceof Number n ? n.longValue() : 0L;
        final int compactionTrigger = effectiveCompactionTrigger(taskOverride,
                cfg.get("compaction_trigger") instanceof Number ct ? ct.intValue() : props.compactionTrigger());
        final int maxTurns = cfg.get("max_turns") instanceof Number mt ? mt.intValue() : ReferenceAgent.DEFAULT_MAX_TURNS;
        final int keepRecentTurns = rescueKeepRecentTurnsFor(rescueCfg, rescuingBounded, props.keepRecentTurns());
        // the only live signal that a rescue is in progress for this task: the call below reuses the
        // task's own canonical session id with continueSession=true, which never writes a new session
        // header (see ReferenceAgent.run()'s own continueSession branch) - so nothing in the session
        // file itself distinguishes "a rescue is working" from "more of the same already-ended
        // session" (found live on T14: a stray failed wrap-up sub-call's own "end" record made the
        // task look finished while the rescue below was still genuinely running for another ~2h).
        // Tailer picks this up the same way it already watches packs/instructions - a plain file's
        // existence - so the live view can say "budget rescue in progress" instead of guessing from a
        // misleading endedStage.
        Files.createDirectories(rd.resolve("rescue"));
        Files.writeString(rd.resolve("rescue/" + t.id + ".start"), reason);
        try {
            // the SAME canonical id as the task's own main session - continueSession=true must find
            // and compact THAT task's own history, not a separate one
            final String sid = UUID.nameUUIDFromBytes(("agentbench/" + runId + "/" + t.id).getBytes()).toString();
            final int rescueWall = rescueWallFor(rescueCfg, rescuingBounded);
            final ReferenceAgent.SessionResult h = RunBenchSupport.runBounded(rescueWall, t.id + "-rescue", rd.resolve("sessions"), sid, proxy.abort(),
                    () -> agent.run(t.id + "-rescue", Packs.rescueInstruction(t.id, reason, verificationSummary), rescueWall, rescueTokens,
                            rd.resolve("sessions"), sid, true, rd.resolve("packs/stable.md").toString(), ws.toString(),
                            proxy.base(), proxy.abort(), firstTokenTimeoutMs, compactionTrigger, maxTurns, (String) cfg.get("model"),
                            (Map<String, String>) cfg.get("_agent_env"), null, false, null, keepRecentTurns));
            rec.put("rescue_seconds", h.seconds());
            rec.put("rescue_rc", h.rc());
            if (h.rc() == 0) {
                rec.put("rc", 0);
                rec.put("rescue_succeeded", true);
                rec.put("verification", VerifyTask.verify(ws, cfg, rd.resolve("verify/" + t.id + "-rescue.log")));
            }
            appendRescueLesson(rd, t.id, reason, h.rc() == 0);
        } finally {
            proxy.stop();
            Files.writeString(rd.resolve("rescue/" + t.id + ".done"), "");
        }
    }

    static List<String[]> manifestHandoffs(final Map<String, Object> manifest) {
        if (!(manifest.get("handoffs") instanceof List<?>)) manifest.put("handoffs", new ArrayList<String[]>());
        return (List<String[]>) manifest.get("handoffs");
    }

    /** #303, found live: `manifestHandoffs(manifest)` and the local `handoffs` list every
     *  Packs.taskPack() call reads from used to be two DIFFERENT lists - handoffStep() only ever
     *  appended to the former, so "## Notes left by earlier tasks" never fired for any task, in any
     *  run, ever. Returning manifestHandoffs(manifest) itself (not a copy) is what fixes that going
     *  forward: handoffStep()'s own .add() calls now land in the one list taskPack() actually reads.
     *
     *  That alone still starts a RESUMED run's list empty, even though earlier tasks' handoffs
     *  already exist on disk (the in-memory manifest is rebuilt fresh on every orchestratedPhase()
     *  call, same as every other R18 resume case in this file - the git tags/handoff/*.md files are
     *  the real source of truth across a resume, not any in-memory state). So this also backfills:
     *  every task already tagged phase/{id}-handoff gets its handoff/{id}.md content read straight
     *  into the list before the task loop starts, the same re-read handoffStep()'s own resumed-tag
     *  branch already does for the ONE task it happens to be revisiting. */
    static List<String[]> loadHandoffs(final Path ws, final List<PlanTask> tasks, final Map<String, Object> manifest) {
        final List<String[]> handoffs = manifestHandoffs(manifest);
        for (final PlanTask t : tasks) {
            if (!RunBenchSupport.tagExists(ws, "phase/" + t.id + "-handoff")) continue;
            final Path hp = ws.resolve("handoff").resolve(t.id + ".md");
            if (!Files.isRegularFile(hp)) continue;
            try { handoffs.add(new String[]{t.id, Files.readString(hp)}); }
            catch (IOException e) { log.warn("could not read already-tagged handoff for {}: {}", t.id, e.toString()); }
        }
        return handoffs;
    }

    /** #404: a run-wide, dynamically-populated counterpart to #403's static stable.md gotchas -
     *  every time the judge catches a DIFFERENT task off track (see appendLesson() below), it's
     *  worth knowing regardless of the dependency graph (unlike handoffs, deliberately scoped to
     *  direct deps - see Packs.taskPack()'s own javadoc on this). Lives directly under the run's
     *  own results dir, so (a) it persists after the job ends, same as manifest.json/task_attempts.
     *  jsonl/judge/*, and (b) it's read fresh at the start of every later task in the SAME run,
     *  never mutated by that read. */
    static String readLessons(final Path rd) {
        final Path lp = rd.resolve("lessons.md");
        if (!Files.isRegularFile(lp)) return null;
        try { return Files.readString(lp); }
        catch (IOException e) { log.warn("could not read {}: {}", lp, e.toString()); return null; }
    }

    /** Shared by appendLesson()/appendRescueLesson() below - appends are safe under concurrency
     *  (a parallel wave can have more than one task's judge or rescue firing at once):
     *  StandardOpenOption.APPEND opens with O_APPEND, and POSIX guarantees a single write() of
     *  this size lands atomically, never interleaved with a concurrent one. */
    private static void writeLesson(final Path rd, final String heading, final String body) {
        try {
            Files.writeString(rd.resolve("lessons.md"), "## " + heading + "\n" + body.strip() + "\n\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("could not append a lesson ({}): {}", heading, e.toString());
        }
    }

    /** #404: called from buildJudgeCheckpoint() the moment a checkpoint comes back OFF TRACK -
     *  appends the judge's own recommendation (the richest, most specific signal already in hand;
     *  no extra summarising LLM call) so later, unrelated tasks in this same run get the same
     *  course-correction the current task just needed. A future harness feature (tracked
     *  separately) can later mine this file at job-end to promote durable facts into #403's
     *  static, cross-run gotchas list - this method only captures the raw material for that. */
    static void appendLesson(final Path rd, final String taskId, final int turn, final String recommendation) {
        writeLesson(rd, taskId + " (turn " + turn + ")", recommendation);
    }

    /** #404: called from rescueStep() once a rescue attempt concludes, win or lose. Weaker signal
     *  than a judge's own specific diagnosis (there's no comparably rich text to carry forward -
     *  just which budget ran out and whether the reconnect recovered a working result), but still
     *  worth knowing: several unrelated tasks needing the same KIND of rescue in one run is itself
     *  informative, the same way a cluster of judge interventions would be. */
    static void appendRescueLesson(final Path rd, final String taskId, final String reason, final boolean succeeded) {
        writeLesson(rd, taskId + " (budget rescue)", "Needed a budget-rescue reconnect because it " + reason + ". Rescue "
                + (succeeded ? "succeeded - a compacted reconnect recovered a working result."
                             : "did not recover a working result - the task is still short of its acceptance criteria."));
    }

    /** Extracted from a `javac -version` (or equivalent) invocation's own output - `javac 21.0.12.1`
     *  on 9+, `javac 1.8.0_292` pre-9 - so pure and independently testable, unlike the subprocess
     *  calls around it. Null when the text doesn't look like a javac version line at all (the
     *  binary doesn't exist, the path is wrong, a permissions error, ...). */
    static Integer parseJavacMajor(final String javacVersionOutput) {
        if (javacVersionOutput == null) return null;
        final var m = Pattern.compile("javac\\s+(?:1\\.)?(\\d+)").matcher(javacVersionOutput);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** Actually runs the candidate's own `bin/javac -version` rather than trusting a path or a
     *  tool's own claim about what it points at - see discoverJavaHome()'s own javadoc for why
     *  that trust would be misplaced here. */
    private static boolean javacReportsMajor(final String javaHome, final int major) {
        if (javaHome == null || javaHome.isBlank()) return false;
        final var r = DockerService.sh(10, javaHome + "/bin/javac", "-version");
        return r.rc() == 0 && Integer.valueOf(major).equals(parseJavacMajor(r.out()));
    }

    /** #398/#402/#403, found live: nearly every task across both runs independently rediscovered
     *  this host's JDK mismatch from scratch, each paying real wall-clock time to do so - this is
     *  the harness-side fix, not just a stable.md hint (#403 still carries one as a fallback for
     *  hosts this doesn't cover). The macOS canonical lookup (`/usr/libexec/java_home -v <major>`,
     *  already tried before this method is ever called) turned out to be the actual culprit: found
     *  live that it returns exit 0 and a JVM of the WRONG major version with no error at all when
     *  the host has no matching entry in its own registry - this host has only JDK 26 registered
     *  there, so "-v 21" simply hands back 26's own home. Homebrew's and SDKMAN's own JDK installs
     *  are invisible to that registry entirely (neither registers itself with it), even when a
     *  real, working install of the requested major version exists right there on disk.
     *
     *  Deliberately never a single hardcoded version-specific path - only the conventional PARENT
     *  directories different installers already use (Homebrew's `opt/openjdk@<major>` on both
     *  Apple Silicon and Intel prefixes, SDKMAN's own candidates directory) - and every candidate,
     *  including the java_home utility's own answer, is validated by actually running ITS javac
     *  and checking what major version IT reports, never assumed correct from a path or name match
     *  alone. Falls back to the (unvalidated) java_home utility result if nothing else checks out -
     *  never worse than this method's own caller's previous behavior. */
    static String discoverJavaHome(final int major) {
        final String fromUtil = DockerService.sh(10, "/usr/libexec/java_home", "-v", String.valueOf(major)).out().strip();
        if (javacReportsMajor(fromUtil, major)) return fromUtil;
        final List<String> candidates = new ArrayList<>(List.of(
                "/opt/homebrew/opt/openjdk@" + major,   // Homebrew, Apple Silicon
                "/usr/local/opt/openjdk@" + major));    // Homebrew, Intel
        try (var sdk = Files.list(Path.of(System.getProperty("user.home"), ".sdkman/candidates/java"))) {
            sdk.filter(p -> p.getFileName().toString().contains(String.valueOf(major))).forEach(p -> candidates.add(p.toString()));
        } catch (IOException e) { log.debug("no sdkman java candidates to probe for JDK {}: {}", major, e.toString()); }
        for (final String candidate : candidates) if (javacReportsMajor(candidate, major)) return candidate;
        log.warn("could not find a validated JDK {} install on this host; falling back to the unvalidated java_home utility result ({})", major, fromUtil);
        return fromUtil;
    }

    /** the agent's parallelisation-plan step; the harness evaluates the schedule and uses it when valid */
    private Map<String, Object> parallelPlanStep(final Map<String, Object> cfg, final String runId, final Path rd, final Path ws, final Path journal, final Map<String, Object> manifest, final List<PlanTask> tasks, final String stable) throws Exception {
        Files.writeString(rd.resolve("packs/PARALLEL_PLAN.md"), Packs.parallelPlanPack(tasks));
        int rc = 0; double seconds = 0;
        // R18: resume past this step if a prior attempt of THIS SAME run already finished it -
        // re-running would discard a perfectly good plan and pay for another full agent turn
        if (RunBenchSupport.tagExists(ws, "phase/parallel_plan") && Files.isRegularFile(ws.resolve("docs/parallel_plan.json"))) {
            log.info("run {}: phase/parallel_plan already completed in a prior attempt, resuming past it", runId);
            ((Map<String, String>) manifest.get("snapshots")).put("parallel_plan", RunBenchSupport.gitOut(ws, "rev-parse", "phase/parallel_plan"));
        } else {
            // same reasoning as #263's task-loop override, and p0_definition/p1_plan above: a
            // restarted PARALLEL_PLAN's override must size the proxy at creation, not just whatever
            // this call site would otherwise pass in
            final var ppOverride = taskOverride(cfg, "PARALLEL_PLAN");
            // 0 means unlimited (the system-wide "0 = no budget" convention)
            final long parallelPlanTokensDefault = cfg.get("parallel_plan_tokens") instanceof Number ppt ? ppt.longValue() : 0L;
            final long parallelPlanTokens = effectiveLong(ppOverride, "task_tokens", parallelPlanTokensDefault);
            final var proxy = proxies.start(journal, parallelPlanTokens, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
            final long firstTokenTimeoutMs = ppOverride.get("first_token_timeout_sec") instanceof Number sec ? sec.longValue() * 1000L
                    : cfg.get("first_token_timeout_ms") instanceof Number n ? n.longValue() : 0L;   // 0 = unlimited
            final int compactionTrigger = cfg.get("compaction_trigger") instanceof Number ct ? ct.intValue() : props.compactionTrigger();
            final int maxTurns = effectiveInt(ppOverride, "max_turns",
                    cfg.get("max_turns") instanceof Number mt ? mt.intValue() : ReferenceAgent.DEFAULT_MAX_TURNS);
            final String planSid = UUID.nameUUIDFromBytes(("agentbench/" + runId + "/PARALLEL_PLAN").getBytes()).toString();
            // 0 means unlimited (the system-wide "0 = no budget" convention)
            final int parallelPlanWallDefault = cfg.get("parallel_plan_wall_sec") instanceof Number pw ? pw.intValue() : 0;
            final int parallelPlanWall = effectiveInt(ppOverride, "task_wall_sec", parallelPlanWallDefault);
            ReferenceAgent.SessionResult prec;
            try {
                prec = RunBenchSupport.runBounded(parallelPlanWall, "PARALLEL_PLAN", rd.resolve("sessions"), planSid, proxy.abort(),
                        () -> runAgentSession(cfg, "PARALLEL_PLAN", Packs.parallelPlanPack(tasks) + "\n\n" + Packs.PARALLEL_PLAN_INSTRUCTION, parallelPlanWall, parallelPlanTokens,
                                rd.resolve("sessions"), planSid, false, rd.resolve("packs/stable.md").toString(), ws.toString(),
                                proxy.base(), proxy.abort(), firstTokenTimeoutMs, compactionTrigger, maxTurns, (String) cfg.get("model"),
                                (Map<String, String>) cfg.get("_agent_env")));
            } finally { proxy.stop(); }
            rc = prec.rc(); seconds = prec.seconds();
            ((Map<String, String>) manifest.get("snapshots")).put("parallel_plan", RunBenchSupport.snapshot(ws, "phase/parallel_plan"));
        }
        final Map<String, Object> pp = Packs.parseParallelPlan(ws.resolve("docs/parallel_plan.json"));
        final Map<String, Object> ev = PlanParser.evaluateParallelPlan(pp, tasks);
        for (String f : List.of("PARALLEL_PLAN.md", "parallel_plan.json")) {
            final Path src = ws.resolve("docs").resolve(f);
            if (Files.isRegularFile(src)) Files.copy(src, rd.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("seconds", seconds);
        out.put("rc", rc);
        out.put("over_budget", rc == 124);
        out.put("parse_ok", pp != null);
        out.put("plan", pp);
        out.put("evaluation", ev);
        out.put("used", Boolean.TRUE.equals(ev.get("valid")) && ((Number) cfg.getOrDefault("parallel", 1)).intValue() > 1);
        manifest.put("parallel_plan", out);
        return out;
    }

    /** the parallel wave: worktrees + HOME copies + per-task tagged journals, a bounded pool, the
     *  merge in plan order (conflicts kept and surfaced), the wave verification, ownership
     *  violations, overlaps, and the FIX step when the merge broke something. */
    private Map<String, Object> runParallelWave(Map<String, Object> cfg, String runId, Path rd, Path ws, Path home, Path journal,
                                                Map<String, Object> manifest, List<PlanTask> tasks, Map<String, String[]> snapshots,
                                                List<PlanTask> waveTasks, int parallel, Map<String, List<String>> ownership,
                                                String promptText, String stable, int taskWall, long taskTokens,
                                                List<String[]> handoffs, String prevVerified, List<String[]> mergeConflicts, String planSha) throws Exception {
        final String pre = manifestSnap(manifest, "p1", planSha);
        final List<String> waveIds = waveTasks.stream().map(t -> t.id).toList();
        final long t0 = System.currentTimeMillis();
        cfg.put("_manifest", manifest);
        final Map<String, Path> taskJournals = new LinkedHashMap<>();
        final Map<String, String> packs = new LinkedHashMap<>();
        // ConcurrentHashMap, not LinkedHashMap: put() from inside the per-task threads below races
        // on a plain LinkedHashMap's bucket array/insertion-order list during a resize, silently
        // losing an entry or corrupting the structure - the Semaphore below only bounds how many
        // threads run at once, it provides no mutual exclusion on this shared map. Iteration order
        // is never relied on for either map (the merge loop walks waveTasks/waveIds, not these).
        final Map<String, Map<String, Object>> recs = new ConcurrentHashMap<>();
        // R18: a wave task whose phase tag already exists finished its agent session in a prior
        // attempt of THIS SAME run (the tag is only ever written after that session returns
        // normally - see the merge loop below). Its work sits on the still-extant "task/<id>"
        // branch (worktree removal never deletes the branch itself), so skip re-creating its
        // worktree/HOME copy and re-running its session entirely; a synthetic resumed rec keeps
        // it present in the manifest, and the merge loop below merges/re-tags it accordingly.
        final Set<String> resumedTaskIds = new LinkedHashSet<>();
        // #263: a worktree-add failure (a stale lock file, a leftover branch ref from a crashed
        // prior attempt, disk pressure) tracked separately from resumedTaskIds - such a task never
        // gets a worktree at all, unlike a resumed one (whose worktree may legitimately be gone
        // already, for the OPPOSITE reason: it finished and was cleaned up).
        final Set<String> setupFailedTaskIds = new LinkedHashSet<>();
        for (PlanTask t : waveTasks) {
            if (RunBenchSupport.tagExists(ws, "phase/" + t.id)) {
                log.info("run {}: phase/{} already completed in a prior attempt (parallel wave), resuming past it", runId, t.id);
                resumedTaskIds.add(t.id);
                recs.put(t.id, new LinkedHashMap<>(Map.of("id", t.id, "rc", 0, "resumed", true)));
                continue;
            }
            final Path wt = Path.of(ws + "-" + t.id), homeT = Path.of(home + "-home-" + t.id);
            // #263: this result used to be discarded entirely - unlike virtually every other git/
            // docker subprocess call in this class, which all check rc(). A failure here left the
            // worktree never created, yet the code proceeded unconditionally into clearStaleProgressLine/
            // copyTree against it and then started the task's agent session with its cwd pointing at
            // a non-existent directory - the real cause only surfacing much later as an opaque,
            // far-from-its-origin failure (a raw IOException from the merge loop's own snapshot()
            // call, or a cascade of agent tool errors).
            final var wtAdd = DockerService.sh(60, "git", "-C", ws.toString(), "worktree", "add", "-q", "-B", "task/" + t.id, wt.toString(), pre);
            if (wtAdd.rc() != 0) {
                log.warn("run {}: could not create worktree for {} (rc={}): {}", runId, t.id, wtAdd.rc(), wtAdd.out());
                final Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("id", t.id);
                failed.put("rc", 1);
                failed.put("error", "could not create worktree for " + t.id + ": " + wtAdd.out());
                failed.put("parallel_wave", waveIds);
                recs.put(t.id, failed);
                setupFailedTaskIds.add(t.id);
                continue;
            }
            RunBenchSupport.clearStaleProgressLine(wt, t.id);
            deleteRecursive(homeT);
            copyTree(home, homeT);
            DockerService.dockerTools(homeT.toString());   // each HOME copy gets the shim + plugins too
            taskJournals.put(t.id, rd.resolve("interactions-" + t.id + ".jsonl"));
            packs.put(t.id, Packs.taskPack(ws, t, tasks, snapshots, doneSet(snapshots), null, null, handoffs, readLessons(rd), prevVerified,
                    mergeConflicts.isEmpty() ? null : mergeConflicts, waveIds.stream().filter(x -> !x.equals(t.id)).toList()));
            Files.writeString(rd.resolve("packs/" + t.id + ".md"), packs.get(t.id));
        }
        final var sem = new java.util.concurrent.Semaphore(parallel);
        final Map<String, Throwable> errors = new ConcurrentHashMap<>();   // same race as recs above
        final List<Thread> threads = new ArrayList<>();
        for (PlanTask t : waveTasks) {
            if (resumedTaskIds.contains(t.id) || setupFailedTaskIds.contains(t.id)) continue;
            Thread th = new Thread(() -> {
                try {
                    sem.acquire();
                    try {
                        // #263: same override-before-the-proxy-is-sized reasoning as the sequential loop's own task call site
                        final var taskOverride = taskOverride(cfg, t.id);
                        final int effTaskWall = effectiveInt(taskOverride, "task_wall_sec", taskWall);
                        final long effTaskTokens = effectiveLong(taskOverride, "task_tokens", taskTokens);
                        final var proxy = proxies.start(taskJournals.get(t.id), effTaskTokens, t.id, effectiveSamplerOverrides(cfg, taskOverride));
                        Map<String, String> envT = RunBenchSupport.scrubbedEnv(Path.of(home + "-home-" + t.id).toString(), runId + "/" + t.id, (String) cfg.get("java_home"));   // the task's own HOME copy (R6)
                        final Path wsT = Path.of(ws + "-" + t.id);
                        final var judgeCheckpoint = buildJudgeCheckpoint(cfg, runId, wsT, rd, t.id, t.goal, effTaskWall, effTaskTokens, taskOverride);
                        Map<String, Object> rec;
                        try {
                            rec = sessionWithPolicy(cfg, runId, t.id, Packs.taskInstruction(t.id) + "\n\n" + packs.get(t.id),
                                    effTaskWall, effTaskTokens, rd, wsT, taskJournals.get(t.id), proxy,
                                    rd.resolve("packs/stable.md").toString(), null, envT, false, judgeCheckpoint);
                        } finally { proxy.stop(); intervene.clear(runId, t.id); }
                        // #307: same reasoning as the sequential loop's own call site - everything
                        // downstream (the manifest snapshot below, the wave merge) must see the
                        // post-rescue state, not the exhausted one
                        rescueStep(cfg, runId, t, rd, wsT, taskJournals.get(t.id), rec, taskOverride);
                        rec.put("parallel_wave", waveIds);
                        recs.put(t.id, rec);
                    } finally { sem.release(); }
                } catch (Throwable e) { errors.put(t.id, e); }
            }, "wave-" + t.id);
            threads.add(th);
            th.start();
        }
        joinAll(threads);
        // #102: a thrown wave-task exception used to be println'd (bypassing the SLF4J log every
        // other operator-relevant event in this class goes through) and the task simply vanished
        // from manifest.tasks with no other trace (recs.put only happens on the success path).
        // Logged properly now, AND a synthetic failure record takes the missing task's place so it
        // stays visible in the manifest instead of silently disappearing.
        for (Map.Entry<String, Throwable> e : errors.entrySet()) {
            log.warn("run {}: parallel task {} failed", runId, e.getKey(), e.getValue());
            final Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("id", e.getKey());
            failed.put("rc", 1);
            failed.put("error", String.valueOf(e.getValue()));
            failed.put("parallel_wave", waveIds);
            recs.put(e.getKey(), failed);
        }
        // merge in plan order; conflicts are kept (markers committed) and surfaced to the next packs and the integration task
        final List<String[]> conflicts = new ArrayList<>();
        final Map<String, Object> waveRec = new LinkedHashMap<>();
        waveRec.put("tasks", waveIds);
        waveRec.put("start_iso", nowIso());
        waveRec.put("seconds", Math.round((System.currentTimeMillis() - t0) / 100.0) / 10.0);
        waveRec.put("merges", new ArrayList<Map<String, Object>>());
        for (PlanTask t : waveTasks) {
            if (setupFailedTaskIds.contains(t.id)) {
                // #263: no worktree was ever created for this task - nothing to snapshot, merge, or
                // clean up; its synthetic failure record (added in the setup loop above) still needs
                // to land in manifest.tasks the same way every other task's record does below.
                final Map<String, Object> rec = recs.get(t.id);
                if (rec != null) {
                    rec.put("title", t.title);
                    ((List<Map<String, Object>>) manifest.get("tasks")).add(rec);
                }
                continue;
            }
            final var wt = Path.of(ws + "-" + t.id);
            final boolean resumed = resumedTaskIds.contains(t.id);
            // R18: a resumed task's commit + tag already exist from a prior attempt (its worktree
            // may already be gone too, if that attempt got as far as merging it) - re-snapshotting
            // a worktree that no longer exists would throw, so just read the existing tag instead.
            // The merge itself is safe to redo unconditionally: git merge of an already-merged
            // branch is a harmless no-op ("Already up to date").
            final String after = resumed ? RunBenchSupport.gitOut(ws, "rev-parse", "phase/" + t.id) : RunBenchSupport.snapshot(wt, "phase/" + t.id);
            ((Map<String, String>) manifest.get("snapshots")).put(t.id, after);
            snapshots.put(t.id, new String[]{pre, after});
            if (!resumed && recs.get(t.id) != null) {
                final var taskOverride = taskOverride(cfg, t.id);
                final int effMaxTurns = effectiveInt(taskOverride, "max_turns",
                        cfg.get("max_turns") instanceof Number mt ? mt.intValue() : ReferenceAgent.DEFAULT_MAX_TURNS);
                // #291: wt (the task's OWN worktree), not ws (the shared, pre-merge main workspace,
                // which doesn't get t.id's branch merged into it until the "git merge" call a few
                // lines below this one runs) - logTaskAttempt() -> reportedStatus() scans
                // docs/PROGRESS.md for this task's own self-reported status line, which only exists
                // in wt at this point. Passing ws here always returned null, for every parallel-wave
                // task, regardless of what it actually wrote.
                logTaskAttempt(rd, wt, t.id, taskOverride, effectiveInt(taskOverride, "task_wall_sec", taskWall),
                        effectiveLong(taskOverride, "task_tokens", taskTokens), effMaxTurns, recs.get(t.id));
            }
            final DockerService.Sh m = DockerService.sh(600, "git", "-C", ws.toString(), "merge", "--no-ff", "--no-edit", "-m", "merge " + t.id, "task/" + t.id);
            final List<String> cs = new ArrayList<>();
            for (String f : DockerService.sh(20, "git", "-C", ws.toString(), "diff", "--name-only", "--diff-filter=U").out().split("\n"))
                if (!f.isBlank()) cs.add(f);
            if (m.rc() != 0) {
                DockerService.sh(60, "git", "-C", ws.toString(), "add", "-A");
                DockerService.sh(60, "git", "-C", ws.toString(), "commit", "-q", "--allow-empty", "-m", "merge " + t.id + " (conflicts kept for integration)");
                for (String f : cs) conflicts.add(new String[]{t.id, f});
            }
            ((List<Map<String, Object>>) waveRec.get("merges")).add(Map.of("task", t.id, "ok", m.rc() == 0, "conflicts", cs));
            final Map<String, Object> rec = recs.get(t.id);
            if (rec != null) {
                rec.put("merge", Map.of("ok", m.rc() == 0, "conflicts", cs));
                rec.put("title", t.title);
                ((List<Map<String, Object>>) manifest.get("tasks")).add(rec);
            }
            // best-effort cleanup either way: for a resumed task this is a no-op if a prior attempt
            // already removed the worktree/HOME copy, and clears it if a crash happened in between
            DockerService.sh(300, "git", "-C", ws.toString(), "worktree", "remove", "--force", wt.toString());
            deleteRecursive(Path.of(home + "-home-" + t.id));
            // tagged records merge into the main journal; attribution is by tag (parallel windows overlap)
            final Path tj = taskJournals.get(t.id);
            if (tj != null && Files.isRegularFile(tj)) {
                Files.writeString(journal, Files.readString(tj), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Files.deleteIfExists(tj);
            }
        }
        final String merged = DockerService.sh(20, "git", "-C", ws.toString(), "rev-parse", "HEAD").out().strip();
        ((Map<String, String>) manifest.get("snapshots")).put("wave-" + String.join("-", waveIds), merged);
        final Map<String, Object> wv = VerifyTask.verify(ws, cfg, rd.resolve("verify/wave-" + String.join("-", waveIds) + ".log"));
        // problems caused by the parallelisation: what the merge broke, and who edited what they should not have
        final Map<String, List<String>> changed = new LinkedHashMap<>();
        for (String tid : waveIds) {
            final String[] sn = snapshots.get(tid);
            final List<String> files = new ArrayList<>();
            if (sn != null)
                for (String f : DockerService.sh(20, "git", "-C", ws.toString(), "diff", "--name-only", sn[0] + ".." + sn[1]).out().split("\n"))
                    if (!f.isBlank() && !f.startsWith("docs/PROGRESS.md") && !f.startsWith("handoff/")) files.add(f);
            changed.put(tid, files);
        }
        final var overlapSet = new TreeSet<String>();
        for (int a = 0; a < waveIds.size(); a++)
            for (int b = a + 1; b < waveIds.size(); b++)
                for (String f : changed.get(waveIds.get(a))) if (changed.get(waveIds.get(b)).contains(f)) overlapSet.add(f);
        final List<String[]> violations = new ArrayList<>();
        for (String tid : waveIds) {
            final List<String> own = ownership.getOrDefault(tid, List.of());
            if (!own.isEmpty())
                for (String f : changed.get(tid))
                    if (own.stream().noneMatch(g -> matchesOwnership(g, f)))
                        violations.add(new String[]{tid, f});
        }
        boolean broken = !conflicts.isEmpty() || (!Boolean.TRUE.equals(wv.get("green"))
                // a resumed task's synthetic rec (R18) carries no "verification" key - guard the cast instead of assuming one
                && waveIds.stream().anyMatch(tid -> recs.get(tid) != null && recs.get(tid).get("verification") instanceof Map<?, ?> vm && Boolean.TRUE.equals(vm.get("green"))));
        final Map<String, Object> problems = new LinkedHashMap<>();
        problems.put("conflicts", conflicts);
        problems.put("verification", Boolean.TRUE.equals(wv.get("green")) ? null : VerifyTask.verifyText(wv));
        problems.put("overlaps", overlapSet.stream().map(f -> new Object[]{f, waveIds.stream().filter(tid -> changed.get(tid).contains(f)).toList()}).toList());
        problems.put("ownership_violations", violations);
        problems.put("broken", broken);
        waveRec.putAll(problems);
        waveRec.put("verification", wv);
        ((List<Map<String, Object>>) manifest.get("waves")).add(waveRec);
        // the FIX step: the agent repairs what its parallelisation caused; its cost is recorded separately and scored
        if (broken && Boolean.TRUE.equals(cfg.getOrDefault("parallel_fix_enabled", true))) {
            final String fid = "FIX-W" + (((List<?>) manifest.get("waves")).size() + 1);
            Map<String, Object> frec;
            final String fafter;
            // R18: resume past the FIX step if a prior attempt of this same wave already ran it
            if (RunBenchSupport.tagExists(ws, "phase/" + fid)) {
                log.info("run {}: phase/{} already completed in a prior attempt, resuming past it", runId, fid);
                frec = new LinkedHashMap<>(Map.of("id", fid, "rc", 0, "resumed", true));
                fafter = RunBenchSupport.gitOut(ws, "rev-parse", "phase/" + fid);
            } else {
                // the FIX step's own budget (found live 2026-09-27): was a fixed literal (900s/20000
                // tokens) with no run-level control at all - 0 means unlimited (the system-wide
                // "0 = no budget" convention)
                final int fixWall = cfg.get("fix_wall_sec") instanceof Number fw ? fw.intValue() : 0;
                final long fixTokens = cfg.get("fix_tokens") instanceof Number ft ? ft.longValue() : 0L;
                Files.writeString(rd.resolve("packs/" + fid + ".md"), Packs.fixPack(ws, problems));
                final var proxy = proxies.start(journal, fixTokens, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
                try {
                    frec = sessionWithPolicy(cfg, runId, fid, Packs.FIX_INSTRUCTION.replace("{tasks}", String.join(", ", waveIds)).replace("{id}", fid),
                            fixWall, fixTokens, rd, ws, journal, proxy, rd.resolve("packs/stable.md").toString(), null, null);
                } finally { proxy.stop(); }
                fafter = RunBenchSupport.snapshot(ws, "phase/" + fid);
            }
            frec.put("title", "fix after merging " + String.join(", ", waveIds));
            frec.put("parallel_fix", true);
            frec.put("fixed_wave", waveIds);
            ((Map<String, String>) manifest.get("snapshots")).put(fid, fafter);
            final Map<String, Object> fv = VerifyTask.verify(ws, cfg, rd.resolve("verify/" + fid + ".log"));
            ((List<Map<String, Object>>) manifest.get("tasks")).add(frec);
            final Map<String, Object> fixSummary = new LinkedHashMap<>();   // Map.of() rejects nulls - a resumed frec has no seconds/reported
            fixSummary.put("id", fid);
            fixSummary.put("seconds", frec.get("seconds_total") == null ? frec.get("seconds") : frec.get("seconds_total"));
            fixSummary.put("resolved", Boolean.TRUE.equals(fv.get("green")));
            fixSummary.put("reported", frec.get("reported"));
            waveRec.put("fix", fixSummary);
        }
        waveRec.put("verification_text", VerifyTask.verifyText(wv));
        waveRec.put("merge_conflicts", conflicts);
        return waveRec;
    }

    /** #99: waits for every parallel-wave thread, but a cancelled job (WorkerService's cancelWatch
     *  interrupts the outer job-runner thread, which is blocked here in the common case) must reach
     *  the wave threads themselves too, not just abandon them running to completion in the background -
     *  the same "an interrupt must reach the actual blocking work" discipline the rest of the codebase
     *  applies everywhere else (R10/R12, see ReferenceAgent/RecordingProxy). Interrupting each wave
     *  thread lets its own runBounded() call unwind the same way any other cancelled session's does;
     *  still WAITS for them to actually stop before returning, not just signals and moves on. */
    /** Whether `f` falls under ownership glob `g`. A glob with no `*` at all is a plain directory
     *  prefix (the plan's own convention for "the whole directory" also works without a wildcard);
     *  anything containing a `*` is matched with java.nio.file.PathMatcher's own glob syntax -
     *  which already distinguishes `**` (any depth) from a bare `*` (one path segment only)
     *  correctly. The hand-rolled regex this replaced stripped only the LAST `*` for its first
     *  branch (dead for a `**`-suffixed glob, since the stripped prefix still contains a literal
     *  `*` that can never match via startsWith) and, for a single-star glob like
     *  "account-service/*.java" (intended: direct children only), degraded to a bare directory
     *  prefix via its second branch - silently under-detecting violations for files nested deeper
     *  than one level. */
    static boolean matchesOwnership(final String g, final String f) {
        if (!g.contains("*")) return Path.of(f).startsWith(g);
        return FileSystems.getDefault().getPathMatcher("glob:" + g).matches(Path.of(f));
    }

    static void joinAll(final List<Thread> threads) throws InterruptedException {
        try {
            for (Thread th : threads) th.join();
        } catch (InterruptedException ie) {
            for (Thread th : threads) th.interrupt();
            for (Thread th : threads) th.join();
            throw ie;
        }
    }

    static void copyTree(final Path from, final Path to) throws Exception {
        try (var w = Files.walk(from)) {
            w.forEach(p -> {
                try {
                    final Path dst = to.resolve(from.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(dst);
                    else { Files.createDirectories(dst.getParent()); Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING); }
                } catch (Exception e) {
                    // a permission/IO error on one file yields a silently incomplete copy of the
                    // agent's workspace tree - worth knowing about even though the run continues
                    log.warn("could not copy {} while snapshotting {} -> {}: {}", p, from, to, e.toString());
                }
            });
        }
    }

    static Map<String, Object> sessionRecord(final ReferenceAgent.SessionResult r, final String id) {
        final Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", id);
        rec.put("rc", r.rc());
        rec.put("seconds", r.seconds());
        rec.put("start_iso", r.start().toString());
        rec.put("end_iso", r.end().toString());
        rec.put("turns", r.turns());
        rec.put("tool_errors", r.toolErrors());
        rec.put("compactions", r.compactions());
        rec.put("session_id", r.sessionFile() == null ? null : r.sessionFile().getFileName().toString());
        rec.put("survivors", List.of());
        return rec;
    }
}
