package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.oracle.checks.StructureChecks;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Port of run_bench.py's verify_task (M-1): the harness runs the tests itself after every task —
 *  the truthful record of where the code stands, independent of what the model wrote in
 *  PROGRESS.md. Uses the agent's wrapper when present, else the host gradle; no Docker needed.
 *  THREE-valued: green TRUE (tests ran, none failed), FALSE (tests ran and failed), None = COULD
 *  NOT RUN (exit != 0 with 0 tests executed: a toolchain/environment failure, never the agent's
 *  RED — R4 C-2). Uses the SAME gradle roots the oracle builds (R5 C-24). */
public final class VerifyTask {
    private VerifyTask() {}

    // compiled ONCE: firstError runs this per output line, and Pattern.compile per line re-parses the regex every time
    private static final Pattern ERROR_PATTERN = Pattern.compile("error:|FAILED|What went wrong|cannot find symbol|requires JVM");

    public static Map<String, Object> verify(final Path ws, final Map<String, Object> cfg, final Path logPath) {
        final List<Path> roots = StructureChecks.gradleRoots(ws);
        if (roots.isEmpty()) return Map.of("ran", false, "reason", "no gradle project");
        final int timeoutSec = cfg.get("verify_timeout_sec") instanceof Number n ? n.intValue() : 600;
        for (Path x : StructureChecks.glob(ws, "**/build/test-results/**/*.xml")) { try { Files.deleteIfExists(x); } catch (IOException ignore) {} }
        final Map<String, Object> res = new LinkedHashMap<>();
        res.put("ran", true);
        res.put("roots", new ArrayList<String>());
        res.put("executed", 0);
        res.put("failed", 0);
        res.put("rc", new ArrayList<Integer>());
        res.put("seconds", 0.0);
        res.put("first_error", "");
        final long t0 = System.nanoTime();
        try {
            java.io.Writer log = null;
            try {
                log = logPath == null ? null : Files.newBufferedWriter(logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                // one factory for the whole glob: newInstance() per XML file re-ran security/feature setup every time
                final var docFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                for (Path r : roots) {
                    String cmd0 = Files.isRegularFile(r.resolve("gradlew")) && r.resolve("gradlew").toFile().canExecute()
                            ? r.resolve("gradlew").toString() : "gradle";
                    List<String> cmd = List.of(cmd0, "test", "-q", "--console=plain", "--warning-mode=none", "--no-daemon");
                    int rc;
                    String out;
                    try {
                        Map<String, String> env = new java.util.LinkedHashMap<>(System.getenv());   // drained concurrently: a full test suite overflows the pipe
                        env.put("CI", "1");
                        env.put("GRADLE_OPTS", "-Dorg.gradle.daemon=false");
                        if (cfg.get("java_home") != null) { env.put("JAVA_HOME", String.valueOf(cfg.get("java_home"))); env.put("PATH", cfg.get("java_home") + "/bin:" + env.getOrDefault("PATH", "")); }
                        final var r2 = com.strgmai.ace.service.docker.DockerService.proc(timeoutSec, r, env, cmd.toArray(String[]::new));
                        rc = r2.rc();
                        out = r2.out() + r2.err();
                    } catch (Exception e) { rc = 127; out = String.valueOf(e); }
                    ((List<Integer>) res.get("rc")).add(rc);
                    ((List<String>) res.get("roots")).add(ws.relativize(r).toString());
                    if (log != null) log.write("### root=" + ws.relativize(r) + " cmd=" + String.join(" ", cmd) + " rc=" + rc + "\n" + out + "\n");
                    if (rc != 0 && String.valueOf(res.get("first_error")).isEmpty())
                        res.put("first_error", firstError(out));
                    for (Path x : StructureChecks.glob(r, "**/build/test-results/**/*.xml")) {
                        try {
                            final var doc = docFactory.newDocumentBuilder().parse(x.toFile());
                            final var root = doc.getDocumentElement();
                            if (root.getTagName().equals("testsuite")) {
                                res.merge("executed", Integer.parseInt(root.getAttribute("tests").isEmpty() ? "0" : root.getAttribute("tests")), (a, b) -> (int) a + (int) b);
                                res.merge("failed", Integer.parseInt(root.getAttribute("failures").isEmpty() ? "0" : root.getAttribute("failures"))
                                                + Integer.parseInt(root.getAttribute("errors").isEmpty() ? "0" : root.getAttribute("errors")), (a, b) -> (int) a + (int) b);
                            }
                        } catch (Exception ignore) {}
                        }
                    }
            } finally {
                // an IOException mid-loop must still reach the close, or the file descriptor leaks
                if (log != null) { try { log.flush(); log.close(); } catch (IOException ignore) {} }
            }
        } catch (IOException ignore) {}
        res.put("seconds", Math.round((System.nanoTime() - t0) / 1e8) / 10.0);
        final List<Integer> rcs = (List<Integer>) res.get("rc");
        if (rcs.stream().anyMatch(rc -> rc != 0) && ((int) res.get("executed")) == 0) {
            res.put("green", null);   // inconclusive: nothing was executed and the build itself failed
            res.put("could_not_run", true);
        } else {
            res.put("green", rcs.stream().allMatch(rc -> rc == 0) && ((int) res.get("executed")) > 0 && ((int) res.get("failed")) == 0);
        }
        return res;
    }

    static String firstError(final String out) {
        for (String line : out.split("\n")) {
            final String l = line.strip();
            if (ERROR_PATTERN.matcher(l).find())
                return l.substring(0, Math.min(160, l.length()));
        }
        final String[] lines = Arrays.stream(out.split("\n")).filter(x -> !x.isBlank()).toArray(String[]::new);
        return lines.length == 0 ? "" : lines[lines.length - 1].substring(0, Math.min(160, lines[lines.length - 1].length()));
    }

    public static String verifyText(final Map<String, Object> v) {
        if (v == null || !Boolean.TRUE.equals(v.get("ran"))) return "(no Gradle project to verify yet)";
        if (v.get("green") == null)
            return "`gradle test` run by the harness could NOT run (build failed before any test executed; rc=" + v.get("rc") + ")"
                    + (String.valueOf(v.get("first_error")).isEmpty() ? "" : "; first error: " + v.get("first_error")) + " — fix the build first.";
        return "`gradle test` run by the harness: " + (Boolean.TRUE.equals(v.get("green")) ? "GREEN" : "RED") + " — " + v.get("executed")
                + " tests, " + v.get("failed") + " failed, rc=" + v.get("rc")
                + (String.valueOf(v.get("first_error")).isEmpty() ? "" : "; first error: " + v.get("first_error"));
    }

    /** True = claimed done AND harness green; False = claimed done and harness red, or not claimed
     *  done; None = claimed done but the verification could not run (inconclusive, R4 C-2). */
    public static Boolean doneVerified(final String reported, final Map<String, Object> v) {
        if (!"done".equals(reported)) return false;
        return verdict(v);
    }

    public static Boolean verdict(final Map<String, Object> v) {
        if (v == null || !Boolean.TRUE.equals(v.get("ran"))) return null;
        final Object g = v.get("green");
        final List<Integer> rcs = v.get("rc") instanceof List<?> l ? (List<Integer>) l : List.of(1);
        final int executed = v.get("executed") instanceof Number n ? n.intValue() : 0;
        if (g == null || (Boolean.FALSE.equals(g) && executed == 0 && rcs.stream().anyMatch(rc -> rc != 0))) return null;
        return (Boolean) g;
    }
}
