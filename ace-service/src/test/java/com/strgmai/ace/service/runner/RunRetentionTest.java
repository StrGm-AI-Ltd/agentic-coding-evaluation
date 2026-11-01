package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.config.BenchProperties;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.strgmai.ace.service.jooq.Tables.RUNS;
import static org.junit.jupiter.api.Assertions.*;

/** #692: binaries (extracted Gradle/other tool distributions, build output) accumulate per run
 *  and dwarf the source/docs a run actually produced. Runs against a real, fresh-per-test SQLite
 *  database (same pattern ImporterServiceTest already uses) and a real temp filesystem - no
 *  mocking either, since the whole point is the real interaction between a DB query and a file
 *  walk. */
class RunRetentionTest {

    private DSLContext dsl() throws Exception {
        final var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + Files.createTempFile("ace-retention-test", ".db") + "?foreign_keys=on");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return DSL.using(ds, SQLDialect.SQLITE);
    }

    private static BenchProperties props(final Path resultsDir, final Path workspaceRoot) {
        return new BenchProperties(null, null, null, null, null, null, null, null, null, null,
                resultsDir.toString(), workspaceRoot.toString(), null, null, null, null, null, null);
    }

    /** a fixture run directory shaped like a real one: a source file (never touched) and a build/
     *  directory holding a fake binary (always stripped unless this run is the kept one). */
    private static void fixtureRun(final Path base, final String runId) throws Exception {
        final Path dir = base.resolve(runId);
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(dir.resolve("src/main/java/com/example/App.java"), "class App {}");
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docs/PROGRESS.md"), "T1 | done | ok");
        Files.createDirectories(dir.resolve("build/libs"));
        Files.writeString(dir.resolve("build/libs/app.jar"), "not a real jar");
        Files.createDirectories(dir.resolve("gradle-8.5/lib"));
        Files.writeString(dir.resolve("gradle-8.5/lib/some.jar"), "extracted distribution");
    }

    private static void insertRun(final DSLContext dsl, final String runId, final String task, final boolean valid, final String importedAt) {
        dsl.insertInto(RUNS, RUNS.RUN_ID, RUNS.RESULTS_DIR, RUNS.TASK, RUNS.POOLABLE, RUNS.ORACLE, RUNS.VALID, RUNS.IMPORTED_AT)
                .values(runId, "/dev/null", task, false, "{}", valid, importedAt)
                .execute();
    }

    private static boolean hasBuildOutput(final Path base, final String runId) {
        return Files.isRegularFile(base.resolve(runId).resolve("build/libs/app.jar"))
                || Files.isDirectory(base.resolve(runId).resolve("gradle-8.5"));
    }

    private static boolean hasSource(final Path base, final String runId) {
        return Files.isRegularFile(base.resolve(runId).resolve("src/main/java/com/example/App.java"))
                && Files.isRegularFile(base.resolve(runId).resolve("docs/PROGRESS.md"));
    }

    @Test
    void keepsBinariesOnlyForTheMostRecentValidRunOfATaskAndStripsTheRest() throws Exception {
        final DSLContext db = dsl();
        final Path results = Files.createTempDirectory("results");
        final Path workspaces = Files.createTempDirectory("workspaces");
        for (final String id : List.of("run-1", "run-2", "run-3")) {
            fixtureRun(results, id);
            fixtureRun(workspaces, id);
        }
        insertRun(db, "run-1", "L2_one_endpoint", true, "2026-10-30T00:00:00Z");
        insertRun(db, "run-2", "L2_one_endpoint", true, "2026-10-31T00:00:00Z");   // the real keeper
        insertRun(db, "run-3", "L2_one_endpoint", true, "2026-10-29T00:00:00Z");

        new RunRetention(db, props(results, workspaces)).pruneStaleBinaries("L2_one_endpoint", "run-3");

        assertFalse(hasBuildOutput(results, "run-1"), "an older run must lose its binaries");
        assertFalse(hasBuildOutput(results, "run-3"), "the just-finished run, not the keeper, must lose its binaries too");
        assertTrue(hasBuildOutput(results, "run-2"), "the most recent valid run must keep its binaries");
        for (final String id : List.of("run-1", "run-2", "run-3")) {
            assertTrue(hasSource(results, id), id + " must keep its source/docs regardless");
            assertTrue(hasSource(workspaces, id), id + " must keep its source/docs regardless (workspace copy)");
        }
    }

    @Test
    void aDifferentTaskSKeeperIsUntouchedByPruningAnotherTask() throws Exception {
        final DSLContext db = dsl();
        final Path results = Files.createTempDirectory("results");
        final Path workspaces = Files.createTempDirectory("workspaces");
        fixtureRun(results, "task-a-run");
        fixtureRun(results, "task-b-run");
        insertRun(db, "task-a-run", "L2_one_endpoint", true, "2026-10-30T00:00:00Z");
        insertRun(db, "task-b-run", "L3p_point_in_time", true, "2026-10-30T00:00:00Z");

        new RunRetention(db, props(results, workspaces)).pruneStaleBinaries("L2_one_endpoint", "task-a-run");

        assertTrue(hasBuildOutput(results, "task-a-run"), "the only (and therefore most recent) run of its own task must be kept");
        assertTrue(hasBuildOutput(results, "task-b-run"), "a different task's own run must never be touched by pruning this task");
    }

    @Test
    void noValidRunYetStripsEveryRunOfThatTask() throws Exception {
        final DSLContext db = dsl();
        final Path results = Files.createTempDirectory("results");
        final Path workspaces = Files.createTempDirectory("workspaces");
        fixtureRun(results, "only-run");
        insertRun(db, "only-run", "L2_one_endpoint", false, "2026-10-30T00:00:00Z");   // never valid

        new RunRetention(db, props(results, workspaces)).pruneStaleBinaries("L2_one_endpoint", "only-run");

        assertFalse(hasBuildOutput(results, "only-run"), "no run of this task is valid yet, so there is no keeper to spare");
        assertTrue(hasSource(results, "only-run"));
    }

    @Test
    void stripBinariesNeverTouchesGitHistory() throws Exception {
        final Path dir = Files.createTempDirectory("git-safe");
        Files.createDirectories(dir.resolve(".git/objects/build"));   // a directory literally named "build" INSIDE .git
        Files.writeString(dir.resolve(".git/objects/build/pack.jar"), "must survive");
        Files.createDirectories(dir.resolve("build"));
        Files.writeString(dir.resolve("build/real.jar"), "must be removed");

        RunRetention.stripBinaries(dir);

        assertTrue(Files.isRegularFile(dir.resolve(".git/objects/build/pack.jar")), "nothing under .git is ever touched");
        assertFalse(Files.exists(dir.resolve("build")), "a real build/ directory outside .git is removed");
    }

    @Test
    void stripBinariesLeavesSourceMarkdownAndConfigFilesAlone() throws Exception {
        final Path dir = Files.createTempDirectory("keep-source");
        Files.writeString(dir.resolve("App.java"), "class App {}");
        Files.writeString(dir.resolve("README.md"), "# docs");
        Files.writeString(dir.resolve("build.gradle"), "plugins {}");
        Files.createDirectories(dir.resolve("build"));
        Files.writeString(dir.resolve("build/output.jar"), "binary");

        RunRetention.stripBinaries(dir);

        assertTrue(Files.isRegularFile(dir.resolve("App.java")));
        assertTrue(Files.isRegularFile(dir.resolve("README.md")));
        assertTrue(Files.isRegularFile(dir.resolve("build.gradle")));
        assertFalse(Files.exists(dir.resolve("build")));
    }
}
