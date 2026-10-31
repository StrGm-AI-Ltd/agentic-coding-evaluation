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

    /** #680: a real wrapper always ships gradle-wrapper.jar alongside gradlew - verify() now
     *  requires it before trusting gradlew at all (see its own javadoc), so every fixture here
     *  creates one too, matching a genuine wrapper's own real shape. */
    private static void fakeGradlew(final Path ws, final String script) throws Exception {
        final Path gradlew = ws.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        assertTrue(gradlew.toFile().setExecutable(true));
        Files.createDirectories(ws.resolve("gradle/wrapper"));
        Files.writeString(ws.resolve("gradle/wrapper/gradle-wrapper.jar"), "not a real jar, existence is all verify() checks");
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

    /** Found live: every real call site (RunBench's own task/wave/FIX/rescue/INTEGRATION verifies)
     *  passes a NESTED logPath (rd/verify/T1.log, say) whose own "verify/" directory is never
     *  created beforehand - Files.newBufferedWriter's CREATE option creates the file, never a
     *  missing parent, so this threw NoSuchFileException on a genuinely fresh run's very first
     *  call, silently degrading to "could not run" instead of ever executing the real test suite.
     *  Every existing test above passes a FLAT logPath (ws.resolve("verify.log")), which never
     *  exercised this at all - pinned here with a nested one so it can't regress unnoticed again. */
    @Test
    void createsItsLogsOwnParentDirectoryInsteadOfSilentlyDegradingToCouldNotRun() throws Exception {
        final Path ws = fixture("nested-log-dir");
        fakeGradlew(ws, writeJunitXmlCommand(2, 0) + "\nexit 0");
        final Path rd = Files.createTempDirectory("results");

        final Map<String, Object> v = VerifyTask.verify(ws, Map.of(), rd.resolve("verify/T1.log"));
        assertEquals(Boolean.TRUE, v.get("green"), "the real test suite must actually run, not silently abort: " + v);
        assertEquals(2, v.get("executed"));
        assertNull(v.get("could_not_run"));
        assertTrue(Files.isRegularFile(rd.resolve("verify/T1.log")), "the log itself must land where the caller asked");
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

    /** #680-followup3, found live (job d6d9868c, T2): "add build dependencies" has nothing to test
     *  yet - a clean `gradle test` run against a project with no test source reports NO-SOURCE,
     *  executed:0, failed:0, exit 0. That must be inconclusive (could-not-run), never a RED - the
     *  class's own javadoc defines FALSE as "tests ran AND failed", which requires executed>0.
     *  Before this fix, exit 0 with executed:0 fell through to green:false purely because nothing
     *  ran - harmless until #680 gave a disagreement a real consequence (retry-then-fail): a task
     *  with nothing to test can never produce a nonzero executed count no matter how many times it
     *  is retried, so this used to exhaust every retry and fail the whole run on a task that did
     *  nothing wrong. */
    @Test
    void couldNotRunWhenNothingExecutedEvenIfTheBuildItselfExitedCleanly() throws Exception {
        final Path ws = fixture("nothing-to-test-yet");
        fakeGradlew(ws, "exit 0");   // a clean build, no test source, no XML written at all

        final Map<String, Object> v = VerifyTask.verify(ws, Map.of(), ws.resolve("verify.log"));
        assertNull(v.get("green"), "nothing executed must be inconclusive regardless of exit code: " + v);
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

    /** #680: a `gradlew` with no `gradle/wrapper/gradle-wrapper.jar` sitting next to it cannot be a
     *  real wrapper (one cannot run at all without that jar) - found live as a model-written fake
     *  stub that always printed a canned version string and exited 0 for ANY argument, which this
     *  used to trust outright. Proves the untrusted script is never invoked at all: whatever
     *  verify() actually runs instead (the bare "gradle" fallback, resolved via the REAL process
     *  PATH - ProcessBuilder resolves a bare command name against its OWN launching environment,
     *  not the target env map, so this deliberately does not try to control or assert what that
     *  fallback finds) never gets a chance to report the untrusted script's own lie (99 tests). */
    @Test
    void distrustsAGradlewWithNoWrapperJarAndNeverInvokesIt() throws Exception {
        final Path ws = fixture("no-wrapper-jar");
        final Path gradlew = ws.resolve("gradlew");
        // if this ran, it would lie: claims 99 tests passed; no gradle-wrapper.jar written alongside it
        Files.writeString(gradlew, "#!/bin/sh\n" + writeJunitXmlCommand(99, 0) + "\nexit 0\n");
        assertTrue(gradlew.toFile().setExecutable(true));

        final Map<String, Object> v = VerifyTask.verify(ws, Map.of(), ws.resolve("verify.log"));

        assertTrue(v.get("executed") == null || ((int) v.get("executed")) != 99,
                "the untrusted gradlew must never run: " + v);
        final String log = Files.readString(ws.resolve("verify.log"));
        assertTrue(log.contains("cmd=gradle "), "verify() must fall back to the bare \"gradle\" command, not the untrusted gradlew path: " + log);
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

    /** #680-followup2, found live (job be96af63, T1): claimed "done" with NO gradle project found
     *  at all is direct, structural proof the claim is false - not the same inconclusive signal
     *  R4 C-2 protects (a real project existing but something else stopping the test run). Must be
     *  a real disagreement (false), not null, or the retry-then-fail mechanism (#680) never sees it. */
    @org.junit.jupiter.api.Test
    void doneVerifiedIsFalseNotNullWhenClaimedDoneButNoGradleProjectExistsAtAll() {
        final Map<String, Object> noProject = Map.of("ran", false, "reason", "no gradle project");
        assertEquals(Boolean.FALSE, VerifyTask.doneVerified("done", noProject));
    }

    /** the genuine inconclusive case R4 C-2 IS for: a real project exists (ran:true) but the build
     *  itself failed before any test executed - a toolchain/environment issue, never the agent's
     *  own RED. Must stay null here, unchanged by #680-followup2's own narrower fix above. */
    @org.junit.jupiter.api.Test
    void doneVerifiedStaysNullForAGenuineCouldNotRunWithARealProject() {
        final Map<String, Object> couldNotRun = new java.util.HashMap<>();
        couldNotRun.put("ran", true); couldNotRun.put("green", null);
        couldNotRun.put("executed", 0); couldNotRun.put("failed", 0); couldNotRun.put("rc", java.util.List.of(1));
        assertNull(VerifyTask.doneVerified("done", couldNotRun));
    }

    @org.junit.jupiter.api.Test
    void doneVerifiedIsTrueForAGenuineGreenPass() {
        final Map<String, Object> green = Map.of("ran", true, "green", true, "executed", 3, "failed", 0, "rc", java.util.List.of(0));
        assertEquals(Boolean.TRUE, VerifyTask.doneVerified("done", green));
    }

    @org.junit.jupiter.api.Test
    void doneVerifiedIsFalseWhenNeverClaimedDoneRegardlessOfVerification() {
        final Map<String, Object> green = Map.of("ran", true, "green", true, "executed", 3, "failed", 0, "rc", java.util.List.of(0));
        assertEquals(Boolean.FALSE, VerifyTask.doneVerified("partial", green));
    }
}
