package com.agentbench.runner;

import com.agentbench.agent.ReferenceAgent;
import com.agentbench.config.BenchProperties;
import com.agentbench.oracle.RunOracle;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** The bug behind job #14 (and every other real task) failing with
 *  "java.nio.file.NoSuchFileException: .../task/PROMPT.md": the port read task/PROMPT.md as a
 *  filesystem path relative to the repo root, a file that was never vendored into this repo at all
 *  (nor would a single generic file have been correct - run_bench.py prefers each task's OWN prompt,
 *  tasks/&lt;task&gt;/PROMPT.md, first). Fixed by vendoring the prompts as classpath resources
 *  (matching how tasks/ladder.json already works) and preferring the task-specific one. */
class RunBenchTest {

    private static RunBench runBench() {
        return new RunBench(mock(BenchProperties.class), mock(ReferenceAgent.class), mock(RecordingProxyFactory.class),
                mock(RunOracle.class), mock(Reviews.class), mock(ContextProbe.class));
    }

    @Test
    void promptResourcePrefersTheTaskSOwnPrompt() throws Exception {
        try (InputStream in = runBench().promptResource("L3p_point_in_time")) {
            assertNotNull(in, "tasks/L3p_point_in_time/PROMPT.md must be vendored as a resource");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
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
                String taskText = new String(perTask.readAllBytes(), StandardCharsets.UTF_8);
                String genericText = new String(generic.readAllBytes(), StandardCharsets.UTF_8);
                assertNotEquals(genericText, taskText, task + " must not silently fall back to the generic prompt");
            }
        }
    }
}
