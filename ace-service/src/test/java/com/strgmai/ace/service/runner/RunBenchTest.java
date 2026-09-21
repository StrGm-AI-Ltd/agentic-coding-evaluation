package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.oracle.RunOracle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** The bug behind job #14 (and every other real task) failing with
 *  "java.nio.file.NoSuchFileException: .../task/PROMPT.md": the port read task/PROMPT.md as a
 *  filesystem path relative to the repo root, a file that was never vendored into this repo at all
 *  (nor would a single generic file have been correct - run_bench.py prefers each task's OWN prompt,
 *  tasks/&lt;task&gt;/PROMPT.md, first). Fixed by vendoring the prompts as classpath resources
 *  (matching how tasks/ladder.json already works) and preferring the task-specific one. */
class RunBenchTest {

    // the temp workspace dir(s) leak across runs - track and delete them recursively
        private final Set<Path> temps = new HashSet<>();

    private Path track(Path p) { temps.add(p); return p; }

    @AfterEach
    void cleanUp() throws IOException {
        for (Path p : temps)
            if (p != null && Files.exists(p)) {
                try (var s = Files.walk(p)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignore) {} });
                }
            }
        temps.clear();
    }


    private static RunBench runBench() {
        return new RunBench(mock(BenchProperties.class), mock(ReferenceAgent.class), mock(RecordingProxyFactory.class),
                mock(RunOracle.class), mock(Reviews.class), mock(ContextProbe.class));
    }

    @Test
    void promptResourcePrefersTheTaskSOwnPrompt() throws Exception {
        try (InputStream in = runBench().promptResource("L3p_point_in_time")) {
            assertNotNull(in, "tasks/L3p_point_in_time/PROMPT.md must be vendored as a resource");
            final var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("point-in-time") || text.contains("point in time") || text.length() > 100,
                    "a real, task-specific prompt, not empty: " + text.substring(0, Math.min(200, text.length())));
        }
    }

    @Test
    void promptResourceFallsBackToTheGenericDefaultForAnUnknownTask() throws Exception {
        try (InputStream in = runBench().promptResource("no-such-task")) {
            assertNotNull(in, "tasks/PROMPT.md (the generic default) must be vendored as a resource");
        }
    }

    /** Job #26's failure, hidden behind #14's until that one was fixed: Files.copy's TARGET
     *  directory (ws/task) must exist too, not just its parent (ws) -
     *  Files.createDirectories(ws.resolve("task").getParent()) created only ws. */
    @Test
    void setUpTaskPromptCreatesTheTaskDirectoryItselfNotJustItsParent() throws Exception {
        Path ws = track(Files.createTempDirectory("ws"));   // a bare, empty workspace dir - nothing pre-created under it

        final String text = runBench().setUpTaskPrompt(ws, "L3p_point_in_time");

        assertTrue(Files.isRegularFile(ws.resolve("task/PROMPT.md")), "task/PROMPT.md must exist under the workspace");
        assertFalse(text.isBlank());
    }

    @Test
    void everyLadderTaskWithAKnownPromptResolvesToItsOwnFile() throws Exception {
        // every rung except L7_full_platform has its own PROMPT.md in the Python original (verified
        // against ~/Documents/repo/agentbench-trading-service/tasks/*/PROMPT.md) - each must resolve
        // to ITS OWN content here too, not silently share the generic default
        for (String task : new String[]{"L1_migration_entity", "L2_one_endpoint", "L3_point_in_time",
                "L3p_point_in_time", "L4_state_machine", "L5_second_service", "L6_compose_health"}) {
            try (InputStream perTask = runBench().promptResource(task);
                 InputStream generic = RunBenchTest.class.getResourceAsStream("/tasks/PROMPT.md")) {
                assertNotNull(perTask, task + " must resolve to a vendored prompt");
                assertNotNull(generic, "the generic tasks/PROMPT.md must be vendored");   // a missing resource is a clear message, not an NPE
                final var taskText = new String(perTask.readAllBytes(), StandardCharsets.UTF_8);
                final var genericText = new String(generic.readAllBytes(), StandardCharsets.UTF_8);
                assertNotEquals(genericText, taskText, task + " must not silently fall back to the generic prompt");
            }
        }
    }
}
