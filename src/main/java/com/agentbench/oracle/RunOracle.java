package com.agentbench.oracle;

import com.agentbench.docker.DockerService;
import com.agentbench.oracle.checks.BlackboxScenarios;
import com.agentbench.oracle.checks.BuildChecks;
import com.agentbench.oracle.checks.ComposeChecks;
import com.agentbench.oracle.checks.MoneySafetyChecks;
import com.agentbench.oracle.checks.StructureChecks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Port of oracle/run_oracle.py: runs the checkers over a workspace with the FIXED-denominator
 *  invariants. Rung-aware (tasks/ladder.json restricts the check set). Docker-gated scripts run at
 *  full scope when the daemon is reachable: the build checks (B1/B2/B3) in the PINNED CONTAINER
 *  IMAGE with a real PostgreSQL sidecar and the seeded mutation, and the compose runtime checks
 *  (C1/C2 + the black-box F suite) against the agent's live stack from a source-only copy. When
 *  Docker is unavailable their ids are SKIPPED (excluded from the denominator, headline blocked) —
 *  infrastructure is never charged to the agent. A crashing checker fails every id it owns (never
 *  shrinks the denominator). F6 (OpenAPI conformance) is the one check not ported. */
@Component
public class RunOracle {
    private final ObjectMapper json = new ObjectMapper();
    public static final String BUILD_IMAGE = BuildChecks.DEFAULT_IMAGE;

    public Map<String, Object> score(Path ws, String task, String systemBaseUrl) throws Exception {
        return score(ws, task, systemBaseUrl, null);
    }

    /** full form: the runner's manifest carries the per-phase snapshot SHAs P3 diffs (05_phases.py) */
    @SuppressWarnings("unchecked")
    public Map<String, Object> score(Path ws, String task, String systemBaseUrl, Map<String, Object> manifest) throws Exception {
        JsonNode ladder = json.readTree(getClass().getResourceAsStream("/tasks/ladder.json"));
        JsonNode rung = ladder.has(task) ? ladder.get(task) : ladder.get("L7_full_platform");
        Set<CheckId> wanted = new LinkedHashSet<>();
        if (rung.get("checks").isTextual() && rung.get("checks").asText().equals("all"))
            wanted.addAll(EnumSet.allOf(CheckId.class));
        else for (JsonNode c : rung.get("checks")) wanted.add(CheckId.valueOf(c.asText()));

        Map<CheckId, CheckResult> got = new EnumMap<>(CheckId.class);
        List<CheckId> offline = List.of(CheckId.S1, CheckId.S2, CheckId.S3, CheckId.S4, CheckId.S5, CheckId.S6, CheckId.S7, CheckId.S8, CheckId.S9,
                CheckId.M1, CheckId.M2, CheckId.M3, CheckId.M4, CheckId.P1, CheckId.P2, CheckId.P3);
        for (CheckId id : offline) {
            if (!wanted.contains(id)) continue;
            try { runOffline(id, ws, manifest, got); }
            catch (Exception e) { got.put(id, CheckResult.fail(id, "checker crashed: " + e)); }
        }
        // ---- the docker-gated scripts, as one gate (the Python oracle gates per script) ----
        List<CheckId> gated = wanted.stream().filter(id -> !offline.contains(id) && id != CheckId.P1).toList();
        boolean dockerOk = DockerService.sh(15, "docker", "info", "--format", "{{.ServerVersion}}").rc() == 0;
        if (dockerOk) {
            try {   // 03_build.py: the pinned container, the sidecar, the mutation
                for (CheckResult r : BuildChecks.run(ws, BUILD_IMAGE))
                    if (wanted.contains(r.id())) got.put(r.id(), r);
            } catch (Exception e) {
                for (CheckId id : gated)
                    if (id == CheckId.B1 || id == CheckId.B2 || id == CheckId.B3)
                        got.put(id, CheckResult.fail(id, "build checker crashed: " + e));
            }
            if (wanted.stream().anyMatch(id -> id == CheckId.C1 || id == CheckId.C2 || id.name().startsWith("F"))) {
                try {   // 04_compose.py: build -> up -> C1/C2 -> F* (F6 grades the observed responses) -> down
                    String base = systemBaseUrl == null || systemBaseUrl.isBlank() ? "http://localhost:8080" : systemBaseUrl;
                    for (CheckResult r : ComposeChecks.run(ws, 2400, 600, wanted::contains, base, manifest == null ? ws : Path.of(String.valueOf(manifest.getOrDefault("workspace", ws)))))
                        if (wanted.contains(r.id())) got.put(r.id(), r);
                } catch (Exception e) {
                    for (CheckId id : gated)
                        if (id == CheckId.C1 || id == CheckId.C2 || id.name().startsWith("F"))
                            if (!got.containsKey(id)) got.put(id, CheckResult.fail(id, "compose checker crashed: " + e));
                }
            }
        }
        for (CheckId id : gated)
            if (!got.containsKey(id)) got.put(id, CheckResult.skipped(id, "docker unavailable (build/compose checks skipped; never charged to the agent)"));
        // missing => FAIL: a checker that emitted nothing can never raise the score by omission
        List<CheckResult> records = new ArrayList<>();
        for (CheckId id : wanted)
            records.add(got.getOrDefault(id, CheckResult.fail(id, "checker missing: no script produced this id")));

        Map<String, Object> rep = Scorer.score(records);
        rep.put("task", task);
        rep.put("checks_subset", wanted.stream().map(Enum::name).toList());
        rep.put("workspace", ws.toString());
        rep.put("system", systemBaseUrl);
        rep.put("docker_available", dockerOk);
        rep.put("scored_at", Instant.now().toString());
        DockerService.Sh digest = DockerService.sh(20, "docker", "image", "inspect", "--format", "{{index .RepoDigests 0}}", BUILD_IMAGE);
        rep.put("provenance", Map.of("build_image", BUILD_IMAGE, "build_image_digest", digest.rc() == 0 ? digest.out().strip() : null,
                "checks", wanted.stream().map(Enum::name).toList()));
        rep.put("results", records.stream().map(r -> Map.of(
                "id", r.id().name(), "status", r.status().name(), "weight", r.id().weight, "detail", r.detail())).toList());
        return rep;
    }

    private void runOffline(CheckId id, Path ws, Map<String, Object> manifest, Map<CheckId, CheckResult> got) {
        switch (id) {
            case S1, S2, S3, S4, S5, S6, S7, S8, S9 -> StructureChecks.run(ws).forEach(r -> got.put(r.id(), r));
            case M1, M2, M3, M4 -> MoneySafetyChecks.run(ws).forEach(r -> got.put(r.id(), r));
            case P1 -> { try { got.put(id, p1(ws)); } catch (java.io.IOException e) { got.put(id, CheckResult.fail(id, "checker crashed: " + e)); } }
            case P2 -> got.put(id, p2(ws));
            case P3 -> got.put(id, p3(ws, manifest));
            default -> { }
        }
    }

    /** P2: plan subtasks have id/goal/deps/criterion — the plan parser IS the check */
    private CheckResult p2(Path ws) {
        Path plan = ws.resolve("docs/IMPLEMENTATION_PLAN.md");
        String planTxt;
        try { planTxt = Files.isRegularFile(plan) ? Files.readString(plan) : ""; }
        catch (java.io.IOException e) { return CheckResult.fail(CheckId.P2, "checker crashed: " + e); }
        if (planTxt.length() < 200) return CheckResult.notAttempted(CheckId.P2, "IMPLEMENTATION_PLAN.md missing or trivial");
        try {
            var tasks = com.agentbench.plan.PlanParser.parseFile(plan);
            boolean ok = tasks.stream().allMatch(t -> t.goal != null && !t.goal.isBlank() && t.deps != null
                    && t.acceptance != null && !t.acceptance.isBlank());
            return new CheckResult(CheckId.P2, ok ? CheckStatus.PASS : CheckStatus.FAIL,
                    tasks.size() + " tasks parsed; " + (ok ? "all carry goal/deps/criterion"
                            : "missing fields in " + tasks.stream().filter(t -> t.goal == null || t.goal.isBlank() || t.acceptance == null || t.acceptance.isBlank()).map(t -> t.id).toList()));
        } catch (com.agentbench.plan.PlanError e) {
            return CheckResult.fail(CheckId.P2, "plan unparseable: " + e.getMessage());
        }
    }

    /** P1: required sections of the task definition; PASS when at most one is missing (05_phases.py) */
    private static final Map<String, java.util.regex.Pattern> REQ_SECTIONS = Map.ofEntries(
            Map.entry("scope", java.util.regex.Pattern.compile("^#+\\s*.*\\b(scope|overview)\\b", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.MULTILINE)),
            Map.entry("actors", java.util.regex.Pattern.compile("^#+\\s*.*\\bactors?\\b", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.MULTILINE)),
            Map.entry("domain model", java.util.regex.Pattern.compile("^#+\\s*.*\\b(domain|entit)", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.MULTILINE)),
            Map.entry("service boundaries", java.util.regex.Pattern.compile("^#+\\s*.*\\b(service|microservice|boundar)", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.MULTILINE)),
            Map.entry("non-functional", java.util.regex.Pattern.compile("^#+\\s*.*\\b(non-?functional|nfr|latency|consistency|audit)", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.MULTILINE)),
            Map.entry("out of scope", java.util.regex.Pattern.compile("\\bout[- ]of[- ]scope\\b", java.util.regex.Pattern.CASE_INSENSITIVE)),
            Map.entry("money handling", java.util.regex.Pattern.compile("\\b(BigDecimal|minor units|decimal|precision|rounding)\\b", java.util.regex.Pattern.CASE_INSENSITIVE)),
            Map.entry("order states", java.util.regex.Pattern.compile("\\b(state machine|order state|status transition|NEW|FILLED|CANCEL)", java.util.regex.Pattern.CASE_INSENSITIVE)));

    private CheckResult p1(Path ws) throws java.io.IOException {
        Path td = ws.resolve("docs/TASK_DEFINITION.md");
        String txt = Files.isRegularFile(td) ? Files.readString(td) : "";
        if (txt.length() < 200) return CheckResult.notAttempted(CheckId.P1, "TASK_DEFINITION.md missing or trivial");
        List<String> missing = REQ_SECTIONS.entrySet().stream()
                .filter(e -> !e.getValue().matcher(txt).find()).map(Map.Entry::getKey).toList();
        return new CheckResult(CheckId.P1, missing.size() <= 1 ? CheckStatus.PASS : CheckStatus.FAIL,
                missing.isEmpty() ? "all sections present" : (REQ_SECTIONS.size() - missing.size()) + "/" + REQ_SECTIONS.size() + " sections; missing: " + missing);
    }

    /** P3: no source code written during phases 0/1 — from the git snapshots the runner committed,
     *  not a scan of the final workspace (which any implemented run fails) */
    private CheckResult p3(Path ws, Map<String, Object> manifest) {
        Map<String, String> snaps = manifest != null && manifest.get("snapshots") instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
        String start = snaps.get("start"), p1 = snaps.get("p1");
        if (start == null || p1 == null)   // no snapshot pair recorded: fall back to the final-tree scan (fixture scoring)
            return p3Scan(ws);
        String diff = com.agentbench.docker.DockerService.sh(60, "git", "-C", ws.toString(),
                "diff", "--name-only", start + ".." + p1, "--", ".", ":(exclude)docs", ":(exclude)task").out();
        List<String> code = Arrays.stream(diff.split("\n")).filter(l -> !l.isBlank())
                .filter(l -> !l.endsWith(".md") && !l.endsWith(".txt")).toList();
        return new CheckResult(CheckId.P3, code.isEmpty() ? CheckStatus.PASS : CheckStatus.FAIL,
                code.isEmpty() ? "no source written during phases 0/1"
                        : "code written during planning: " + code.subList(0, Math.min(5, code.size())));
    }

    private CheckResult p3Scan(Path ws) {
        List<String> code = new ArrayList<>();
        for (String ext : List.of(".java", ".kt", ".sql", ".yml", ".yaml")) {
            for (Path p : StructureChecks.glob(ws, "**/*")) {
                String n = p.toString();
                if (n.endsWith(ext) && !n.contains("/task/") && !n.contains("/docs/") && !StructureChecks.skip(p)) code.add(ws.relativize(p).toString());
                if (code.size() >= 6) break;
            }
            if (code.size() >= 6) break;
        }
        boolean noCode = code.stream().noneMatch(c -> c.endsWith(".java") || c.endsWith(".kt"));
        return new CheckResult(CheckId.P3, noCode ? CheckStatus.PASS : CheckStatus.FAIL,
                noCode ? "no production sources at definition time" : "sources present: " + code);
    }
}
