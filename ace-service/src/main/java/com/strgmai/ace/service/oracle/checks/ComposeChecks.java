package com.strgmai.ace.service.oracle.checks;

import com.strgmai.ace.service.docker.DockerService;
import com.strgmai.ace.service.oracle.CheckId;
import com.strgmai.ace.service.oracle.CheckResult;
import com.strgmai.ace.service.oracle.CheckStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Port of oracle/checks/04_compose.py: runtime lifecycle. sweep stale stacks -> compose build ->
 *  up -> C1/C2 health -> black-box suite (F*) -> down.
 *  - compose file = the ROOT-most one (a per-service compose never shadows the stack)
 *  - build and health have SEPARATE budgets: three JVM images plus a React build on a cold cache is
 *    a build problem, not a health problem
 *  - one-shot containers that exited 0 (migrations) are neutral; database rows are never "bad";
 *    `restarting`/`dead`/`unhealthy`/non-zero exit are
 *  - infrastructure failures (daemon gone, registry unreachable, port held by a stale stack) are
 *    INFRA, never charged to the agent
 *  - ids already emitted are never overwritten by a late crash
 *  - the stack is built from a SOURCE-ONLY COPY of the workspace: a jar the agent built on the
 *    host can never ship in the image, so C/F measure "does it build and run from source" (R4 C-1) */
public final class ComposeChecks {
    private ComposeChecks() {}

    private static final Logger log = LoggerFactory.getLogger(ComposeChecks.class);

    static final List<String> DB_HINTS = List.of("postgres", "db", "database", "redis", "kafka", "zookeeper", "rabbit", "mongo", "flyway", "liquibase", "migrat");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<CheckId> IDS = new LinkedHashSet<>(List.of(CheckId.C1, CheckId.C2, CheckId.F1, CheckId.F2, CheckId.F3, CheckId.F4,
            CheckId.F5, CheckId.F6, CheckId.F7, CheckId.F8, CheckId.F9));

    static DockerService.Sh compose(String proj, Path cf, final int timeout, final String... args) {
        final List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-p", proj, "-f", cf.toString()));
        cmd.addAll(List.of(args));
        return DockerService.sh(timeout, cmd.toArray(String[]::new));
    }

    static List<Map<String, Object>> ps(String proj, Path cf) {
        final List<Map<String, Object>> rows = new ArrayList<>();
        final String out = compose(proj, cf, 60, "ps", "-a", "--format", "json").out();
        for (String line : out.split("\n")) {
            if (line.isBlank()) continue;
            try {
                final JsonNode n = JSON.readTree(line.strip());
                if (n.isArray()) for (JsonNode x : n) rows.add(JSON.convertValue(x, Map.class));
                else if (n.isObject()) rows.add(JSON.convertValue(n, Map.class));
            } catch (Exception e) { log.warn("could not parse `docker compose ps` line for project {}: {}", proj, e.toString()); }
        }
        return rows;
    }

    /** tear down benchmark stacks a killed oracle left behind (they hold :8080) */
    static void sweepStale() {
        final DockerService.Sh ls = DockerService.sh(60, "docker", "compose", "ls", "-a", "--format", "json");
        if (ls.rc() != 0) {
            // a silently skipped sweep leaves leaked containers/images with no trace, and subsequent
            // oracle runs can mysteriously fail to bind :8080 with nothing pointing back to why
            log.warn("`docker compose ls` failed (rc={}), skipping the stale-stack sweep: {}", ls.rc(), ls.out());
            return;
        }
        try {
            for (JsonNode p : JSON.readTree(ls.out().isEmpty() ? "[]" : ls.out())) {
                final String name = p.path("Name").asText("");
                if (name.startsWith("ab") && name.length() > 2 && name.substring(2).chars().allMatch(Character::isDigit))
                    // --rmi local too (R4 C-24): without it, a killed oracle leaves the stale stack's
                    // images behind forever - this sweep is the only chance to remove them, since the
                    // process that would have hit the finally block in run() below is already gone
                    DockerService.sh(300, "docker", "compose", "-p", name, "down", "-v", "--remove-orphans", "--rmi", "local");
            }
        } catch (Exception e) {
            log.warn("stale-stack sweep failed, leftover ab<digits> stacks may remain: {}", e.toString());
        }
    }

    static boolean isDb(final Map<String, Object> row) {
        final String svc = String.valueOf(row.getOrDefault("Service", row.getOrDefault("Name", "")));
        return DB_HINTS.stream().anyMatch(h -> svc.toLowerCase().contains(h));
    }

    record Classified(List<Map<String, Object>> svc, List<Map<String, Object>> healthy, List<Map<String, Object>> bad) {}

    static Classified classify(List<Map<String, Object>> rows) {
        final List<Map<String, Object>> svc = rows.stream().filter(r -> !isDb(r)).toList();
        final List<Map<String, Object>> bad = new ArrayList<>(), healthy = new ArrayList<>();
        for (Map<String, Object> r : svc) {   // database rows are never "bad" (C-12): a slow Postgres shows up as unhealthy services
            final String state = String.valueOf(r.getOrDefault("State", "")).toLowerCase();
            final String health = String.valueOf(r.getOrDefault("Health", "")).toLowerCase();
            if (List.of("dead", "restarting").contains(state) || health.equals("unhealthy")) bad.add(r);
            else if (state.equals("exited") && !List.of("0", "None", "", "null").contains(String.valueOf(r.get("ExitCode")))) bad.add(r);   // exit 0 = one-shot job, neutral
            if (health.equals("healthy")) healthy.add(r);
        }
        return new Classified(svc, healthy, bad);
    }

    /** Dockerfiles that COPY/ADD a host-built artefact from the build context without `--from=`: the
     *  image would depend on something a clean checkout does not contain. */
    static List<String> prebuiltArtefactCopies(Path ws) {
        final List<String> hits = new ArrayList<>();
        // COPY --from=<stage> reads from a build STAGE, not the context: a legitimate multi-stage build (R5 C-1)
        final Pattern copy = Pattern.compile("(?im)^\\s*(?:COPY|ADD)\\s+(?!--from)(?:--\\S+\\s+)*(\\S+)");
        final Pattern artefactSrc = Pattern.compile("(?i)(^|/)(build|target|dist|out)/|[\\w.-]+\\.(jar|war|ear|class)$");
        for (Path df : StructureChecks.glob(ws, "**/Dockerfile*")) {
            String src;
            // a read failure here silently means this Dockerfile is never checked for a host-built
            // artefact copy - an R5 C-1 violation could pass unnoticed, not because it's clean
            try { src = Files.readString(df); }
            catch (IOException e) { log.warn("could not read {} for the prebuilt-artefact check: {}", df, e.toString()); continue; }
            final java.util.regex.Matcher m = copy.matcher(src);
            while (m.find()) {
                final String s = m.group(1);
                if (artefactSrc.matcher(s).find() && !s.contains("gradle-wrapper.jar"))
                    hits.add(ws.relativize(df) + ": COPY " + s);
            }
        }
        return hits;
    }

    /** `context:`/`dockerfile:` or bind-mount sources given as absolute paths escape the source-only
     *  copy the oracle builds from. */
    static List<String> absoluteComposePaths(final Path composeFile) {
        final List<String> hits = new ArrayList<>();
        String txt;
        // a read failure here silently means this compose file is never checked for an escaped
        // absolute path (R5 C-19) - an empty result then reads as "clean", not "could not check"
        try { txt = Files.readString(composeFile); }
        catch (IOException e) { log.warn("could not read {} for the absolute-path check: {}", composeFile, e.toString()); return hits; }
        for (var m : Pattern.compile("(?m)^\\s*(?:context|dockerfile)\\s*:\\s*(/\\S+)", Pattern.CASE_INSENSITIVE).matcher(txt).results().toList())
            hits.add(m.group(0).strip());
        for (var m : Pattern.compile("(?m)^\\s*-\\s*['\"]?(/(?!dev/|proc/|sys/)[^:'\"\\s]+):", Pattern.CASE_INSENSITIVE).matcher(txt).results().toList())
            hits.add(m.group(0).strip());
        return hits;
    }

    /** runs C1/C2 and the wanted F* checks while the agent's stack is up; the stack is always torn down */
    public static List<CheckResult> run(Path wsOrig, int buildTimeout, int healthTimeout,
                                        java.util.function.Predicate<CheckId> wanted, String base) throws Exception {
        return run(wsOrig, buildTimeout, healthTimeout, wanted, base, wsOrig);
    }

    public static List<CheckResult> run(Path wsOrig, int buildTimeout, int healthTimeout,
                                        java.util.function.Predicate<CheckId> wanted, String base, Path wsForSpec) throws Exception {
        final List<CheckResult> out = new ArrayList<>();
        Set<CheckId> emitted = new LinkedHashSet<>();   // ids already emitted are never overwritten by a late crash
        final Path cfOrig = StructureChecks.findCompose(wsOrig);
        if (cfOrig == null) {
            for (CheckId c : IDS) if (wanted.test(c)) out.add(CheckResult.notAttempted(c, "no docker-compose / compose file"));
            return out;
        }
        final var tmp = Files.createTempDirectory("ab-compose-");
        final String proj = "ab" + (System.currentTimeMillis() / 1000);
        Path ws = BuildChecks.scratchCopy(wsOrig, tmp);   // source-only copy (R4 C-1)
        final Path cf = StructureChecks.findCompose(ws);
        try {
            List<String> pre = prebuiltArtefactCopies(ws);   // R5 C-1: say WHY before the build fails without the host-built jar
            if (!pre.isEmpty()) {
                for (CheckId c : IDS) if (wanted.test(c)) out.add(CheckResult.fail(c, "image depends on a host-built artefact (" + head(pre.get(0), 120) + "): images must build from source on a clean checkout"));
                return out;
            }
            List<String> absp = absoluteComposePaths(cf);    // R5 C-19: an absolute build context/bind mount escapes the copy
            if (!absp.isEmpty()) {
                for (CheckId c : IDS) if (wanted.test(c)) out.add(CheckResult.fail(c, "compose file uses absolute host paths (" + head(absp.get(0), 100) + "): the stack must build from the repository"));
                return out;
            }
            sweepStale();
            if (DockerService.portInUse(8080, "127.0.0.1")) {
                for (CheckId c : IDS) if (wanted.test(c)) out.add(new CheckResult(c, CheckStatus.INFRA, "port 8080 already bound by a non-benchmark process before compose up"));
                return out;
            }
            // ---- build (its own budget) ----
            final DockerService.Sh b = compose(proj, cf, buildTimeout, "build", "--quiet");
            if (b.rc() != 0) {
                final String txt = b.out().strip();
                if (b.rc() == 124) { rest(out, emitted, wanted, CheckStatus.FAIL, "compose build exceeded " + buildTimeout + "s"); return out; }
                final String reason = BuildChecks.infraReason(txt);
                if (!reason.isEmpty()) { rest(out, emitted, wanted, CheckStatus.INFRA, "compose build infrastructure: " + reason); return out; }
                rest(out, emitted, wanted, CheckStatus.FAIL, "compose build rc=" + b.rc() + ": " + lastLine(txt, 150));
                return out;
            }
            // ---- up + health (its own budget) ----
            final DockerService.Sh u = compose(proj, cf, healthTimeout, "up", "-d", "--no-build", "--quiet-pull");
            if (u.rc() != 0) {
                final String txt = u.out().strip();
                final String reason = BuildChecks.infraReason(txt);
                if (!reason.isEmpty()) { rest(out, emitted, wanted, CheckStatus.INFRA, "compose up infrastructure: " + reason); return out; }
                rest(out, emitted, wanted, CheckStatus.FAIL, "compose up rc=" + u.rc() + ": " + lastLine(txt, 150));
                return out;
            }
            // a rung without C1 (L2/L3/L3p: one service) must not idle for the whole budget (C-9)
            final int need = wanted.test(CheckId.C1) ? 3 : 1;
            final long deadline = System.currentTimeMillis() + healthTimeout * 1000L;
            List<Map<String, Object>> rows = List.of();
            while (System.currentTimeMillis() < deadline) {
                rows = ps(proj, cf);
                final Classified c = classify(rows);
                if ((c.healthy().size() >= need || (!c.svc().isEmpty() && c.healthy().size() == c.svc().size())) && c.bad().isEmpty()) break;
                Thread.sleep(10_000);
            }
            final Classified c = classify(rows);
            List<String> nohc = c.svc().stream().filter(r -> String.valueOf(r.getOrDefault("Health", "")).isEmpty())
                    .map(r -> String.valueOf(r.get("Service"))).limit(3).toList();
            out.add(new CheckResult(CheckId.C1, emitted.add(CheckId.C1) && c.healthy().size() >= 3 && c.bad().isEmpty() ? CheckStatus.PASS : CheckStatus.FAIL,
                    c.healthy().size() + "/" + c.svc().size() + " non-db services healthy; bad="
                            + c.bad().stream().limit(3).map(r -> "(" + r.get("Service") + "," + r.get("State") + "," + r.get("ExitCode") + ")").toList()
                            + "; no-healthcheck=" + nohc));
            // ---- C2: readiness endpoint at the contract's base (status only) ----
            boolean ok2 = false; String det = "";
            for (int i = 0; i < 6 && !ok2; i++) {
                try {
                    HttpResponse<Void> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/health"))
                            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    ok2 = r.statusCode() == 200;
                    det = base + "/health -> " + r.statusCode();
                } catch (Exception e) { det = base + "/health unreachable: " + head(String.valueOf(e), 60); Thread.sleep(5000); }
            }
            out.add(new CheckResult(CheckId.C2, emitted.add(CheckId.C2) && ok2 ? CheckStatus.PASS : CheckStatus.FAIL, det));
            // ---- F* while the stack is up; system not reachable -> NOT_ATTEMPTED, never FAIL ----
            final var suite = new BlackboxScenarios(base);
            if (!suite.up()) {
                for (CheckId id : IDS)
                    if (id.name().startsWith("F") && wanted.test(id)) out.add(CheckResult.notAttempted(id, "system not reachable at " + base + "/health"));
                return out;
            }
            for (CheckId id : IDS) {
                if (!id.name().startsWith("F") || !wanted.test(id) || emitted.contains(id)) continue;
                boolean ok = switch (id) {
                    case F1 -> suite.f1BuyThenSellRestoresHoldings();
                    case F2 -> suite.f2PointInTimeBetweenBuyAndSell();
                    case F3 -> suite.f3InsufficientBalanceRejectedWith422();
                    case F4 -> suite.f4IllegalTransitionRejectedWith409();
                    case F5 -> suite.f5DecimalAmountsRoundTripExactly();
                    case F7 -> suite.f7DepositsRoundHalfEven();
                    case F8 -> suite.f8AsOfBoundaryExclusiveAndEchoed();
                    case F9 -> suite.f9IdempotencyKeyRepeat();
                    case F6 -> suite.f6ContractConformance(wsForSpec);
                    default -> false;
                };
                emitted.add(id);
                out.add(new CheckResult(id, ok ? CheckStatus.PASS : CheckStatus.FAIL, suite.notes.getOrDefault(id.name(), "scenario ran")));
            }
            for (CheckId id : IDS)
                if (id.name().startsWith("F") && wanted.test(id) && !emitted.contains(id))
                    out.add(CheckResult.fail(id, "black-box suite emitted no record"));
            return out;
        } finally {
            compose(proj, cf, 300, "down", "-v", "--remove-orphans", "--rmi", "local");   // images of this project are not kept (R4 C-24)
        }
    }

    static void rest(final List<CheckResult> out, final Set<CheckId> emitted, final java.util.function.Predicate<CheckId> wanted, final CheckStatus status, final String detail) {
        for (CheckId c : IDS) if (wanted.test(c) && emitted.add(c)) out.add(new CheckResult(c, status, detail));
    }

    static String head(String s, int n) { return s == null ? "" : s.substring(0, Math.min(n, s.length())); }
    static String lastLine(final String txt, final int n) {
        final String[] lines = Arrays.stream(txt.split("\n")).filter(l -> !l.isBlank()).toArray(String[]::new);
        return lines.length == 0 ? "" : head(lines[lines.length - 1], n);
    }
}
