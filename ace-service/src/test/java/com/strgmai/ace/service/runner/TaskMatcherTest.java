package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ProviderStore;
import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.backend.OmlxBackend;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.plan.PlanTask;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** #339: the LLM reply's own "<aId> -> <bId-or-NONE>" line format is the one thing standing
 *  between a real reply and a crashed/garbage result - pinned directly, same reasoning JudgeTest
 *  already applies to cap()/capTail() and ReviewsTest applies to externalBase()/
 *  externalCredentials() (all deliberately duplicated here, not shared - see the class javadoc). */
class TaskMatcherTest {

    private static DSLContext freshDb(final String name) throws Exception {
        final var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + Files.createTempFile(name, ".db") + "?foreign_keys=on");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return DSL.using(ds, SQLDialect.SQLITE);
    }

    private final BenchProperties props = new BenchProperties(null, "http://127.0.0.1:9191/v1", null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);
    private final TaskMatcher matcher;

    TaskMatcherTest() throws Exception {
        final var providerStore = new ProviderStore(freshDb("task-matcher-test"));
        matcher = new TaskMatcher(props,
                new ReferenceAgent(new BenchProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null), providerStore),
                providerStore, new OmlxBackend(Files.createTempDirectory("omlx-test")));
    }

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

    /** Found live: ACE_ENDPOINT set to a blank string (not unset - docker-compose.yml's own
     *  ${ACE_ENDPOINT:-}) is how the deploy host's own compose file runs this deliberately, since
     *  it has no local model server reachable. Spring resolves "set to blank" as a real, blank
     *  value - props.endpoint() itself returns "", never falling through to the Java-side
     *  default the way props.upstreamBase() does. externalBase() used to pass that blank string
     *  straight to the OpenAI SDK client builder, which rejected it outright
     *  ("baseUrl cannot be null or blank") for ANY unrecognized provider prefix - including the
     *  historical "omlx" convention baked into resolveModel()'s own local-fallback string when
     *  the deployment's actual local provider happens to be registered under a different key
     *  (confirmed live: "omlx-local"). */
    @Test
    void externalBaseNeverProducesABlankBaseUrlEvenWhenAceEndpointIsSetButBlank() throws Exception {
        final var blankEndpointProps = new BenchProperties(null, "", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        final var providerStore = new ProviderStore(freshDb("task-matcher-blank-endpoint-test"));
        final var blankEndpointMatcher = new TaskMatcher(blankEndpointProps,
                new ReferenceAgent(blankEndpointProps, providerStore), providerStore, new OmlxBackend(Files.createTempDirectory("omlx-test")));

        assertEquals("http://127.0.0.1:9191/v1", blankEndpointMatcher.externalBase("omlx/some-local-model"));
    }
}
