package com.strgmai.ace.service.runner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VerifyTask.verify() against a fake "gradlew" (an executable shell script standing in for the
 *  real wrapper VerifyTask.verify() would otherwise invoke) - fast, deterministic, and needs no
 *  real Gradle installation. Each fixture controls exactly what the "test run" produces: a JUnit
 *  XML result file (parsed from **\/build/test-results/**\/*.xml, same as the real check), and an
 *  exit code, which together drive green/red/could-not-run exactly the way a real `gradle test`
 *  invocation's own exit code + XML output would. */
class VerifyTaskTest {

    private static Path fixture(final String name) throws Exception {
        final Path ws = Files.createTempDirectory("verify-" + name);
        Files.writeString(ws.resolve("settings.gradle"), "");   // the one thing StructureChecks.gradleRoots() needs to find this as a root
        return ws;
    }

    private static void fakeGradlew(final Path ws, final String script) throws Exception {
        final Path gradlew = ws.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        assertTrue(gradlew.toFile().setExecutable(true));
    }

    /** verify()'s own very first step deletes any PRE-EXISTING XML matching this glob (stale-result
     *  cleanup) - so the XML must be written by the fake script ITSELF, during its own execution,
     *  not seeded into the fixture beforehand or this cleanup step would just delete it first. */
    private static String writeJunitXmlCommand(final int tests, final int failures) {
        return "mkdir -p build/test-results/test && printf '<testsuite tests=\"" + tests + "\" failures=\""
                + failures + "\" errors=\"0\"></testsuite>' > build/test-results/test/TEST-Fake.xml";
    }

    @Test
    void greenWhenTestsExecutedNoneFailedAndExitCodeIsZero() throws Exception {
        final Path ws = fixture("green");
        fakeGradlew(ws, writeJunitXmlCommand(3, 0) + "\nexit 0");

        final Map<String, Object> v = VerifyTask.verify(ws, Map.of(), ws.resolve("verify.log"));
        assertEquals(Boolean.TRUE, v.get("green"));
        assertEquals(3, v.get("executed"));
        assertEquals(0, v.get("failed"));
        assertNull(v.get("could_not_run"));
    }

    @Test
    void redWhenTestsExecutedAndAtLeastOneFailed() throws Exception {
        final Path ws = fixture("red");
        fakeGradlew(ws, writeJunitXmlCommand(3, 1) + "\nexit 1");   // gradle itself exits non-zero on a test failure too

        final Map<String, Object> v = VerifyTask.verify(ws, Map.of(), ws.resolve("verify.log"));
        assertEquals(Boolean.FALSE, v.get("green"));
        assertEquals(3, v.get("executed"));
        assertEquals(1, v.get("failed"));
        assertNull(v.get("could_not_run"));
    }

    /** rc != 0 with ZERO tests executed - a toolchain/environment failure, never the agent's own
     *  RED (the class's own three-valued contract, R4 C-2). */
    @Test
    void couldNotRunWhenTheBuildFailsBeforeAnyTestExecutes() throws Exception {
        final Path ws = fixture("could-not-run");
        fakeGradlew(ws, "echo 'What went wrong: compile error' >&2; exit 1");   // no XML written at all

        final Map<String, Object> v = VerifyTask.verify(ws, Map.of(), ws.resolve("verify.log"));
        assertNull(v.get("green"));
        assertEquals(Boolean.TRUE, v.get("could_not_run"));
        assertEquals(0, v.get("executed"));
    }

    /** #293, found live: this used to start from System.getenv() - the real operator environment
     *  of the ace-service JVM itself - instead of the run's own scrubbed "_agent_env", the same
     *  env every other piece of agent-controlled code execution in this harness already uses. */
    @Test
    void usesTheConfiguredAgentEnvAsTheSubprocessEnvironmentNotTheRawOperatorOne() throws Exception {
        final Path ws = fixture("env");
        fakeGradlew(ws, "echo \"SAW=$SAW_SENTINEL\"; exit 0");

        VerifyTask.verify(ws, Map.of("_agent_env", Map.of("SAW_SENTINEL", "from-scrubbed-env")), ws.resolve("verify.log"));

        final String log = Files.readString(ws.resolve("verify.log"));
        assertTrue(log.contains("SAW=from-scrubbed-env"), "the configured _agent_env's own variable must reach the subprocess: " + log);
    }

    @Test
    void fallsBackToAnEmptyEnvironmentRatherThanTheRawOperatorOneWhenNoAgentEnvIsConfigured() throws Exception {
        final Path ws = fixture("no-agent-env");
        fakeGradlew(ws, "echo \"HOME_WAS=$HOME\"; exit 0");   // a bare unit-test context: _agent_env was never set

        VerifyTask.verify(ws, Map.of(), ws.resolve("verify.log"));

        final String log = Files.readString(ws.resolve("verify.log"));
        assertTrue(log.contains("HOME_WAS=\n") || log.contains("HOME_WAS=\r\n"),
                "with no _agent_env configured, the fallback must be an empty map (matching ReferenceAgent.toolEnv()'s own null-context convention), never a copy of System.getenv(): " + log);
    }
}
