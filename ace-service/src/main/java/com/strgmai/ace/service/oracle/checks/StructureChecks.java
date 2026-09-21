package com.strgmai.ace.service.oracle.checks;

import com.strgmai.ace.service.oracle.CheckId;
import com.strgmai.ace.service.oracle.CheckResult;
import com.strgmai.ace.service.oracle.CheckStatus;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Port of oracle/checks/01_structure.py (S1-S9). Always applicable. Hardened ports kept:
 *  S5 counts only build files that apply Spring Boot AND have src/main; S6 wants a real wrapper
 *  (script > 1 KB + properties) in ANY Gradle root; S7 requires the spec to look like OpenAPI
 *  (Java port checks the openapi/paths keys with a tolerant text scan instead of PyYAML). */
public final class StructureChecks {
    private StructureChecks() {}

    static final Set<String> SKIP_DIRS = Set.of("/build/", "/.gradle/", "/node_modules/", "/.git/", "/buildSrc/", "/dist/", "/target/", "/out/");

    public static boolean skip(Path p) {
        final String s = p.toString() + "/";
        return SKIP_DIRS.stream().anyMatch(s::contains);
    }

    public static List<CheckResult> run(final Path ws) {
        final List<CheckResult> out = new ArrayList<>();
        for (var e : Map.of(CheckId.S1, "docs/TASK_DEFINITION.md", CheckId.S2, "docs/IMPLEMENTATION_PLAN.md", CheckId.S3, "docs/PROGRESS.md").entrySet()) {
            final Path p = ws.resolve(e.getValue());
            final boolean present = Files.isRegularFile(p);
            final long size = present ? fileSize(p) : 0;
            out.add(new CheckResult(e.getKey(), present && size > 200 ? CheckStatus.PASS : CheckStatus.FAIL,
                    (present ? "present" : "missing") + " " + size + "B"));
        }
        final Path compose = findCompose(ws);
        out.add(new CheckResult(CheckId.S4, compose != null ? CheckStatus.PASS : CheckStatus.FAIL,
                "stack file: " + (compose == null ? "none" : ws.relativize(compose).toString())));

        // S5: a "service" = its own build file that applies Spring Boot AND has src/main
        final List<String> svcs = new ArrayList<>();
        for (Path bf : glob(ws, "**/build.gradle*")) {
            if (bf.getParent().equals(ws) || skip(bf)) continue;
            final String src = read(bf);
            boolean real = (src.contains("org.springframework.boot") || src.contains("spring-boot"))
                    && Files.isDirectory(bf.getParent().resolve("src/main"));
            if (real) svcs.add(ws.relativize(bf.getParent()).toString());
        }
        out.add(new CheckResult(CheckId.S5, svcs.size() >= 3 ? CheckStatus.PASS : CheckStatus.FAIL,
                svcs.size() + " real services: " + svcs.subList(0, Math.min(5, svcs.size()))));

        // S6: a REAL wrapper (script + properties) in at least one Gradle root
        List<Path> roots = gradleRoots(ws);
        if (roots.isEmpty()) roots = List.of(ws);
        final List<String> found = new ArrayList<>();
        for (Path r : roots) {
            final Path gw = r.resolve("gradlew"), props = r.resolve("gradle/wrapper/gradle-wrapper.properties");
            if (Files.isRegularFile(gw) && fileSize(gw) > 1024 && Files.isRegularFile(props))
                found.add(roots.size() > 1 && !r.equals(ws) ? ws.relativize(r).toString() : ".");
        }
        out.add(new CheckResult(CheckId.S6, !found.isEmpty() ? CheckStatus.PASS : CheckStatus.FAIL,
                found.isEmpty() ? "no real gradlew+properties in any of " + roots.size() + " gradle root(s)" : "wrapper in " + found));

        // S7: a spec file named openapi*/api* that parses as OpenAPI (tolerant key scan)
        final List<Path> specs = new ArrayList<>();
        for (Path p : glob(ws, "**/*.yml")) if (isSpecName(p) && !skip(p)) specs.add(p);
        for (Path p : glob(ws, "**/*.yaml")) if (isSpecName(p) && !skip(p)) specs.add(p);
        for (Path p : glob(ws, "**/*.json")) if (isSpecName(p) && !skip(p)) specs.add(p);
        specs.sort(Comparator.comparingInt((Path p) -> ws.relativize(p).getNameCount()).thenComparing(Path::toString));
        boolean ok7 = false; String det7 = "none found";
        for (Path p : specs.subList(0, Math.min(5, specs.size()))) {
            final String src = read(p);
            if (Pattern.compile("(?m)^\\s*openapi\\s*:", Pattern.CASE_INSENSITIVE).matcher(src).find()
                    && Pattern.compile("(?m)^\\s*paths\\s*:", Pattern.CASE_INSENSITIVE).matcher(src).find()) {
                ok7 = true; det7 = ws.relativize(p) + " (openapi+paths keys)"; break;
            }
            det7 = ws.relativize(p) + ": no openapi/paths";
        }
        out.add(new CheckResult(CheckId.S7, ok7 ? CheckStatus.PASS : CheckStatus.FAIL, det7));

        // S8: react under `dependencies` in some package.json
        boolean ok8 = false; String det8 = "no package.json with react in dependencies";
        for (Path p : glob(ws, "**/package.json")) {
            if (skip(p)) continue;
            final String src = read(p);
            if (Pattern.compile("\"dependencies\"\\s*:\\s*\\{[^}]*\"react\"\\s*:", Pattern.DOTALL).matcher(src).find()) {
                ok8 = true; det8 = ws.relativize(p).toString(); break;
            }
        }
        out.add(new CheckResult(CheckId.S8, ok8 ? CheckStatus.PASS : CheckStatus.FAIL, det8));

        long mig = Stream.concat(glob(ws, "**/db/migration/*.sql").stream(), glob(ws, "**/*changelog*.*").stream())
                .filter(p -> !skip(p) && fileSize(p) > 20).count();
        out.add(new CheckResult(CheckId.S9, mig > 0 ? CheckStatus.PASS : CheckStatus.FAIL, mig + " migration files"));
        return out;
    }

    static boolean isSpecName(final Path p) {
        final String n = p.getFileName().toString().toLowerCase();
        return n.startsWith("openapi") || n.startsWith("api");
    }

    /** the ROOT-most compose file (a per-service compose must not shadow the stack) */
    public static Path findCompose(final Path ws) {
        final List<Path> all = new ArrayList<>();
        for (String pat : List.of("docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml"))
            all.addAll(glob(ws, pat));
        for (String pat : List.of("**/docker-compose.y*ml", "**/compose.y*ml"))
            for (Path p : glob(ws, pat)) if (!p.getParent().equals(ws) && !skip(p)) all.add(p);
        return all.stream().min(Comparator.comparingInt(p -> ws.relativize(p).getNameCount())).orElse(null);
    }

    /** every directory that is a Gradle project root: settings.gradle dirs, else build.gradle dirs with src/ */
    public static List<Path> gradleRoots(final Path ws) {
        final List<Path> out = new ArrayList<>();
        for (String pat : List.of("settings.gradle", "settings.gradle.kts", "**/settings.gradle*", "**/settings.gradle.kts"))
            for (Path s : glob(ws, pat)) if (!skip(s)) out.add(s.getParent());
        if (!out.isEmpty()) {
            // settings.gradle* dirs ARE the roots; drop any root nested inside another returned root
            final List<Path> roots = out.stream().distinct().sorted(Comparator.comparingInt(p -> ws.relativize(p).getNameCount())).toList();
            final List<Path> top = new ArrayList<>();
            for (Path r : roots) if (top.stream().noneMatch(t -> r.startsWith(t))) top.add(r);
            return top;
        }
        // no settings: every build.gradle* dir with src/ counts, INCLUDING the workspace root itself
        for (Path b : glob(ws, "**/build.gradle*"))
            if (!skip(b) && Files.isDirectory(b.getParent().resolve("src"))) out.add(b.getParent());
        return out.stream().distinct().sorted(Comparator.comparingInt(p -> ws.relativize(p).getNameCount())).toList();
    }

    public static List<Path> glob(final Path root, final String pattern) {
        // Java's glob "**/x" does not match a ROOT-level x (Python's recursive glob does) — match both
        final List<PathMatcher> matchers = new ArrayList<>();
        final String full = root.resolve(pattern).toString().replace(root.getFileSystem().getSeparator(), "/");
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + full));
        if (pattern.startsWith("**/"))
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + root.resolve(pattern.substring(3)).toString().replace(root.getFileSystem().getSeparator(), "/")));
        int max = 4096;   // a walked workspace is a small source tree; never loop a runaway node_modules
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).filter(p -> matchers.stream().anyMatch(m -> m.matches(p))).limit(max).sorted().toList();
        } catch (IOException e) { return List.of(); }
    }

    static long fileSize(Path p) { try { return Files.size(p); } catch (IOException e) { return 0; } }
    static String read(Path p) { try { return Files.readString(p); } catch (IOException e) { return ""; } }
}
