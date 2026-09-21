package com.strgmai.ace.service.oracle.checks;

import com.strgmai.ace.service.docker.DockerService;
import com.strgmai.ace.service.oracle.CheckId;
import com.strgmai.ace.service.oracle.CheckResult;
import com.strgmai.ace.service.oracle.CheckStatus;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Port of oracle/checks/03_build.py: build checks made EVIDENTIAL. gradle always runs from the
 *  PINNED IMAGE (the agent's wrapper is never executed); every Gradle root is built; B1 = all
 *  roots assemble, B2 = XML-verified tests (executed>0, failed==0, no neutered test task), B3 =
 *  the agent's suite must FAIL on a seeded mutation of the sell-side arithmetic. Tests get a real
 *  PostgreSQL sidecar on a private network, recreated before every run. A warm Gradle cache is
 *  mounted from the host; dependency/daemon failures are INFRA. Everything runs on a scratch
 *  copy; the mutation never touches the agent's files. */
public final class BuildChecks {
    private BuildChecks() {}

    public static final int GRADLE_TIMEOUT = 2400;
    public static final String DEFAULT_IMAGE = "gradle:8.14-jdk21";
    static final String DB_IMAGE = "postgres:16-alpine";
    public static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache/ace-service/gradle");
    // _common.py INFRA_PATTERNS, the full set: infrastructure failures are never the agent's
    public static final String INFRA_RE = "docker daemon|Cannot connect to the Docker daemon|network .* not found|registry|unauthorized|toomanyrequests|no space|EOF occurred|connection reset|Connection refused|Connection timed out|Read timed out|Could not GET|TLS handshake timeout|rpc error|failed to solve: failed to fetch|Could not resolve|daemon down|malformed|buildkit|Error response from daemon";
    public static final List<String> SKIP_DIRS = List.of("node_modules", ".git", "build", ".gradle", ".gradle-cache", "target", "dist", "out");

    // ---- mutants: (name, regex on MASKED source, replacement, money-line only?)
    record Mutant(String name, Pattern rx, String replacement, boolean moneyOnly) {}
    static final List<Mutant> MUTANTS = List.of(
            new Mutant("subtract->add", Pattern.compile("\\.subtract\\("), ".add(", false),
            new Mutant("negate->identity", Pattern.compile("\\.negate\\(\\)"), "", false),
            new Mutant("-qty->qty", Pattern.compile("(?<![\\w)\\]\\s])\\s*-\\s*(?=\\w*(?:qty|quantity|amount)\\b)"), "", false),
            new Mutant("-=->+=", Pattern.compile("-="), "+=", true),
            new Mutant("minus->plus", Pattern.compile("\\.minus\\("), ".plus(", true));
    static final Pattern ANCHOR = Pattern.compile("\\b(SELL|BUY|Side\\.\\w+|OrderSide\\.\\w+)\\b");
    static final Pattern PIT_DEF = Pattern.compile("\\b(holdings?At|positions?At|balanceAt|asOf|pointInTime|snapshotAt|replay\\w*)\\s*\\(", Pattern.CASE_INSENSITIVE);
    static final Pattern FILE_HINT = Pattern.compile("holding|position|ledger|portfolio", Pattern.CASE_INSENSITIVE);
    static final Pattern MONEY_LINE = Pattern.compile("qty|quantity|amount|balance|holding|position", Pattern.CASE_INSENSITIVE);
    static final int ANCHOR_DISTANCE = 3000;

    public static String infraReason(final String text) {
        if (text == null) return "";
        final Matcher m = Pattern.compile(INFRA_RE, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(0).substring(0, Math.min(80, m.group(0).length())) : "";
    }

    // ---- the PostgreSQL sidecar on a private network
    static final class Infra {
        final String net, db;
        boolean ok; String err = "";
        Infra(String net) { this.net = net; this.db = net + "-db"; }
        Infra up() {
            DockerService.Sh r = DockerService.sh(60, "docker", "network", "create", net);
            if (r.rc() != 0) { err = tail(r.out(), 160); return this; }
            r = DockerService.sh(300, "docker", "run", "-d", "--rm", "--name", db, "--network", net,
                    "-e", "POSTGRES_USER=bench", "-e", "POSTGRES_PASSWORD=bench", "-e", "POSTGRES_DB=bench", DB_IMAGE);
            if (r.rc() != 0) { err = tail(r.out(), 160); return this; }
            for (int i = 0; i < 45; i++) {
                if (DockerService.sh(15, "docker", "exec", db, "pg_isready", "-U", "bench").rc() == 0) { ok = true; return this; }
                DockerService.sleep(2);
            }
            err = "postgres sidecar never became ready";
            return this;
        }
        void resetDb() {
            if (!ok) return;   // two -c: DROP DATABASE cannot run in a transaction block
            DockerService.sh(60, "docker", "exec", db, "psql", "-U", "bench", "-d", "postgres",
                    "-c", "DROP DATABASE IF EXISTS bench WITH (FORCE);", "-c", "CREATE DATABASE bench;");
        }
        void down() {
            DockerService.sh(60, "docker", "rm", "-f", db);
            DockerService.sh(60, "docker", "network", "rm", net);
        }
    }

    static String gradle(Path copy, final String image, final String task, Infra infra, final int timeout) throws IOException, InterruptedException {
        Files.createDirectories(CACHE);
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm",
                "-v", copy + ":/w", "-v", CACHE + ":/gh", "-w", "/w",
                "-e", "GRADLE_USER_HOME=/gh", "-e", "GRADLE_OPTS=-Dorg.gradle.daemon=false", "--memory=6g"));
        if (infra.ok)
            cmd.addAll(List.of("--network", infra.net,
                    "-e", "SPRING_DATASOURCE_URL=jdbc:postgresql://" + infra.db + ":5432/bench",
                    "-e", "SPRING_DATASOURCE_USERNAME=bench", "-e", "SPRING_DATASOURCE_PASSWORD=bench",
                    "-e", "AB_DB_HOST=" + infra.db, "-e", "AB_DB_PORT=5432", "-e", "AB_DB_NAME=bench", "-e", "AB_DB_USER=bench", "-e", "AB_DB_PASSWORD=bench"));
        cmd.addAll(List.of(image, "gradle", "--no-daemon", "-q", "--console=plain", "--warning-mode=none", task));
        var r = DockerService.proc(timeout, null, null, cmd.toArray(String[]::new));   // drained concurrently: gradle output overflows the pipe
        return r.rc() + "\n" + r.out() + r.err();
    }

    public static String firstError(String out) {
        for (String line : out.split("\n")) {
            if (Pattern.compile(": error:|^e: |error: |Could not find|What went wrong|Unresolved reference|cannot find symbol|FAILURE:").matcher(line).find())
                return line.strip().substring(0, Math.min(160, line.strip().length()));
        }
        final String[] lines = Arrays.stream(out.strip().split("\n")).filter(s -> !s.isBlank()).toArray(String[]::new);
        return (lines.length == 0 ? "" : String.join(" ", Arrays.copyOfRange(lines, Math.max(0, lines.length - 2), lines.length))).substring(0, Math.min(160, Math.max(1, lines.length * 80)));
    }

    public static int[] testStats(Path root) {   // {executed, failed} from JUnit-style XML under build/test-results
        int ex = 0, fl = 0;
        for (Path x : StructureChecks.glob(root, "**/build/test-results/**/*.xml")) {
            try {
                final Element r = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(x.toFile()).getDocumentElement();
                if (r.getTagName().equals("testsuite")) {
                    ex += Integer.parseInt(r.getAttribute("tests").isEmpty() ? "0" : r.getAttribute("tests"));
                    fl += Integer.parseInt(r.getAttribute("failures").isEmpty() ? "0" : r.getAttribute("failures"))
                            + Integer.parseInt(r.getAttribute("errors").isEmpty() ? "0" : r.getAttribute("errors"));
                }
            } catch (Exception ignore) {}
        }
        return new int[]{ex, fl};
    }

    /** Replace comments (and by default string literals) with spaces, PRESERVING offsets, so a match in the
     *  masked text applies to the original at the same index. */
    public static String mask(String src, final boolean strings) {
        final char[] out = src.toCharArray();
        int i = 0, n = src.length();
        while (i < n) {
            if (src.startsWith("//", i)) {
                int j = src.indexOf('\n', i); if (j < 0) j = n;
                Arrays.fill(out, i, j, ' '); i = j;
            } else if (src.startsWith("/*", i)) {
                int j = src.indexOf("*/", i + 2); j = j < 0 ? n : j + 2;
                Arrays.fill(out, i, j, ' '); i = j;
            } else if (src.startsWith("\"\"\"", i)) {                      // Java text block
                int j = src.indexOf("\"\"\"", i + 3); j = j < 0 ? n : j;
                if (strings) Arrays.fill(out, i + 3, Math.min(j, n), ' ');
                i = Math.min(j + 3, n);
            } else if (src.charAt(i) == '"' || src.charAt(i) == '\'') {
                final char q = src.charAt(i); int j = i + 1;
                while (j < n && src.charAt(j) != q) { if (src.charAt(j) == '\\' && j + 1 < n) j++; j++; }
                if (strings) Arrays.fill(out, i + 1, Math.min(j, n), ' ');
                i = Math.min(j + 1, n);
            } else i++;
        }
        return new String(out);
    }

    /** A build script that disables the TEST task or ignores its failures; scoped to test-task blocks. */
    public static List<String> neutered(List<Path> roots, final Path ws) {
        final Set<String> hits = new TreeSet<>();
        final Pattern blockStart = Pattern.compile("(?:^|\\n)\\s*(?:tasks\\.(?:named|withType)\\(\\s*['\"]?[Tt]est['\"]?[^)]*\\)|tasks\\.test|test)\\s*\\{");
        for (Path r : roots)
            for (Path bf : StructureChecks.glob(r, "**/build.gradle*")) {
                String src;
                try { src = Files.readString(bf).replaceAll("(?s)//[^\\n]*|/\\*.*?\\*/", ""); } catch (IOException e) { continue; }
                final Matcher m = blockStart.matcher(src);
                while (m.find()) {
                    int start = m.end(), depth = 1, i = start;
                    while (i < src.length() && depth > 0) { depth += src.charAt(i) == '{' ? 1 : src.charAt(i) == '}' ? -1 : 0; i++; }
                    final String block = src.substring(start, i);
                    if (Pattern.compile("ignoreFailures\\s*=\\s*true|enabled\\s*=\\s*false|\\bexclude\\s*\\(?\\s*['\"]\\*\\*(?:/\\*)?(?:\\*?Tests?\\*?)?['\"]").matcher(block).find())
                        hits.add(ws.relativize(bf).toString());
                }
                if (Pattern.compile("tasks\\.test\\.enabled\\s*=\\s*false|test\\.enabled\\s*=\\s*false|-x\\s+test").matcher(src).find())
                    hits.add(ws.relativize(bf).toString());
            }
        return new ArrayList<>(hits);
    }

    public record Candidate(Path file, String name, int start, int end, String replacement, String anchor) {}

    /** Sell-side arithmetic candidates, NEAREST an anchor first (masked source: comments/strings never count). */
    public static List<Candidate> pickMutations(Path copy, final int limit) {
        record Cand(int dist, Path file, String name, int start, int end, String rep, String anchor) {}
        final List<Cand> cands = new ArrayList<>();
        final List<Path> sources = new ArrayList<>(StructureChecks.glob(copy, "**/src/main/**/*.java"));
        sources.addAll(StructureChecks.glob(copy, "**/src/main/**/*.kt"));
        for (Path jf : sources) {
            // SEGMENTS, not substrings (see scratchCopy below): the scratch tmp dir is named
            // "ab-build-<random>", so a naive jf.toString().contains("build") would skip every
            // file under it - this was a real bug (B3 always NOT_ATTEMPTED: "no sell-side
            // arithmetic... to mutate" even when the source plainly had it).
            final List<String> segs = Arrays.asList(jf.toString().split("/"));
            if (SKIP_DIRS.stream().anyMatch(segs::contains)) continue;
            String src;
            try { src = Files.readString(jf); } catch (IOException e) { continue; }
            final String m = mask(src, true), mKeep = mask(src, false);
            List<int[]> anchors = new ArrayList<>();   // [pos, labelIdx]
            final List<String> labels = new ArrayList<>();
            final Matcher a = ANCHOR.matcher(mKeep);
            while (a.find()) { anchors.add(new int[]{a.start(), labels.size()}); labels.add(a.group(1)); }
            final Matcher p = PIT_DEF.matcher(mKeep);
            while (p.find()) { anchors.add(new int[]{p.start(), labels.size()}); labels.add("def " + p.group(1)); }
            final String hint = FILE_HINT.matcher(jf.getFileName().toString()).find() ? jf.getFileName().toString() : null;
            if (anchors.isEmpty() && hint == null) continue;
            for (Mutant mu : MUTANTS) {
                final Matcher x = mu.rx().matcher(m);
                while (x.find()) {
                    if (mu.moneyOnly()) {
                        final int ls = m.lastIndexOf('\n', x.start() - 1) + 1;
                        final int le = m.indexOf('\n', x.end());
                        final String line = m.substring(ls, le < 0 ? m.length() : le);
                        if (!MONEY_LINE.matcher(line).find()) continue;
                    }
                    int bestDist = Integer.MAX_VALUE; int bestLab = -1;
                    for (int[] an : anchors) { int d = Math.abs(x.start() - an[0]); if (d < bestDist) { bestDist = d; bestLab = an[1]; } }
                    if (bestLab >= 0 && bestDist <= ANCHOR_DISTANCE)
                        cands.add(new Cand(bestDist, jf, mu.name(), x.start(), x.end(), mu.replacement(), labels.get(bestLab) + "@" + bestDist));
                    else if (hint != null)
                        cands.add(new Cand(ANCHOR_DISTANCE, jf, mu.name(), x.start(), x.end(), mu.replacement(), "file:" + hint));
                }
            }
        }
        cands.sort(Comparator.comparingInt((Cand c) -> c.dist).thenComparing(c -> c.name).thenComparing(c -> c.file.toString()));
        final List<Candidate> out = new ArrayList<>();
        for (Cand c : cands.subList(0, Math.min(limit, cands.size())))
            out.add(new Candidate(c.file(), c.name(), c.start(), c.end(), c.rep(), c.anchor()));
        return out;
    }

    /** the scratch copy: build outputs and caches never ride into the pinned container (one artefact definition) */
    static Path scratchCopy(final Path ws, Path tmp) throws IOException {
        final Path copy = tmp.resolve("w");
        try (var walk = Files.walk(ws)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                final String rel = ws.relativize(p).toString();
                final String[] segs = rel.split("/");
                if (SKIP_DIRS.stream().anyMatch(d -> Arrays.asList(segs).contains(d))) continue;   // SEGMENTS, not substrings: "build.gradle" is not "build/"
                if (List.of(".jar", ".class", ".war").stream().anyMatch(rel::endsWith)
                        && !rel.endsWith("gradle/wrapper/gradle-wrapper.jar")) continue;   // the wrapper is source, not a build artefact (R5 C-2)
                Files.createDirectories(copy.resolve(rel).getParent());
                Files.copy(p, copy.resolve(rel));
            }
        }
        return copy;
    }

    public static List<CheckResult> run(final Path ws, final String image) throws Exception {
        final List<CheckResult> out = new ArrayList<>();
        final List<Path> roots = StructureChecks.gradleRoots(ws);
        if (roots.isEmpty()) {
            for (CheckId c : List.of(CheckId.B1, CheckId.B2, CheckId.B3))
                out.add(CheckResult.notAttempted(c, "no Gradle project (no settings.gradle*/build.gradle* outside build dirs)"));
            return out;
        }
        final var infra = new Infra("abnet" + ProcessHandle.current().pid()).up();
        final var tmp = Files.createTempDirectory("ab-build-");
        try {
            final Path copy = scratchCopy(ws, tmp);
            final List<Path> croots = roots.stream().map(r -> r.equals(ws) ? copy : copy.resolve(ws.relativize(r))).toList();
            final List<String> names = roots.stream().map(r -> ws.relativize(r).toString().isEmpty() ? "." : ws.relativize(r).toString()).toList();

            // ---- B1: every root assembles
            final List<String> fails = new ArrayList<>(), infraHits = new ArrayList<>();
            for (int i = 0; i < croots.size(); i++) {
                final String[] res = gradle(croots.get(i), image, "assemble", infra, GRADLE_TIMEOUT).split("\n", 2);
                final int rc = Integer.parseInt(res[0]);
                final String output = res.length > 1 ? res[1] : "";
                if (rc != 0) {
                    final String reason = infraReason(output);
                    (reason.isEmpty() ? fails : infraHits).add(names.get(i) + ": " + (reason.isEmpty() ? firstError(output) : reason));
                }
            }
            if (!infraHits.isEmpty()) {
                for (CheckId c : List.of(CheckId.B1, CheckId.B2, CheckId.B3)) out.add(new CheckResult(c, CheckStatus.INFRA, "build infrastructure: " + head(infraHits.get(0), 160)));
                return out;
            }
            out.add(new CheckResult(CheckId.B1, fails.isEmpty() ? CheckStatus.PASS : CheckStatus.FAIL,
                    fails.isEmpty() ? roots.size() + " root(s) " + names.subList(0, Math.min(4, names.size())) + " assemble" : head(String.join("; ", fails), 250)));
            if (!fails.isEmpty()) {
                out.add(CheckResult.fail(CheckId.B2, "build did not compile"));
                out.add(CheckResult.fail(CheckId.B3, "build did not compile"));
                return out;
            }
            if (!infra.ok) {   // the prompt promises a database: without it B2/B3 are the machine's fault, never the agent's
                for (CheckId c : List.of(CheckId.B2, CheckId.B3)) out.add(new CheckResult(c, CheckStatus.INFRA, "postgres sidecar unavailable: " + head(infra.err, 120)));
                return out;
            }

            // ---- B2: tests executed AND green, verified from XML, across roots; a neutered test task is caught too
            final List<String> neut = neutered(roots, ws);
            int ex = 0, fl = 0;
            final List<Integer> rcs = new ArrayList<>(); infraHits.clear();
            for (int i = 0; i < croots.size(); i++) {
                infra.resetDb();
                final String[] res = gradle(croots.get(i), image, "test", infra, GRADLE_TIMEOUT).split("\n", 2);
                final int rc = Integer.parseInt(res[0]);
                final String output = res.length > 1 ? res[1] : "";
                rcs.add(rc);
                if (rc != 0 && !infraReason(output).isEmpty()) infraHits.add(names.get(i) + ": " + infraReason(output));
                final int[] st = testStats(croots.get(i));
                ex += st[0]; fl += st[1];
            }
            if (!infraHits.isEmpty() && ex == 0) {
                for (CheckId c : List.of(CheckId.B2, CheckId.B3)) out.add(new CheckResult(c, CheckStatus.INFRA, head(infraHits.get(0), 160)));
                return out;
            }
            CheckStatus b2;
            if (!neut.isEmpty()) { b2 = CheckStatus.FAIL; out.add(CheckResult.fail(CheckId.B2, "test task neutered in " + neut.subList(0, Math.min(2, neut.size())))); }
            else if (ex == 0) { b2 = CheckStatus.FAIL; out.add(CheckResult.fail(CheckId.B2, "0 tests executed (gradle test exit 0 with no tests is not evidence)")); }
            else {
                b2 = (rcs.stream().allMatch(r -> r == 0) && fl == 0) ? CheckStatus.PASS : CheckStatus.FAIL;
                out.add(new CheckResult(CheckId.B2, b2, ex + " executed, " + fl + " failed, rc=" + rcs));
            }

            // ---- B3: seed ONE mutation into the sell-side arithmetic; the suite MUST fail
            if (b2 != CheckStatus.PASS) { out.add(CheckResult.notAttempted(CheckId.B3, "mutation testing needs a green suite (B2 did not pass)")); return out; }
            final List<Candidate> cands = pickMutations(copy, 4);
            if (cands.isEmpty()) {
                out.add(CheckResult.notAttempted(CheckId.B3, "no sell-side arithmetic (.subtract/.negate/-qty/-=/.minus near a SELL/BUY/Side token, a point-in-time method or in a holdings/position/ledger file) in src/main to mutate"));
                return out;
            }
            final List<String> tried = new ArrayList<>();
            for (Candidate c : cands) {
                final String src = Files.readString(c.file());
                Files.writeString(c.file(), src.substring(0, c.start()) + c.replacement() + src.substring(c.end()));
                final Path rootOf = croots.stream().filter(r -> c.file().startsWith(r) && !r.equals(copy)).findFirst().orElse(copy);
                for (Path x : StructureChecks.glob(rootOf, "**/build/test-results/**/*.xml")) Files.deleteIfExists(x);
                infra.resetDb();
                final String[] res = gradle(rootOf, image, "test", infra, GRADLE_TIMEOUT).split("\n", 2);
                int rc = Integer.parseInt(res[0]);
                final String output = res.length > 1 ? res[1] : "";
                final int[] st = testStats(rootOf);
                Files.writeString(c.file(), src);   // restore for the next candidate
                if (rc != 0 && st[0] == 0 && !infraReason(output).isEmpty()) { out.add(new CheckResult(CheckId.B3, CheckStatus.INFRA, infraReason(output))); return out; }
                if (st[0] == 0) { tried.add(c.name() + "@" + copy.relativize(c.file()) + ": did not compile"); continue; }   // invalid mutant
                final boolean caught = st[1] > 0;
                out.add(new CheckResult(CheckId.B3, caught ? CheckStatus.PASS : CheckStatus.FAIL,
                        "mutant " + c.name() + " in " + copy.relativize(c.file()) + " (anchor " + c.anchor() + "): " + st[0] + " run, " + st[1] + " failed, rc=" + rc
                                + " -> " + (caught ? "suite CAUGHT it" : "suite did NOT notice inverted sell arithmetic")
                                + (tried.isEmpty() ? "" : "; skipped " + tried)));
                return out;
            }
            out.add(CheckResult.notAttempted(CheckId.B3, "every candidate mutant failed to compile: " + tried.subList(0, Math.min(3, tried.size()))));
            return out;
        } finally {
            infra.down();
        }
    }

    static String head(String s, int n) { return s == null ? "" : s.substring(0, Math.min(n, s.length())); }
    static String tail(String s, int n) { return s == null ? "" : s.substring(Math.max(0, s.length() - n)); }
}
