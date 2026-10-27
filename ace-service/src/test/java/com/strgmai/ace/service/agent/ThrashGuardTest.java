package com.strgmai.ace.service.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** #326: the cheap, deterministic, same-turn circuit breaker for literal tool-call repetition -
 *  pure/testable against real-shape fixtures, same convention CompactionStrategiesTest already
 *  follows for its own loop-mechanic state machine. */
class ThrashGuardTest {

    private static final Map<String, Object> ARGS = Map.of("path", "App.java");

    @Test
    void firesExactlyOnceOnceTheStreakReachesThreshold() {
        final var state = new ThrashGuard.State();
        assertNull(ThrashGuard.check("read", "{\"path\":\"App.java\"}", ARGS, state), "1st: not a streak yet");
        assertNull(ThrashGuard.check("read", "{\"path\":\"App.java\"}", ARGS, state), "2nd: still short of THRESHOLD");
        assertNotNull(ThrashGuard.check("read", "{\"path\":\"App.java\"}", ARGS, state), "3rd: THRESHOLD reached");
        assertNull(ThrashGuard.check("read", "{\"path\":\"App.java\"}", ARGS, state),
                "4th: already fired for this streak - must not spam the nudge every subsequent identical call");
    }

    @Test
    void theCorrectiveMessageNamesTheRepeatedTool() {
        final var state = new ThrashGuard.State();
        ThrashGuard.check("bash", "{\"command\":\"ls\"}", Map.of("command", "ls"), state);
        ThrashGuard.check("bash", "{\"command\":\"ls\"}", Map.of("command", "ls"), state);
        final String msg = ThrashGuard.check("bash", "{\"command\":\"ls\"}", Map.of("command", "ls"), state);
        assertNotNull(msg);
        assertTrue(msg.contains("bash"), "names the tool so the model knows which call it's being told to stop");
    }

    @Test
    void aDifferentCallInBetweenResetsTheStreak() {
        final var state = new ThrashGuard.State();
        ThrashGuard.check("read", "{\"path\":\"A.java\"}", Map.of("path", "A.java"), state);
        ThrashGuard.check("read", "{\"path\":\"A.java\"}", Map.of("path", "A.java"), state);
        assertNull(ThrashGuard.check("read", "{\"path\":\"B.java\"}", Map.of("path", "B.java"), state), "a different call breaks the streak");
        assertNull(ThrashGuard.check("read", "{\"path\":\"A.java\"}", Map.of("path", "A.java"), state), "back to A.java, but the streak restarts from 1");
    }

    @Test
    void firesAgainAfterTheStreakBreaksAndRebuildsToThreshold() {
        final var state = new ThrashGuard.State();
        ThrashGuard.check("read", "{\"path\":\"A.java\"}", Map.of("path", "A.java"), state);
        ThrashGuard.check("read", "{\"path\":\"A.java\"}", Map.of("path", "A.java"), state);
        assertNotNull(ThrashGuard.check("read", "{\"path\":\"A.java\"}", Map.of("path", "A.java"), state), "1st streak fires");
        ThrashGuard.check("read", "{\"path\":\"B.java\"}", Map.of("path", "B.java"), state);   // breaks the streak
        ThrashGuard.check("read", "{\"path\":\"A.java\"}", Map.of("path", "A.java"), state);
        ThrashGuard.check("read", "{\"path\":\"A.java\"}", Map.of("path", "A.java"), state);
        assertNotNull(ThrashGuard.check("read", "{\"path\":\"A.java\"}", Map.of("path", "A.java"), state), "2nd streak also fires once it rebuilds to THRESHOLD");
    }

    /** different arguments to the SAME tool must not count as a repeat - repeatedly reading
     *  different files is normal exploration, not thrash. */
    @Test
    void sameToolDifferentArgumentsIsNeverCountedAsAThrashStreak() {
        final var state = new ThrashGuard.State();
        for (int i = 0; i < 10; i++)
            assertNull(ThrashGuard.check("read", "{\"path\":\"F" + i + ".java\"}", Map.of("path", "F" + i + ".java"), state));
    }

    /** #326: re-running the tests after an edit is discipline, not thrash - same exemption
     *  Trajectory.isTestOrBuild() already applies to its own post-hoc metric. */
    @Test
    void identicalTestCommandsAreNeverFlaggedAsThrash() {
        final var state = new ThrashGuard.State();
        for (int i = 0; i < 10; i++)
            assertNull(ThrashGuard.check("bash", "{\"command\":\"./gradlew test\"}", Map.of("command", "./gradlew test"), state));
    }

    @Test
    void aNonBashToolIsNeverExemptedEvenIfItsArgumentsLookTestRelated() {
        final var state = new ThrashGuard.State();
        ThrashGuard.check("write", "{\"path\":\"test.txt\",\"content\":\"gradle\"}", Map.of("path", "test.txt", "content", "gradle"), state);
        ThrashGuard.check("write", "{\"path\":\"test.txt\",\"content\":\"gradle\"}", Map.of("path", "test.txt", "content", "gradle"), state);
        assertNotNull(ThrashGuard.check("write", "{\"path\":\"test.txt\",\"content\":\"gradle\"}", Map.of("path", "test.txt", "content", "gradle"), state));
    }

    /** a bash command that merely runs tests alongside other work must not leave the streak
     *  state corrupted for the NEXT, non-test call - the exemption resets the streak outright
     *  (correct: a test run breaks whatever non-test streak came before it, same as any other
     *  different call would), it never silently counts toward one. */
    @Test
    void aTestCommandBreaksAPriorNonTestStreakInsteadOfExtendingIt() {
        final var state = new ThrashGuard.State();
        ThrashGuard.check("bash", "{\"command\":\"cat App.java\"}", Map.of("command", "cat App.java"), state);
        ThrashGuard.check("bash", "{\"command\":\"cat App.java\"}", Map.of("command", "cat App.java"), state);
        ThrashGuard.check("bash", "{\"command\":\"./gradlew test\"}", Map.of("command", "./gradlew test"), state);   // exempt, resets streak
        assertNull(ThrashGuard.check("bash", "{\"command\":\"cat App.java\"}", Map.of("command", "cat App.java"), state),
                "streak restarts at 1, not 3 - the test run must not be silently skipped over");
    }
}
