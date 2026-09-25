package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.docker.DockerService;
import com.strgmai.ace.service.metrics.Trajectory;
import com.strgmai.ace.service.oracle.RunOracle;
import com.strgmai.ace.service.pack.Packs;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final RecordingProxyFactory proxies;
    private final RunOracle oracle;
    private final Reviews reviews;
    private final ContextProbe probe;
    private final ObjectMapper json = new ObjectMapper();

    public RunBench(BenchProperties props, ReferenceAgent agent, RecordingProxyFactory proxies, RunOracle oracle, Reviews reviews, ContextProbe probe) {
        this.props = props; this.agent = agent; this.proxies = proxies; this.oracle = oracle; this.reviews = reviews; this.probe = probe;
    }

    public static final List<String> PHASES = List.of("p0_definition", "p1_plan", "p2_implementation");
    static final Map<String, List<String>> RUNG_PHASES = Map.of("L7_full_platform", List.of("p0_definition", "p1_plan", "p2_implementation"),
            "L3p_point_in_time", List.of("p1_plan", "p2_implementation"));

    static List<String> phasesFor(String task) { return RUNG_PHASES.getOrDefault(task, List.of("implement")); }

    static final String IMPLEMENT_TEXT = "Read task/PROMPT.md and do exactly what it asks. Implement it, write tests proving the acceptance "
            + "criteria, run them until green, and record what you did and how you verified it in docs/PROGRESS.md.";
    static final Map<String, String[]> PHASE_TEXT = Map.of(
            "p0_definition", new String[]{"docs/TASK_DEFINITION.md", "Read task/PROMPT.md. Do PHASE 0 ONLY: write docs/TASK_DEFINITION.md. Write no code."},
            "p1_plan", new String[]{"docs/IMPLEMENTATION_PLAN.md", "Read task/PROMPT.md and docs/TASK_DEFINITION.md. Do PHASE 1 ONLY: write docs/IMPLEMENTATION_PLAN.md as ordered subtasks with ids (T1, T2, ...), each with a goal, dependencies, and a verifiable acceptance criterion. Write no code."},
            "p2_implementation", new String[]{"docs/PROGRESS.md", "Read task/PROMPT.md and docs/IMPLEMENTATION_PLAN.md. Do PHASE 2: implement subtasks one at a time in dependency order; for each, implement it, write tests proving its acceptance criterion, run them until green, then append the result to docs/PROGRESS.md. Continue until every subtask is done."},
            "implement", new String[]{"docs/PROGRESS.md", IMPLEMENT_TEXT});

    static String nowIso() { return Instant.now().toString(); }

    /** `T3 | done | ...` / `T3 | blocked | ...` status lines the task instruction asks for */
    static String progressStatus(Path ws, final String tid) {
        final Path p = ws.resolve("docs/PROGRESS.md");
        if (!Files.isRegularFile(p)) return null;
        try {
            String st = null;
            final Pattern re = Pattern.compile("^\\s*(?:[-*]\\s*)?\\*{0,3}\\s*\\|?\\s*\\*{0,3}" + Pattern.quote(tid) + "\\*{0,3}\\s*\\|\\s*\\*{0,3}(\\w+)", Pattern.CASE_INSENSITIVE);
            for (String line : Files.readAllLines(p)) {
                final java.util.regex.Matcher m = re.matcher(line);
                if (m.find() && !line.contains("budget exhausted before a status was written")) st = m.group(1).toLowerCase();
            }
            return st;
        } catch (Exception e) { return null; }
    }

    /** port of the Docker window monitor (R7): during an implementation-kind session Docker may
     *  come up (the shim on the first `docker` call); the harness records the window and stops
     *  Docker after idle_sec without a call and at the session's end. */
    class DockerWindowMonitor {
        final List<Map<String, Object>> windows = new ArrayList<>();
        Map<String, Object> cur;
        int callsBefore;
        void poll(int idleSec) {
            final boolean up = DockerService.dockerRunning();
            final int[] c = DockerService.dockerCalls(Path.of(System.getenv().getOrDefault("ACE_DOCKER_LOG", "/dev/null")), new int[3]);
            if (up && cur == null)
                cur = new LinkedHashMap<>(Map.of("start_iso", nowIso(), "started_by", "agent-direct", "calls_before", callsBefore, "start_epoch", System.currentTimeMillis() / 1000.0));
            if (cur != null) {
                final double idle = System.currentTimeMillis() / 1000.0 - Math.max(c[1] == 0 ? ((Number) cur.get("start_epoch")).doubleValue() : c[1], ((Number) cur.get("start_epoch")).doubleValue());
                if (!up || idle > idleSec) {
                    if (up) DockerService.dockerDown(30);
                    final Map<String, Object> w = new LinkedHashMap<>(cur);
                    w.put("end_iso", nowIso());
                    w.put("seconds", Math.round((System.currentTimeMillis() / 1000.0 - ((Number) cur.get("start_epoch")).doubleValue()) * 10) / 10.0);
                    w.put("calls", c[0] - callsBefore);
                    windows.add(w);
                    cur = null;
                }
            }
        }
        List<Map<String, Object>> finish() { if (cur != null) poll(0); return windows; }
    }

    /** the task's own prompt (tasks/&lt;task&gt;/PROMPT.md, vendored as a resource) when it exists,
     *  else the generic default (tasks/PROMPT.md, the port of the Python original's top-level
     *  task/PROMPT.md) - port of run_bench.py's own tp-then-fallback lookup. */
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

    @SuppressWarnings("unchecked")
    public Map<String, Object> runOnce(final Map<String, Object> cfg, final String runId, String task, final String mode, final String planSource) throws Exception {
        final var resultsDir = Path.of((String) cfg.getOrDefault("results_root", props.resultsDir()));
        final Path rd = resultsDir.resolve(runId);
        Files.createDirectories(rd);
        final var wsRoot = Path.of((String) cfg.getOrDefault("workspace_root", props.workspaceRoot())).resolve(runId);
        final Path ws = wsRoot.resolve("workspace"), home = wsRoot.resolve("home");
        Files.createDirectories(home.resolve(".pi/agent"));
        final String promptText = setUpTaskPrompt(ws, task);
        cfg.put("java_home", props.javaHome() == null || props.javaHome().isBlank()
                ? DockerService.sh(20, "/usr/libexec/java_home", "-v", String.valueOf(cfg.getOrDefault("java_major", 21))).out().strip() : props.javaHome());

        // ---- step 0: probe the window this setup offers, derive every knob (ports apply_context) ----
        Map<String, Object> probeRec = null, derived = Map.of();
        Map<String, Object> probeSansCurve;
        if (Boolean.TRUE.equals(cfg.get("context_probe"))) {
            Map<String, Object> ensured = probe.ensure(props, Boolean.TRUE.equals(cfg.get("context_probe_fresh")),
                    cfg.get("task_wall_sec") instanceof Number n ? n.intValue() : null, null);
            probeRec = (Map<String, Object>) ensured.get("probe");
            derived = (Map<String, Object>) ensured.get("derived");
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
            // the job's own pinned window (queue runs, per arm) wins over the operator's global one
            int window = cfg.get("context_window") instanceof Number n ? n.intValue()
                    : props.contextWindow() == null ? 65536 : props.contextWindow();
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
        final int firstTokenTimeoutSec = cfg.get("first_token_timeout_sec") instanceof Number n3 ? n3.intValue() : 180;
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
                firstTokenTimeoutSec));
        final int taskWall = cfg.get("task_wall_sec") instanceof Number n ? n.intValue() : 3600;
        long taskTokens = cfg.get("task_tokens") instanceof Number n2 ? n2.longValue()
                : ((Number) derived.getOrDefault("task_tokens", 60000)).longValue();
        if (!(cfg.get("compaction_trigger") instanceof Number)) cfg.put("compaction_trigger", props.compactionTrigger());

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
        manifest.put("contention_events", new ArrayList<Map<String, Object>>());
        manifest.put("provenance", Map.of("model", cfg.getOrDefault("model", props.model()), "harness", "ref",
                "harness_version", BenchProperties.HARNESS_VERSION, "java_home", cfg.get("java_home"),
                "runner", System.getProperty("ace.build", "ace-service")));
        // was always read for comparability (StatsService.KEY_FIELDS) but never actually written -
        // every run compared equal (both null) on this field, giving it no real protective value.
        // A run-level override replaces ReferenceAgent's own per-phase-kind DEFAULT_REASONING
        // table entirely (design rule 5: "record what was sent, not what was configured");
        // unset stays "default"
        manifest.put("reasoning_policy", cfg.get("reasoning_effort") instanceof String re && !re.isBlank() ? re : "default");
        ((Map<String, String>) manifest.get("snapshots")).put("start", RunBenchSupport.snapshot(ws, "phase/start"));
        Path journal = rd.resolve("interactions.jsonl");

        final List<Map<String, Object>> contentions = (List<Map<String, Object>>) manifest.get("contention_events");
        if (Boolean.TRUE.equals(cfg.get("manage_docker")) && DockerService.dockerRunning()) contentions.add(Map.of("docker_up", true, "ts", nowIso()));
        DockerService.dockerTools(home.toString());   // the shim + CLI plugins (R7), before the HOME is copied for parallel tasks
        cfg.put("_agent_env", RunBenchSupport.scrubbedEnv(home.toString(), runId, (String) cfg.get("java_home")));   // isolation reaches the agent

        if ("orchestrated".equals(mode)) orchestratedPhase(cfg, runId, rd, ws, home, journal, manifest, planSource, promptText, taskWall, taskTokens, task);
        else monolithicPhases(cfg, runId, rd, ws, journal, manifest, promptText, task);
        writeManifest(rd, manifest);
        // R17: bundle the agent's actual solution NOW - this is the ONLY bundle. Nothing after this
        // point ever adds anything worth re-bundling for: oracle scoring never commits at all, and
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
        final Map<String, Object> oracleReport = oracleWithDocker(cfg, ws, task, rd, manifest);
        Files.writeString(rd.resolve("oracle.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(oracleReport));
        // ---- metrics.json: the leaderboard block + per-step scores (collect.py) ----
        Files.writeString(rd.resolve("metrics.json"), json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(com.strgmai.ace.service.metrics.Collect.collect(rd, manifest)));
        // ---- trajectory metrics from the journal ----
        final Map<String, Object> summary = reviews.renderTrajectory(rd, manifest);
        manifest.put("decode_tps_median", summary.get("decode_tps_median"));
        manifest.put("decode_tps_median_short", summary.get("decode_tps_median_short"));
        manifest.put("contention", Map.of("docker_up", DockerService.dockerRunning(), "docker_windows", manifest.get("docker_windows")));
        writeManifest(rd, manifest);
        captureOmlxLog(rd, manifest);
        // never delete on an unconfirmed bundle (see bundleWorkspace above) - losing disk space on a
        // scratch dir is recoverable, losing the run's own output is not
        if (Files.isRegularFile(rd.resolve("workspace.bundle")) && !Boolean.TRUE.equals(cfg.get("keep_workspace"))) { deleteRecursive(ws); deleteRecursive(home); }
        return manifest;
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

    private Map<String, Object> oracleWithDocker(final Map<String, Object> cfg, final Path ws, String task, final Path rd, final Map<String, Object> manifest) throws Exception {
        final boolean manageDocker = Boolean.TRUE.equals(cfg.get("manage_docker"));
        if (manageDocker) DockerService.dockerUp(360);
        try {
            return oracle.score(ws, task, (String) cfg.get("system_base_url"), manifest);
        } finally {
            if (manageDocker) DockerService.dockerDown(30);
        }
    }

    // ---------------- monolithic mode: one growing session per phase; the rung picks the phases
    private void monolithicPhases(final Map<String, Object> cfg, final String runId, final Path rd, final Path ws, final Path journal, final Map<String, Object> manifest, final String promptText, String task) throws Exception {
        final String rules = Boolean.TRUE.equals(cfg.get("system_rules")) ? Packs.hygiene() : null;
        List<String> phases = phasesFor(task);   // non-L7 rungs run the single `implement` phase with the rung's budget
        final String impl = phases.contains("p2_implementation") ? "p2_implementation" : "implement";
        final Map<String, Integer> rung = rungBudgets(task);
        for (String pid : phases) {
            int wall = "p1_plan".equals(pid) ? rung.getOrDefault("plan_sec", 900) : rung.getOrDefault("sec", 14400);
            if ("implement".equals(pid) || "p2_implementation".equals(pid)) wall = rung.get("impl_sec");
            long tokens = "p2_implementation".equals(pid) ? props.phaseTokens("p2_implementation")
                    : "implement".equals(pid) ? props.phaseTokens("implement") : props.phaseTokens("p1_plan");
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

    /** ladder.json budgets: L7-style from config; rung budget split plan/implementation for the rest */
    private Map<String, Integer> rungBudgets(String task) {
        try {
            final var rung = json.readTree(getClass().getResourceAsStream("/tasks/ladder.json")).path(task);
            final int sec = rung.path("budget_sec").asInt(14400);
            int plan = 900;   // orchestration.plan_phase_sec
            final Map<String, Integer> out = new LinkedHashMap<>();
            out.put("sec", sec);
            out.put("plan_sec", plan);
            out.put("impl_sec", sec - plan);
            return out;
        } catch (Exception e) {
            return Map.of("sec", 14400, "plan_sec", 900, "impl_sec", 13500);
        }
    }

    private Map<String, Object> sessionWithPolicy(Map<String, Object> cfg, String runId, String name, String instruction,
                                                  long wall, long tokens, Path rd, Path ws, Path journal,
                                                  RecordingProxyFactory.ProxySession proxy, String appendSystem, String sessionId,
                                                  Map<String, String> envOverride) throws Exception {
        return sessionWithPolicy(cfg, runId, name, instruction, wall, tokens, rd, ws, journal, proxy, appendSystem, sessionId, envOverride, false);
    }

    /** a session + the stop protocol: budget clock, one `continue` on a length finish with the
     *  remaining budget, a wrap-up continuation when no status was written, the harness's fallback
     *  line, the budget-refusal verdict, the docker window. plainPlan=true for definition/plan
     *  phases: no clock, no docker note, no wrap-up, no verification (R4 C-3). */
    private Map<String, Object> sessionWithPolicy(Map<String, Object> cfg, String runId, String name, String instruction,
                                                  long wall, long tokens, Path rd, Path ws, Path journal,
                                                  RecordingProxyFactory.ProxySession proxy, String appendSystem, String sessionId,
                                                  Map<String, String> envOverride, boolean plainPlan) throws Exception {
        final var dw = new DockerWindowMonitor();
        final Map<String, String> env = envOverride != null ? envOverride : (Map<String, String>) cfg.get("_agent_env");
        // the run's OWN requested model (--model=): a single worker JVM handles every queued job in
        // turn, so the operator-wide default (props.model()) is not per-run - passing null here would
        // silently run every task against whatever model happens to be configured process-wide instead
        // of the model this run actually asked for (a real, previously silent mismatch)
        final String runModel = (String) cfg.get("model");
        final long firstTokenTimeoutMs = cfg.get("first_token_timeout_ms") instanceof Number ftt ? ftt.longValue() : 180_000L;
        final int compactionTrigger = cfg.get("compaction_trigger") instanceof Number ct ? ct.intValue() : props.compactionTrigger();
        final long t0 = System.currentTimeMillis();
        String full = plainPlan ? instruction
                : instruction + "\n\n" + Packs.budgetSection(name, LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                        LocalDateTime.now().plusSeconds(wall).format(DateTimeFormatter.ofPattern("HH:mm")), (int) (wall / 60))
                + "\n\n" + Packs.DOCKER_NOTE;
        final String canonicalSessionId = UUID.nameUUIDFromBytes(("agentbench/" + runId + "/" + name).getBytes()).toString();
        final String sid = sessionId == null ? canonicalSessionId : sessionId;
        final Thread monitor = plainPlan ? null : monitorThread(dw, 600);
        ReferenceAgent.SessionResult rec;
        try {
            rec = RunBenchSupport.runBounded(wall, name, rd.resolve("sessions"), sid, proxy.abort(), () -> agent.run(name, full, wall, tokens,
                    rd.resolve("sessions"), sid, false, appendSystem, ws.toString(), proxy.base(), proxy.abort(),
                    firstTokenTimeoutMs, compactionTrigger, runModel, env));
        } finally { if (monitor != null) monitor.interrupt(); }
        if (!plainPlan) appendWindows(manifestOf(cfg), dw, name);
        // P-1: a session that died on a `length` finish gets one continuation with what is LEFT (R4 C-7)
        if (!plainPlan && "length".equals(rec.finish()) && rec.rc() != 124 && wall - (System.currentTimeMillis() - t0) / 1000 > 120) {
            final long spent = ((Number) JournalFacts.facts(journal.toString(), rec.start().toString(), nowIso(), null, null, null).getOrDefault("completion_tokens", 0L)).longValue();
            long remaining = Math.max(1000, tokens - spent);   // what is LEFT, not a fresh budget (R4 C-7); a fresh proxy enforces it
            final var contProxy = proxies.start(journal, remaining, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
            try {
                final long contWall = wall - (System.currentTimeMillis() - t0) / 1000;
                rec = RunBenchSupport.runBounded(contWall, name + "-continue", rd.resolve("sessions"), sid, contProxy.abort(), () -> agent.run(name + "-continue",
                        "Your previous turn was cut off at the output limit. Continue the task from where you stopped; be concise and act with tools.",
                        contWall, remaining, rd.resolve("sessions"), sid, true, appendSystem, ws.toString(), contProxy.base(), contProxy.abort(),
                        firstTokenTimeoutMs, compactionTrigger, runModel, env));
            } finally { contProxy.stop(); }
        }
        final Map<String, Object> out = RunBench.sessionRecord(rec, name);
        String reported = plainPlan
                ? (Files.isRegularFile(ws.resolve(name.startsWith("p0") ? "docs/TASK_DEFINITION.md" : "docs/IMPLEMENTATION_PLAN.md")) ? "done" : null)
                : progressStatus(ws, name);
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
            // the wrap-up is a continuation on top of the task budget: its own proxy with its own 2000 tokens (run_bench run_task)
            final var wrapProxy = proxies.start(journal, 2000L, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
            final int wrapupBase = cfg.get("wrapup_wall_sec") instanceof Number wb ? wb.intValue() : 300;
            final long wrapWall = (long) (wrapupBase * scale);
            final ReferenceAgent.SessionResult w;   // assigned exactly once below; a legal blank final
            try {
                w = RunBenchSupport.runBounded(wrapWall, name + "-wrapup", rd.resolve("sessions"), canonicalSessionId, wrapProxy.abort(),
                        () -> agent.run(name + "-wrapup", wrapInstr, wrapWall, 2000L, rd.resolve("sessions"), canonicalSessionId,
                                true, appendSystem, ws.toString(), wrapProxy.base(), wrapProxy.abort(), firstTokenTimeoutMs, compactionTrigger, runModel, env));
            } finally { wrapProxy.stop(); }
            out.put("wrapup_rc", w.rc());
            out.put("wrapup_seconds", w.seconds());
            out.put("wrapup_scale", Math.round(scale * 100) / 100.0);
            reported = progressStatus(ws, name);
        }
        if (!plainPlan && reported == null) {   // the harness owns the status trail
            final Path pp = ws.resolve("docs/PROGRESS.md");
            Files.createDirectories(pp.getParent());
            final String existing = Files.isRegularFile(pp) ? Files.readString(pp) : "";
            Files.writeString(pp, (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n") + Packs.fallbackStatus(name, "") + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            reported = "unfinished";
            out.put("status_by_harness", true);
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

    private Thread monitorThread(final DockerWindowMonitor dw, final int idleSec) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                dw.poll(idleSec);
                try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }
            }
        }, "docker-window-monitor");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void appendWindows(final Map<String, Object> manifest, final DockerWindowMonitor dw, final String session) {
        for (Map<String, Object> w : dw.finish()) {
            w.put("session", session);
            ((List<Map<String, Object>>) manifest.get("docker_windows")).add(w);
        }
    }

    // ---------------- orchestrated mode: stable pack, task packs, parallel waves, merge, FIX, integration
    private void orchestratedPhase(Map<String, Object> cfg, String runId, Path rd, Path ws, Path home, Path journal,
                                   Map<String, Object> manifest, String planSource, String promptText, int taskWall, long taskTokens,
                                   String task) throws Exception {
        cfg.put("_manifest", manifest);
        final List<String> rungPhases = phasesFor(task);
        if (rungPhases.contains("p0_definition")) {   // L7-style rungs define before they plan (Python run_once loops the rung's phases)
            Map<String, Object> rec;
            // R18: resume past this phase if a prior attempt of THIS SAME run already finished it
            if (RunBenchSupport.tagExists(ws, "phase/p0") && Files.isRegularFile(ws.resolve(PHASE_TEXT.get("p0_definition")[0]))) {
                log.info("run {}: phase/p0 already completed in a prior attempt, resuming past it", runId);
                rec = new LinkedHashMap<>(Map.of("id", "p0_definition", "rc", 0, "resumed", true));
                ((Map<String, String>) manifest.get("snapshots")).put("p0", RunBenchSupport.gitOut(ws, "rev-parse", "phase/p0"));
            } else {
                final var proxy = proxies.start(journal, (long) props.phaseTokens("p0_definition"), null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
                try {
                    rec = sessionWithPolicy(cfg, runId, "p0_definition", PHASE_TEXT.get("p0_definition")[1],
                            props.phaseWall("p0_definition"), (long) props.phaseTokens("p0_definition"), rd, ws, journal, proxy, null, null, null, true);
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
                final var proxy = proxies.start(journal, (long) props.phaseTokens("p1_plan"), null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
                try {
                    p1rec = sessionWithPolicy(cfg, runId, "p1_plan", PHASE_TEXT.get("p1_plan")[1],
                            props.phaseWall("p1_plan"), (long) props.phaseTokens("p1_plan"), rd, ws, journal, proxy, null, null, null, true);
                } finally { proxy.stop(); }
                planSha = RunBenchSupport.snapshot(ws, "phase/p1");
            }
            p1rec.put("artifact", PHASE_TEXT.get("p1_plan")[0]);
            p1rec.put("artifact_present", Files.isRegularFile(ws.resolve("docs/IMPLEMENTATION_PLAN.md")));
            ((List<Map<String, Object>>) manifest.get("phases")).add(p1rec);
        }
        if ("reference".equals(planSource)) {   // the reference plan is a recorded phase too (rc=0, 0 s)
            Map<String, Object> p1rec = new LinkedHashMap<>(Map.of("id", "p1_plan", "rc", 0, "seconds", 0.0,
                    "start_iso", nowIso(), "end_iso", nowIso(), "artifact", PHASE_TEXT.get("p1_plan")[0],
                    "artifact_present", Files.isRegularFile(ws.resolve("docs/IMPLEMENTATION_PLAN.md"))));
            p1rec.put("reference_plan", planSource);
            ((List<Map<String, Object>>) manifest.get("phases")).add(p1rec);
        }
        final List<PlanTask> tasks = PlanParser.parseFile(ws.resolve("docs/IMPLEMENTATION_PLAN.md"));
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
        final List<String[]> handoffs = Boolean.TRUE.equals(cfg.get("handoff_notes")) ? new ArrayList<>() : null;
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
                        final String tp = Packs.taskPack(ws, t, tasks, snapshots, doneSet(snapshots), prev, prevSession, handoffs, prevVerified, mergeConflicts.isEmpty() ? null : mergeConflicts, null);
                        Files.writeString(rd.resolve("packs/" + t.id + ".md"), tp);
                        final var proxy = proxies.start(journal, taskTokens, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
                        try {
                            rec = sessionWithPolicy(cfg, runId, t.id, Packs.taskInstruction(t.id) + "\n\n" + tp,
                                    taskWall, taskTokens, rd, ws, journal, proxy, rd.resolve("packs/stable.md").toString(), null, null);
                        } finally { proxy.stop(); }
                        final String after = RunBenchSupport.snapshot(ws, "phase/" + t.id);
                        ((Map<String, String>) manifest.get("snapshots")).put(t.id, after);
                        snapshots.put(t.id, new String[]{String.valueOf(manifestSnap(manifest, "p1", planSha)), after});
                        mergeConflicts.clear();
                    }
                    rec.put("title", t.title);
                    ((List<Map<String, Object>>) manifest.get("tasks")).add(rec);
                    if (handoffs != null && ((int) rec.get("rc")) != 124) handoffStep(cfg, runId, t, rd, ws, journal, manifest, rec);
                    prev = t.id;
                    prevSession = agentSessionFile(rd, runId, t.id);
                    prevVerified = VerifyTask.verifyText(VerifyTask.verify(ws, cfg, rd.resolve("verify/" + t.id + ".log")));
                }
                continue;
            }
            // ---- parallel wave: every task in its own worktree + HOME copy + tagged journal; then merge into the main workspace ----
            wave = runParallelWave(cfg, runId, rd, ws, home, journal, manifest, tasks, snapshots, w, parallel, ownership,
                    promptText, stable, taskWall, taskTokens, handoffs, prevVerified, mergeConflicts, planSha);
            prev = null; prevSession = null;
            prevVerified = "after the merged wave " + w.stream().map(t -> t.id).toList() + ": " + wave.get("verification_text");
            mergeConflicts = (List<String[]>) wave.get("merge_conflicts");
        }
        // ---- integration: the fixed final task ----
        Map<String, Object> rec;
        final String after;
        if (RunBenchSupport.tagExists(ws, "phase/INTEGRATION")) {   // R18: see the sequential task loop above
            log.info("run {}: phase/INTEGRATION already completed in a prior attempt, resuming past it", runId);
            rec = new LinkedHashMap<>(Map.of("id", "INTEGRATION", "rc", 0, "resumed", true));
            after = RunBenchSupport.gitOut(ws, "rev-parse", "phase/INTEGRATION");
        } else {
            final String tp = Packs.taskPack(ws, new PlanTask("INTEGRATION", "integrate and verify"), tasks, snapshots, doneSet(snapshots), prev, prevSession, handoffs, prevVerified, mergeConflicts.isEmpty() ? null : mergeConflicts, null);
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
        for (Path p : StructureChecksGlob(rd.resolve("sessions")))
            if (p.getFileName().toString().contains(UUID.nameUUIDFromBytes(("agentbench/" + runId + "/" + id).getBytes()).toString())) return p;
        return null;
    }
    static List<Path> StructureChecksGlob(final Path dir) {
        try (var s = Files.list(dir)) { return s.filter(Files::isRegularFile).toList(); } catch (Exception e) { return List.of(); }
    }

    private void handoffStep(final Map<String, Object> cfg, final String runId, final PlanTask t, final Path rd, final Path ws, final Path journal, final Map<String, Object> manifest, Map<String, Object> rec) throws Exception {
        final var proxy = proxies.start(journal, 3000L, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
        final long firstTokenTimeoutMs = cfg.get("first_token_timeout_ms") instanceof Number n ? n.longValue() : 180_000L;
        final int compactionTrigger = cfg.get("compaction_trigger") instanceof Number ct ? ct.intValue() : props.compactionTrigger();
        try {
            final String handoffSid = UUID.nameUUIDFromBytes(("agentbench/" + runId + "/" + t.id).getBytes()).toString();
            final int handoffWall = cfg.get("handoff_wall_sec") instanceof Number hw ? hw.intValue() : 300;
            ReferenceAgent.SessionResult h = RunBenchSupport.runBounded(handoffWall, t.id + "-handoff", rd.resolve("sessions"), handoffSid, proxy.abort(),
                    () -> agent.run(t.id + "-handoff", Packs.handoffInstruction(t.id), handoffWall, 3000L,
                            rd.resolve("sessions"), handoffSid, true, rd.resolve("packs/stable.md").toString(), ws.toString(),
                            proxy.base(), proxy.abort(), firstTokenTimeoutMs, compactionTrigger, (String) cfg.get("model"), null));
            final Path hp = ws.resolve("handoff").resolve(t.id + ".md");
            if (Files.isRegularFile(hp)) {
                final String txt = Files.readString(hp);
                manifestHandoffs(manifest).add(new String[]{t.id, txt});
                String sha;
                try { sha = RunBenchSupport.gitOut(ws, "hash-object", "-w", hp.toString()); }
                catch (Exception e) { log.warn("git hash-object failed for handoff {}: {}", t.id, e.toString()); sha = null; }
                rec.put("handoff_sha", sha == null || sha.isEmpty() ? null : sha);   // one hash-object, not the old double call that wrote the object twice
                rec.put("handoff_chars", txt.length());
            }
            rec.put("handoff_seconds", h.seconds());
            rec.put("handoff_rc", h.rc());
        } finally { proxy.stop(); }
    }

    static List<String[]> manifestHandoffs(final Map<String, Object> manifest) {
        if (!(manifest.get("handoffs") instanceof List<?>)) manifest.put("handoffs", new ArrayList<String[]>());
        return (List<String[]>) manifest.get("handoffs");
    }

    private Object derived(Map<String, Object> cfg) { return null; }

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
            final var proxy = proxies.start(journal, 8000L, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
            final long firstTokenTimeoutMs = cfg.get("first_token_timeout_ms") instanceof Number n ? n.longValue() : 180_000L;
            final int compactionTrigger = cfg.get("compaction_trigger") instanceof Number ct ? ct.intValue() : props.compactionTrigger();
            final String planSid = UUID.nameUUIDFromBytes(("agentbench/" + runId + "/PARALLEL_PLAN").getBytes()).toString();
            final int parallelPlanWall = cfg.get("parallel_plan_wall_sec") instanceof Number pw ? pw.intValue() : 600;
            ReferenceAgent.SessionResult prec;
            try {
                prec = RunBenchSupport.runBounded(parallelPlanWall, "PARALLEL_PLAN", rd.resolve("sessions"), planSid, proxy.abort(),
                        () -> agent.run("PARALLEL_PLAN", Packs.parallelPlanPack(tasks) + "\n\n" + Packs.PARALLEL_PLAN_INSTRUCTION, parallelPlanWall, 8000L,
                                rd.resolve("sessions"), planSid, false, rd.resolve("packs/stable.md").toString(), ws.toString(),
                                proxy.base(), proxy.abort(), firstTokenTimeoutMs, compactionTrigger, (String) cfg.get("model"), null));
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
        final Map<String, Path> wts = new LinkedHashMap<>(), homes = new LinkedHashMap<>();
        final Map<String, Path> taskJournals = new LinkedHashMap<>();
        final Map<String, String> packs = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> recs = new LinkedHashMap<>();
        // R18: a wave task whose phase tag already exists finished its agent session in a prior
        // attempt of THIS SAME run (the tag is only ever written after that session returns
        // normally - see the merge loop below). Its work sits on the still-extant "task/<id>"
        // branch (worktree removal never deletes the branch itself), so skip re-creating its
        // worktree/HOME copy and re-running its session entirely; a synthetic resumed rec keeps
        // it present in the manifest, and the merge loop below merges/re-tags it accordingly.
        final Set<String> resumedTaskIds = new LinkedHashSet<>();
        for (PlanTask t : waveTasks) {
            if (RunBenchSupport.tagExists(ws, "phase/" + t.id)) {
                log.info("run {}: phase/{} already completed in a prior attempt (parallel wave), resuming past it", runId, t.id);
                resumedTaskIds.add(t.id);
                recs.put(t.id, new LinkedHashMap<>(Map.of("id", t.id, "rc", 0, "resumed", true)));
                continue;
            }
            final Path wt = Path.of(ws + "-" + t.id), homeT = Path.of(home + "-home-" + t.id);
            DockerService.sh(60, "git", "-C", ws.toString(), "worktree", "add", "-q", "-B", "task/" + t.id, wt.toString(), pre);
            deleteRecursive(homeT);
            copyTree(home, homeT);
            DockerService.dockerTools(homeT.toString());   // each HOME copy gets the shim + plugins too
            taskJournals.put(t.id, rd.resolve("interactions-" + t.id + ".jsonl"));
            packs.put(t.id, Packs.taskPack(ws, t, tasks, snapshots, doneSet(snapshots), null, null, handoffs, prevVerified,
                    mergeConflicts.isEmpty() ? null : mergeConflicts, waveIds.stream().filter(x -> !x.equals(t.id)).toList()));
            Files.writeString(rd.resolve("packs/" + t.id + ".md"), packs.get(t.id));
        }
        final var sem = new java.util.concurrent.Semaphore(parallel);
        final Map<String, Throwable> errors = new LinkedHashMap<>();
        final List<Thread> threads = new ArrayList<>();
        for (PlanTask t : waveTasks) {
            if (resumedTaskIds.contains(t.id)) continue;
            Thread th = new Thread(() -> {
                try {
                    sem.acquire();
                    try {
                        final var proxy = proxies.start(taskJournals.get(t.id), taskTokens, t.id, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
                        Map<String, String> envT = RunBenchSupport.scrubbedEnv(Path.of(home + "-home-" + t.id).toString(), runId + "/" + t.id, (String) cfg.get("java_home"));   // the task's own HOME copy (R6)
                        Map<String, Object> rec;
                        try {
                            rec = sessionWithPolicy(cfg, runId, t.id, Packs.taskInstruction(t.id) + "\n\n" + packs.get(t.id),
                                    taskWall, taskTokens, rd, Path.of(ws + "-" + t.id), taskJournals.get(t.id), proxy,
                                    rd.resolve("packs/stable.md").toString(), null, envT);
                        } finally { proxy.stop(); }
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
        if (Boolean.TRUE.equals(cfg.get("manage_docker")) && DockerService.dockerRunning()) DockerService.dockerDown(30);
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
                    if (own.stream().noneMatch(g -> f.startsWith(g.replaceAll("\\*$", "")) || java.nio.file.Path.of(f).startsWith(g.replaceAll("\\*.*$", ""))))
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
                Files.writeString(rd.resolve("packs/" + fid + ".md"), Packs.fixPack(ws, problems));
                final var proxy = proxies.start(journal, 20000L, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
                try {
                    frec = sessionWithPolicy(cfg, runId, fid, Packs.FIX_INSTRUCTION.replace("{tasks}", String.join(", ", waveIds)).replace("{id}", fid),
                            900, 20000L, rd, ws, journal, proxy, rd.resolve("packs/stable.md").toString(), null, null);
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
