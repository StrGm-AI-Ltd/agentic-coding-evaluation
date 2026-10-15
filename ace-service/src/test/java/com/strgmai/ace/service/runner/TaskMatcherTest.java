package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.plan.PlanTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** #339: the LLM reply's own "<aId> -> <bId-or-NONE>" line format is the one thing standing
 *  between a real reply and a crashed/garbage result - pinned directly, same reasoning JudgeTest
 *  already applies to cap()/capTail() and ReviewsTest applies to externalBase()/
 *  externalCredentials() (all deliberately duplicated here, not shared - see the class javadoc). */
class TaskMatcherTest {

    private final BenchProperties props = new BenchProperties(null, "http://127.0.0.1:9191/v1", null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null);
    private final TaskMatcher matcher = new TaskMatcher(props,
            new ReferenceAgent(new BenchProperties(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null)));

    private static PlanTask task(final String id, final String title) {
        return new PlanTask(id, title);
    }

    @Test
    void matchesEveryAIdToItsOwnLine() {
        final var aTasks = List.of(task("T1", "first"), task("T2", "second"));
        final var bTasks = List.of(task("S1", "first-equiv"), task("S2", "second-equiv"));

        final var matches = TaskMatcher.parseReply("T1 -> S2\nT2 -> S1", aTasks, bTasks);

        assertEquals(2, matches.size());
        assertEquals("S2", matches.get(0).bId());
        assertEquals("second-equiv", matches.get(0).bTitle());
        assertEquals("S1", matches.get(1).bId());
    }

    @Test
    void anExplicitNoneLineIsNoMatch() {
        final var aTasks = List.of(task("T1", "first"));
        final var bTasks = List.of(task("S1", "unrelated"));

        final var matches = TaskMatcher.parseReply("T1 -> NONE", aTasks, bTasks);

        assertEquals(1, matches.size());
        assertNull(matches.get(0).bId());
        assertNull(matches.get(0).bTitle());
    }

    @Test
    void aMissingLineForAnATaskIsNoMatchNotAnException() {
        final var aTasks = List.of(task("T1", "first"), task("T2", "second"));
        final var bTasks = List.of(task("S1", "only match"));

        final var matches = TaskMatcher.parseReply("T1 -> S1", aTasks, bTasks);

        assertEquals("S1", matches.get(0).bId());
        assertNull(matches.get(1).bId(), "T2 had no line in the reply at all");
    }

    @Test
    void aBIdTheModelInventedThatIsntInBTasksIsNoMatch() {
        final var aTasks = List.of(task("T1", "first"));
        final var bTasks = List.of(task("S1", "real"));

        final var matches = TaskMatcher.parseReply("T1 -> S99", aTasks, bTasks);

        assertNull(matches.get(0).bId(), "S99 isn't a real B task id - never trust it over an actual match");
    }

    @Test
    void proseAroundTheExpectedLinesIsIgnored() {
        final var aTasks = List.of(task("T1", "first"));
        final var bTasks = List.of(task("S1", "match"));

        final var matches = TaskMatcher.parseReply(
                "Here is my analysis.\n\nT1 -> S1\n\nI hope this helps!", aTasks, bTasks);

        assertEquals("S1", matches.get(0).bId());
    }

    @Test
    void anEmptyAOrBListShortCircuitsToNoMatches() throws Exception {
        assertEquals(List.of(), matcher.match(List.of(), List.of(task("S1", "x")), null, java.nio.file.Path.of("/tmp")));
        assertEquals(List.of(), matcher.match(List.of(task("T1", "x")), List.of(), null, java.nio.file.Path.of("/tmp")));
    }

    @Test
    void externalBaseResolvesAKnownProviderPrefix() {
        assertEquals("https://api.openai.com/v1", matcher.externalBase("openai/gpt-4o"));
    }

    @Test
    void externalBaseFallsBackToTheConfiguredLocalEndpointForOmlxOrUnknownProviders() {
        assertEquals("http://127.0.0.1:9191/v1", matcher.externalBase("omlx/some-local-model"));
        assertEquals("http://127.0.0.1:9191/v1", matcher.externalBase("not-a-real-provider/model"));
    }
}
