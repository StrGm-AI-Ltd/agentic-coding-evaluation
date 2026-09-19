package com.agentbench.runner;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Ports of run_bench.py's process-level machinery: the single-run lock (two runs would share the
 *  model server and fight over Docker), git workspace snapshots (the commit SHAs live OUTSIDE the
 *  workspace; the bundle is P3's evidence), the kill tree (SIGTERM the process group and every
 *  descendant, then SIGKILL survivors) and the scrubbed environment (PATH/LANG/TMPDIR only, no
 *  operator tokens, a pinned JAVA_HOME first on PATH). */
public final class RunBenchSupport {
    private RunBenchSupport() {}

    /** one benchmark run at a time on this machine */
    public static FileChannel acquireLock(Path lockPath) {
        try {
            Files.createDirectories(lockPath.toAbsolutePath().getParent());
            FileChannel ch = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (ch.tryLock() == null) {
                ch.close();
                throw new IllegalStateException("another run holds " + lockPath + "; refusing to start a concurrent run");
            }
            return ch;
        } catch (IOException e) { throw new IllegalStateException("cannot lock " + lockPath + ": " + e, e); }
    }

    public static Map<String, String> scrubbedEnv(String home, String runId, String javaHome) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String k : List.of("PATH", "LANG", "LC_ALL", "TERM", "TMPDIR", "SHELL")) {   // PATH/LANG/TMPDIR only - no operator tokens
            String v = System.getenv(k);
            if (v != null) env.put(k, v);
        }
        env.put("HOME", home);
        env.put("USER", "bench");
        env.put("CI", "1");
        env.put("NO_COLOR", "1");
        env.put("TERM", "dumb");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("AB_RUN_ID", runId);
        if (javaHome != null && !javaHome.isBlank()) {   // a PINNED JAVA_HOME first on PATH (R4 C-2)
            env.put("JAVA_HOME", javaHome);
            env.put("PATH", Paths.get(javaHome).resolve("bin") + ":" + env.getOrDefault("PATH", ""));
        }
        env.put("PATH", Paths.get(home, "bin") + ":" + env.getOrDefault("PATH", ""));   // the docker shim first (R7)
        env.put("AB_DOCKER_LOG", Paths.get(home, "docker-calls.log").toString());
        return env;
    }

    /** commit + tag; returns the commit SHA. Build outputs never enter a snapshot. */
    public static String snapshot(Path ws, String tag) throws IOException, InterruptedException {
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

    public static String gitOut(Path ws, String... args) throws IOException, InterruptedException {
        Process p = git(ws, args);
        return new String(p.getInputStream().readAllBytes()).strip();
    }

    static void runGit(Path ws, String... args) throws IOException, InterruptedException {
        Process p = git(ws, args);
        if (p.waitFor() != 0) throw new IOException("git " + args[0] + " failed: " + new String(p.getErrorStream().readAllBytes()));
    }

    private static Process git(Path ws, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", ws.toString()));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).start();
    }

    /** port of kill_tree: SIGTERM every descendant, then SIGKILL survivors (Java's ProcessHandle
     *  replaces the ppid walk; processes marked with AB_RUN_ID are swept too). */
    public static List<Long> killTree(ProcessHandle root, String runId) {
        Set<ProcessHandle> targets = new LinkedHashSet<>();
        root.descendants().forEach(targets::add);
        targets.add(root);
        if (runId != null)
            ProcessHandle.allProcesses().forEach(ph -> ph.info().commandLine()
                    .ifPresent(cl -> { if (cl.contains("AB_RUN_ID=" + runId)) targets.add(ph); }));
        targets.remove(ProcessHandle.current());
        for (int pass = 0; pass < 2; pass++) {
            for (ProcessHandle t : targets) { try { if (pass == 0) t.destroy(); else t.destroyForcibly(); } catch (Exception ignore) {} }
            long deadline = System.currentTimeMillis() + 20000;
            List<Long> survivors = new ArrayList<>();
            while (System.currentTimeMillis() < deadline) {
                survivors.clear();
                for (ProcessHandle t : targets) if (t.isAlive()) survivors.add(t.pid());
                if (survivors.isEmpty()) return List.of();
                try { TimeUnit.MILLISECONDS.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return survivors; }
            }
        }
        return targets.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
    }

    public static Optional<String> javaHome() {
        String jh = System.getenv("AB_JAVA_HOME");
        if (jh != null && Files.isRegularFile(Paths.get(jh, "bin/java"))) return Optional.of(jh);
        return Optional.empty();
    }

    public static String nowIso() { return Instant.now().toString(); }
    public static long secondsSince(Instant t0) { return Duration.between(t0, Instant.now()).getSeconds(); }
}
