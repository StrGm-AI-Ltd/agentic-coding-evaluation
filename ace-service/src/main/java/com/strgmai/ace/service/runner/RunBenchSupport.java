package com.strgmai.ace.service.runner;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Ports of run_bench.py's process-level machinery: the single-run lock (two runs would share the
 *  model server and fight over Docker), git workspace snapshots (the commit SHAs live OUTSIDE the
 *  workspace; the bundle is P3's evidence) and the scrubbed environment (PATH/LANG/TMPDIR only, no
 *  operator tokens, a pinned JAVA_HOME first on PATH). run_bench.py's kill_tree was deliberately NOT
 *  ported: Java's ProcessHandle.Info exposes only command()/commandLine()/arguments(), never the
 *  process environment, so its AB_RUN_ID sweep could never match; descendants() covers the process tree. */
public final class RunBenchSupport {
    private RunBenchSupport() {}

    /** one benchmark run at a time on this machine */
    public static FileChannel acquireLock(final Path lockPath) {
        try {
            Files.createDirectories(lockPath.toAbsolutePath().getParent());
            final FileChannel ch = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (ch.tryLock() == null) {
                ch.close();
                throw new IllegalStateException("another run holds " + lockPath + "; refusing to start a concurrent run");
            }
            return ch;
        } catch (IOException e) { throw new IllegalStateException("cannot lock " + lockPath + ": " + e, e); }
    }

    public static Map<String, String> scrubbedEnv(final String home, final String runId, final String javaHome) {
        final Map<String, String> env = new LinkedHashMap<>();
        for (String k : List.of("PATH", "LANG", "LC_ALL", "TERM", "TMPDIR", "SHELL")) {   // PATH/LANG/TMPDIR only - no operator tokens
            final String v = System.getenv(k);
            if (v != null) env.put(k, v);
        }
        env.put("HOME", home);
        env.put("USER", "bench");
        env.put("CI", "1");
        env.put("NO_COLOR", "1");
        env.put("TERM", "dumb");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("ACE_RUN_ID", runId);
        if (javaHome != null && !javaHome.isBlank()) {   // a PINNED JAVA_HOME first on PATH (R4 C-2)
            env.put("JAVA_HOME", javaHome);
            env.put("PATH", Paths.get(javaHome).resolve("bin") + ":" + env.getOrDefault("PATH", ""));
        }
        env.put("PATH", Paths.get(home, "bin") + ":" + env.getOrDefault("PATH", ""));   // the docker shim first (R7)
        env.put("ACE_DOCKER_LOG", Paths.get(home, "docker-calls.log").toString());
        return env;
    }

    /** commit + tag; returns the commit SHA. Build outputs never enter a snapshot. */
    public static String snapshot(final Path ws, final String tag) throws IOException, InterruptedException {
        if (!Files.exists(ws.resolve(".git"))) {   // a worktree has a .git FILE pointing at the main repo: it is already initialised
            runGit(ws, "init", "-q");
            runGit(ws, "config", "user.email", "bench@local");
            runGit(ws, "config", "user.name", "bench");
            Files.createDirectories(ws.resolve(".git/info"));
            Files.writeString(ws.resolve(".git/info/exclude"),
                    "build/\n.gradle/\n.gradle-cache/\ntarget/\nnode_modules/\nout/\ndist/\n.idea/\n");
            Files.writeString(ws.resolve(".git/info/attributes"), "docs/PROGRESS.md merge=union\nhandoff/*.md merge=union\n");
        }
        runGit(ws, "add", "-A");
        runGit(ws, "commit", "-q", "--allow-empty", "-m", tag);
        runGit(ws, "tag", "-f", tag);
        return gitOut(ws, "rev-parse", "HEAD");
    }

    public static String gitOut(final Path ws, final String... args) throws IOException, InterruptedException {
        final Process p = git(ws, args);
        final var out = new String(p.getInputStream().readAllBytes()).strip();
        if (p.waitFor() != 0) throw new IOException("git " + args[0] + " failed: " + new String(p.getErrorStream().readAllBytes()));   // like runGit, don't return partial output on failure
        return out;
    }

    static void runGit(final Path ws, final String... args) throws IOException, InterruptedException {
        final Process p = git(ws, args);
        if (p.waitFor() != 0) throw new IOException("git " + args[0] + " failed: " + new String(p.getErrorStream().readAllBytes()));
    }

    private static Process git(final Path ws, final String... args) throws IOException {
        final List<String> cmd = new ArrayList<>(List.of("git", "-C", ws.toString()));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).start();
    }

    public static Optional<String> javaHome() {
        final String jh = System.getenv("AB_JAVA_HOME");
        if (jh != null && Files.isRegularFile(Paths.get(jh, "bin/java"))) return Optional.of(jh);
        return Optional.empty();
    }

    public static String nowIso() { return Instant.now().toString(); }
    public static long secondsSince(Instant t0) { return Duration.between(t0, Instant.now()).getSeconds(); }
}
