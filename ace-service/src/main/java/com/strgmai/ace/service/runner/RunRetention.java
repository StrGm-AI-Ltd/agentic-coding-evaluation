package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.config.BenchProperties;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.strgmai.ace.service.jooq.Tables.RUNS;

/** #692: binaries (extracted Gradle/other tool distributions, build output, downloaded archives)
 *  accumulate per run and dwarf the source/docs the run actually produced - found live, a 25GB
 *  ace-data volume with less than 2GB of it genuinely source code. Every run keeps its own
 *  source/docs forever (the git-tracked workspace, PROGRESS.md, handoff notes, session
 *  transcripts - the real record of what happened); only the MOST RECENT valid run of each
 *  distinct task keeps its binaries too, so there is always at least one fully reproducible
 *  result per task without every earlier attempt's own Gradle distribution silently filling the
 *  disk. Called from WorkerService right after a job reaches a genuinely terminal state
 *  (succeeded or failed - never a cancel/pause, which may still resume and need its own caches). */
@Component
public class RunRetention {
    private static final Logger log = LoggerFactory.getLogger(RunRetention.class);
    private final DSLContext dsl;
    private final BenchProperties props;

    public RunRetention(final DSLContext dsl, final BenchProperties props) {
        this.dsl = dsl;
        this.props = props;
    }

    private static final Set<String> BINARY_DIR_NAMES = Set.of("build", ".gradle", "node_modules");
    // extracted tool distributions an agent downloads by hand (found live: gradle-8.5/,
    // flyway-10.15.0/) - never matched inside .git, see stripBinaries()'s own guard.
    private static final Pattern EXTRACTED_DIST_DIR = Pattern.compile("^(gradle|flyway)-[0-9].*");
    private static final Set<String> BINARY_FILE_SUFFIXES = Set.of(".zip", ".tar.gz", ".tgz", ".jar");

    /** `justFinishedRunId` gets stripped directly (by path, not a DB lookup) whenever it is not
     *  the task's own current keeper - covers a failed or invalid run that was never imported into
     *  `runs` at all, which the DB-driven sweep below would otherwise never see. Safe to call
     *  after every single terminal job: finding the keeper and stripping everyone else is
     *  idempotent, an already-stripped run just matches nothing the second time. */
    public void pruneStaleBinaries(final String task, final String justFinishedRunId) {
        if (task == null || task.isBlank()) return;
        try {
            final String keep = dsl.select(RUNS.RUN_ID).from(RUNS)
                    .where(RUNS.TASK.eq(task)).and(RUNS.VALID.isTrue())
                    .orderBy(RUNS.IMPORTED_AT.desc()).limit(1).fetchOneInto(String.class);
            if (justFinishedRunId != null && !justFinishedRunId.equals(keep)) stripRun(justFinishedRunId);
            final List<String> others = keep == null
                    ? dsl.select(RUNS.RUN_ID).from(RUNS).where(RUNS.TASK.eq(task)).fetchInto(String.class)
                    : dsl.select(RUNS.RUN_ID).from(RUNS).where(RUNS.TASK.eq(task)).and(RUNS.RUN_ID.ne(keep)).fetchInto(String.class);
            for (final String runId : others) stripRun(runId);
        } catch (final Exception e) {
            log.warn("could not prune stale binaries for task {}: {}", task, e.toString());
        }
    }

    private void stripRun(final String runId) {
        stripBinaries(Path.of(props.resultsDir(), runId));
        stripBinaries(Path.of(props.workspaceRoot(), runId));
    }

    /** removes build output, dependency/tool caches, and downloaded or extracted binary
     *  distributions under `root` - never touches .git (an agent that committed a huge binary into
     *  its own history needs real history-rewriting to actually reclaim that space, not a plain
     *  file walk - a separate, harder problem, deliberately not attempted here) or any
     *  source/markdown/config file. */
    static void stripBinaries(final Path root) {
        if (!Files.isDirectory(root)) return;
        final List<Path> dirs = new ArrayList<>();
        final List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.forEach(p -> {
                if (p.equals(root) || isUnderGit(root, p)) return;
                final String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    if (BINARY_DIR_NAMES.contains(name) || EXTRACTED_DIST_DIR.matcher(name).matches()) dirs.add(p);
                } else if (BINARY_FILE_SUFFIXES.stream().anyMatch(name::endsWith)) {
                    files.add(p);
                }
            });
        } catch (final IOException e) {
            log.warn("could not walk {} to strip binaries: {}", root, e.toString());
            return;
        }
        for (final Path f : files) { try { Files.deleteIfExists(f); } catch (final IOException e) { log.debug("could not delete {}: {}", f, e.toString()); } }
        for (final Path d : dirs) RunBench.deleteRecursive(d);
    }

    private static boolean isUnderGit(final Path root, final Path p) {
        return root.relativize(p).toString().contains(".git");
    }
}
