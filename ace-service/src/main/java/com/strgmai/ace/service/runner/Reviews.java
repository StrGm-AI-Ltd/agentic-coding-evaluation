package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.docker.DockerService;
import com.strgmai.ace.service.metrics.Trajectory;
import com.strgmai.ace.service.pack.Packs;
import com.strgmai.ace.service.plan.PlanTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Port of run_bench.py's self_review + trajectory_review: end-of-run reviews by a reviewer model
 *  (the run's own model by default, or any provider/model LangChain4j can reach). The reviewer's
 *  opinion is scored for CALIBRATION against the oracle — it never replaces it. The code is
 *  frozen: anything the reviewer changed outside review/ is reverted and recorded. The trajectory
 *  review first renders the journal into trajectory/TRAJECTORY.md + summary.json (the raw journal
 *  is megabytes), then runs a fresh reviewer session against the harness-computed objective index. */
@Component
public class Reviews {
    private static final Logger log = LoggerFactory.getLogger(Reviews.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BenchProperties props;
    private final ReferenceAgent agent;

    public Reviews(BenchProperties props, ReferenceAgent agent) { this.props = props; this.agent = agent; }

    /** files `git status` reports as changed, minus build artefacts and the given prefixes (a
     *  reviewer that runs the tests changes build/test-results, which is not code — R4 C-11) */
    static List<String> changedSources(final Path ws, final String... excludePrefixes) {
        final List<String> out = new ArrayList<>();
        final DockerService.Sh st = DockerService.sh(20, "git", "-C", ws.toString(), "status", "--porcelain");
        for (String l : st.out().split("\n")) {
            if (l.isBlank()) continue;
            final String path = l.length() > 3 ? l.substring(3).strip().replaceAll("^\"|\"$", "") : "";
            final boolean excluded = Arrays.stream(excludePrefixes).anyMatch(path::startsWith);
            // segment membership (like BuildChecks) instead of "/dir/": a file directly under a TOP-LEVEL
            // skip dir (build/Foo.java) has no leading slash and would be wrongly reported as code
            final boolean skipDir = java.util.Arrays.stream(path.split("/")).anyMatch(com.strgmai.ace.service.oracle.checks.BuildChecks.SKIP_DIRS::contains);
            if (excluded || skipDir) continue;
            if (List.of(".jar", ".class", ".war").stream().anyMatch(path::endsWith)) continue;
            out.add(path);
        }
        return out;
    }

    public record ReviewOutcome(Map<String, Object> manifestFields, double seconds, String model, boolean external, boolean blind) {}

    /** self-review: end-of-run code review session producing review/self_review.json; the harness
     *  records the score, categories and severity-ranked findings, restores any code the reviewer
     *  touched, and later scores the reviewer's CALIBRATION against the oracle. */
    public Map<String, Object> selfReview(Map<String, Object> cfg, Path ws, Path rd, Map<String, Object> manifest,
                                          List<PlanTask> tasks, String promptText, RecordingProxyFactory proxies,
                                          String reviewerModel, boolean blind) throws Exception {
        String reviewer = reviewerModel == null || reviewerModel.isBlank()
                ? "omlx/" + cfg.getOrDefault("model", props.model()) : reviewerModel;
        final boolean external = !reviewer.startsWith("omlx/");
        // only one local model fits: a second local reviewer falls back to the run's own model
        if (!external && !reviewer.equals("omlx/" + cfg.getOrDefault("model", props.model()))) reviewer = "omlx/" + cfg.getOrDefault("model", props.model());
        final var pre = Path.of(RunBenchSupport.snapshot(ws, "phase/pre-review"));
        manifestMap(manifest, "snapshots").put("pre-review", pre.toString());
        final Path verifyLog = rd.resolve("verify/pre-review.log");
        Files.createDirectories(verifyLog.getParent());
        final String verified = VerifyTask.verifyText(VerifyTask.verify(ws, cfg, verifyLog));
        final Path sysPath = rd.resolve("packs/review_system.md");
        Files.createDirectories(sysPath.getParent());
        Files.writeString(sysPath, Packs.reviewSystem(promptText));
        final Path packPath = rd.resolve("packs/REVIEW.md");
        Files.writeString(packPath, Packs.reviewPack(ws, tasks, verified, blind));
        manifestMap(manifest, "review_config").putAll(Map.of("model", reviewer, "external", external, "blind", blind));
        Files.createDirectories(ws.resolve("review"));
        final ReferenceAgent.SessionResult rec = runReviewerSession(cfg, "REVIEW", packPath, sysPath, reviewer, external, ws, rd, manifest, proxies, rd.resolve("review.log"));
        // the code is frozen: anything the reviewer changed outside review/ is reverted and recorded
        final List<String> changed = changedSources(ws, "review/");
        if (!changed.isEmpty()) {
            DockerService.sh(60, "git", "-C", ws.toString(), "checkout", pre.toString(), "--", ".");
            DockerService.sh(60, "git", "-C", ws.toString(), "clean", "-fdq", "-e", "review/", "-e", "build/", "-e", ".gradle/");
        }
        final Map<String, Object> parsed = Packs.parseSelfReview(ws.resolve("review/self_review.json"));
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("reviewer_model", reviewer);
        out.put("external", external);
        out.put("blind", blind);
        out.put("seconds", rec.seconds());
        out.put("rc", rec.rc());
        out.put("over_budget", rec.rc() == 124);
        out.put("usage", com.strgmai.ace.service.agent.AgentSession.usage(rec.sessionFile()));   // the only token source for an external reviewer
        if (!external) {   // the in-journal requests + budget refusals of the reviewer's window
            Map<String, Object> rf = com.strgmai.ace.service.runner.JournalFacts.facts(rd.resolve("interactions.jsonl").toString(),
                    rec.start().toString(), rec.end().toString(), null, null, null);
            out.put("requests", rf.get("requests"));
            out.put("token_budget_exhausted", ((Number) rf.getOrDefault("budget_refusals", 0)).intValue() > 0);
        }
        out.put("parse_ok", parsed != null);
        out.put("modified_code", !changed.isEmpty());
        out.put("modified_paths", changed.subList(0, Math.min(10, changed.size())));
        out.put("md_present", Files.isRegularFile(ws.resolve("review/SELF_REVIEW.md")));
        if (parsed != null) out.putAll(parsed);
        manifestCompute(manifest, "review_windows").add(List.of(rec.start().toString(), rec.end().toString()));
        for (String f : List.of("SELF_REVIEW.md", "self_review.json")) {
            final Path src = ws.resolve("review").resolve(f);
            if (Files.isRegularFile(src)) Files.copy(src, rd.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        }
        manifestMap(manifest, "snapshots").put("review", RunBenchSupport.snapshot(ws, "phase/review"));
        return out;
    }

    /** trajectory review: render the journal first (before the reviewer adds its own requests),
     *  then review the agent's process against the objective index. */
    public Map<String, Object> trajectoryReview(Map<String, Object> cfg, Path ws, Path rd, Map<String, Object> manifest,
                                                 String reviewerModel, RecordingProxyFactory proxies) throws Exception {
        String reviewer = reviewerModel == null || reviewerModel.isBlank()
                ? "omlx/" + cfg.getOrDefault("model", props.model()) : reviewerModel;
        final boolean external = !reviewer.startsWith("omlx/");
        if (!external && !reviewer.equals("omlx/" + cfg.getOrDefault("model", props.model()))) reviewer = "omlx/" + cfg.getOrDefault("model", props.model());
        final Path tdir = ws.resolve("trajectory");
        Files.createDirectories(tdir);
        // render the trajectory as of now (before the reviewer adds its own requests to the journal)
        Files.writeString(rd.resolve("manifest.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        final Map<String, Object> summary = renderTrajectory(rd, manifest);
        Files.writeString(tdir.resolve("summary.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        // renderTrajectory() writes TRAJECTORY.md into rd (the permanent results dir), never ws - but
        // the reviewer's own tools only ever see ws, so without this copy it can never find the
        // transcript the pack tells it is there (confirmed live: a reviewer session burned its whole
        // budget rediscovering this exact gap turn after turn instead of ever reviewing anything)
        final Path rdTraj = rd.resolve("trajectory/TRAJECTORY.md");
        if (Files.isRegularFile(rdTraj)) Files.copy(rdTraj, tdir.resolve("TRAJECTORY.md"), StandardCopyOption.REPLACE_EXISTING);
        final String head = Files.isRegularFile(tdir.resolve("TRAJECTORY.md")) ? Files.readString(tdir.resolve("TRAJECTORY.md")) : "";
        final Path packPath = rd.resolve("packs/TRAJECTORY_REVIEW.md");
        Files.writeString(packPath, Packs.trajectoryReviewPack(summary, head));
        final Path sysPath = rd.resolve("packs/trajectory_review_system.md");
        Files.writeString(sysPath, Packs.TRAJ_ROLE);
        Files.createDirectories(ws.resolve("review"));
        final var pre = Path.of(RunBenchSupport.snapshot(ws, "phase/pre-trajectory-review"));
        final ReferenceAgent.SessionResult rec = runReviewerSession(cfg, "TRAJECTORY_REVIEW", packPath, sysPath, reviewer, external, ws, rd, manifest, proxies, rd.resolve("review.log"));
        manifestCompute(manifest, "review_windows").add(List.of(rec.start().toString(), rec.end().toString()));
        final List<String> changed = changedSources(ws, "review/", "trajectory/");
        if (!changed.isEmpty()) {
            DockerService.sh(60, "git", "-C", ws.toString(), "checkout", pre.toString(), "--", ".");
            DockerService.sh(60, "git", "-C", ws.toString(), "clean", "-fdq", "-e", "review/", "-e", "trajectory/", "-e", "build/", "-e", ".gradle/");
        }
        final Map<String, Object> parsed = Packs.parseTrajectoryReview(ws.resolve("review/trajectory_review.json"));
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("reviewer_model", reviewer);
        out.put("external", external);
        out.put("seconds", rec.seconds());
        out.put("rc", rec.rc());
        out.put("over_budget", rec.rc() == 124);
        out.put("usage", com.strgmai.ace.service.agent.AgentSession.usage(rec.sessionFile()));
        if (!external) {
            Map<String, Object> rf = com.strgmai.ace.service.runner.JournalFacts.facts(rd.resolve("interactions.jsonl").toString(),
                    rec.start().toString(), rec.end().toString(), null, null, null);
            out.put("requests", rf.get("requests"));
            out.put("token_budget_exhausted", ((Number) rf.getOrDefault("budget_refusals", 0)).intValue() > 0);
        }
        out.put("parse_ok", parsed != null);
        out.put("modified_files", !changed.isEmpty());
        out.put("objective_index_pct", summary.get("harness_trajectory_pct"));
        out.put("objective_penalties", summary.get("harness_trajectory_penalties"));
        out.put("md_present", Files.isRegularFile(ws.resolve("review/TRAJECTORY_REVIEW.md")));
        if (parsed != null) out.putAll(parsed);
        for (String f : List.of("TRAJECTORY_REVIEW.md", "trajectory_review.json")) {
            final Path src = ws.resolve("review").resolve(f);
            if (Files.isRegularFile(src)) Files.copy(src, rd.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    /** render the journal into trajectory/TRAJECTORY.md + the summary (the raw journal is megabytes) */
    @SuppressWarnings("unchecked")
    public Map<String, Object> renderTrajectory(final Path rd, final Map<String, Object> manifest) throws Exception {
        final Path journal = rd.resolve("interactions.jsonl");
        final List<Map<String, Object>> recs = new ArrayList<>();
        if (Files.isRegularFile(journal))
            for (String line : Files.readAllLines(journal)) {
                if (line.isBlank()) continue;
                try { recs.add(JSON.readValue(line, Map.class)); }
                catch (Exception e) { log.debug("could not parse journal line for the trajectory render, dropping it: {}", e.toString()); }
            }
        final List<Trajectory.Turn> turns = Trajectory.turnsFromProxy(recs);
        final Map<String, Object> derived = manifest.get("derived") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        final List<Map<String, Object>> dockerWindows = manifest.get("docker_windows") instanceof List<?> l ? (List<Map<String, Object>>) l : null;
        // exclude review sessions from the agent's trajectory (they are the reviewer's, not the agent's) BEFORE any analysis
        List<Trajectory.Turn> agentTurns = turns;
        if (manifest.get("review_windows") instanceof List<?> rw && !rw.isEmpty()) {
            agentTurns = new ArrayList<>();
            for (Trajectory.Turn t : turns) {
                boolean inReview = false;
                if (t.ts() != null) {
                    final OffsetDateTimeHolder ts = parseTs(t.ts());
                    for (Object w0 : rw) {
                        final List<String> w = (List<String>) w0;
                        if (!ts.v.isBefore(parseTs(w.get(0)).v) && !ts.v.isAfter(parseTs(w.get(1)).v)) { inReview = true; break; }
                    }
                }
                if (!inReview) agentTurns.add(t);
            }
        }
        // one analysis over the correct (possibly filtered) turn list - analyzing all turns first was double work
        // over a megabyte-sized journal for the only case that mattered (review windows present)
        final Map<String, Object> s = Trajectory.analyze(agentTurns, derived, dockerWindows);
        final var idx = Trajectory.objectiveIndex(s, manifest);
        s.put("harness_trajectory_pct", idx.getKey());
        s.put("harness_trajectory_penalties", idx.getValue());
        final Path traj = rd.resolve("trajectory/TRAJECTORY.md");
        Files.createDirectories(traj.getParent());
        final Map<String, String[]> windows = new LinkedHashMap<>();
        if (manifest.get("tasks") instanceof List<?> tasks)
            for (Object t0 : tasks) {
                final Map<String, Object> t = (Map<String, Object>) t0;
                if (t.get("start_iso") != null && t.get("end_iso") != null) windows.put(String.valueOf(t.get("id")), new String[]{String.valueOf(t.get("start_iso")), String.valueOf(t.get("end_iso"))});
            }
        Files.writeString(traj, Trajectory.transcript(recs, turns, windows.isEmpty() ? null : windows));
        Files.writeString(rd.resolve("trajectory.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("schema_version", 3, "summary", s, "turns", turns)));
        return s;
    }

    record OffsetDateTimeHolder(java.time.OffsetDateTime v) {}
    static OffsetDateTimeHolder parseTs(String s) { return new OffsetDateTimeHolder(java.time.OffsetDateTime.parse(s.replace("Z", "+00:00"))); }

    /** one reviewer session; external reviewers' credentials pass through for this session only */
    ReferenceAgent.SessionResult runReviewerSession(Map<String, Object> cfg, String name, Path packPath, Path sysPath,
                                                     String reviewer, boolean external, Path ws, Path rd,
                                                     Map<String, Object> manifest, RecordingProxyFactory proxies, Path log) throws Exception {
        final Map<String, Object> reviewCfg = cfg.get("review") instanceof Map<?, ?> r ? (Map<String, Object>) r : Map.of();
        final long tokens = ((Number) reviewCfg.getOrDefault("tokens", 12000)).longValue();
        final String model = reviewer.startsWith("omlx/") ? reviewer.substring("omlx/".length()) : reviewer.substring(reviewer.indexOf('/') + 1);
        final RecordingProxyFactory.ProxySession proxy = external ? null : proxies.start(rd.resolve("interactions.jsonl"), tokens, null);
        final Map<String, String> extraEnv = external ? externalCredentials(reviewCfg) : null;
        final long firstTokenTimeoutMs = cfg.get("first_token_timeout_ms") instanceof Number n ? n.longValue() : 180_000L;
        final int compactionTrigger = cfg.get("compaction_trigger") instanceof Number ct ? ct.intValue() : props.compactionTrigger();
        try {
            ReferenceAgent.SessionResult res = agent.run(name, Files.readString(packPath) + "\n\n" + (name.equals("REVIEW") ? Packs.REVIEW_INSTRUCTION : Packs.TRAJ_INSTRUCTION),
                    ((Number) reviewCfg.getOrDefault("wall_sec", 900)).longValue(), tokens, rd.resolve("sessions"),
                    UUID.nameUUIDFromBytes(("agentbench/" + manifest.get("run_id") + "/" + name).getBytes()).toString(),
                    false, sysPath.toString(), ws.toString(),
                    proxy == null ? externalBase(reviewer, cfg) : proxy.base(),
                    proxy == null ? (() -> {}) : proxy.abort(), firstTokenTimeoutMs, compactionTrigger, model, extraEnv);
            // both self-review and trajectory-review write to the SAME log path - append, or the
            // second review's line silently replaces the first's (default writeString() truncates)
            if (log != null) Files.writeString(log, "review session " + name + " rc=" + res.rc() + " turns=" + res.turns() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return res;
        } finally {
            if (proxy != null) proxy.stop();
        }
    }

    Map<String, String> externalCredentials(Map<String, Object> reviewCfg) {
        final Map<String, String> extra = new LinkedHashMap<>();
        for (String k : (List<String>) reviewCfg.getOrDefault("credential_env", List.of("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "OPENROUTER_API_KEY")))
            if (System.getenv(k) != null) extra.put(k, System.getenv(k));
        return extra;
    }

    /** external providers bypass the proxy (their credentials and endpoints are their own) */
    String externalBase(String reviewer, Map<String, Object> cfg) {
        final String provider = reviewer.substring(0, reviewer.indexOf('/'));
        return switch (provider) {
            case "openai" -> "https://api.openai.com/v1";
            case "openrouter" -> "https://openrouter.ai/api/v1";
            case "anthropic" -> "https://api.anthropic.com/v1";
            case "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai";
            case "nebius" -> "https://api.tokenfactory.us-central1.nebius.com/v1";
            default -> String.valueOf(cfg.getOrDefault("endpoint", props.endpoint()));
        };
    }

    @SuppressWarnings("unchecked")
    static <K, V> Map<K, V> manifestMap(final Map<String, Object> manifest, final String key) {
        if (!(manifest.get(key) instanceof Map<?, ?>)) manifest.put(key, new LinkedHashMap<K, V>());
        return (Map<K, V>) manifest.get(key);
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> manifestCompute(final Map<String, Object> manifest, final String key) {
        if (!(manifest.get(key) instanceof List<?>)) manifest.put(key, new ArrayList<T>());
        return (List<T>) manifest.get(key);
    }
}
