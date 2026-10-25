package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ProviderStore;
import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.config.BenchProperties;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #265: cap()/capTail() are the one thing standing between the judge's own prompt and becoming
 *  the next oversized-context failure (#256's own original shape) - pinned directly, same
 *  reasoning ReviewsTest already applies to externalBase()/externalCredentials() (which Judge
 *  deliberately duplicates rather than shares - see Judge's own class javadoc). */
class JudgeTest {

    private static DSLContext freshDb(final String name) throws Exception {
        final var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + Files.createTempFile(name, ".db") + "?foreign_keys=on");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return DSL.using(ds, SQLDialect.SQLITE);
    }

    private final BenchProperties props = new BenchProperties(null, "http://127.0.0.1:9191/v1", null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null);
    private final ProviderStore providerStore;
    private final Judge judge;

    JudgeTest() throws Exception {
        providerStore = new ProviderStore(freshDb("judge-test"));
        judge = new Judge(props,
                new ReferenceAgent(new BenchProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null), providerStore),
                providerStore);
    }

    @Test
    void capLeavesShortTextUntouchedAndTruncatesLongTextWithAVisibleMarker() {
        assertEquals("short", Judge.cap("short", 100));
        final var truncated = Judge.cap("0123456789", 5);
        assertTrue(truncated.startsWith("01234"));
        assertTrue(truncated.contains("truncated at 5 chars"));
    }

    @Test
    void capOfNullIsBlankNotAnNpe() {
        assertEquals("", Judge.cap(null, 100));
    }

    @Test
    void capTailKeepsTheNewestPartNotTheOldest() {
        final var kept = Judge.capTail("0123456789", 5);
        assertTrue(kept.endsWith("56789"), "the tail (newest part) must survive: " + kept);
        assertTrue(kept.contains("older chars omitted"));
        assertEquals("short", Judge.capTail("short", 100));
    }

    /** #299: the leading verdict line RunBench's adaptive judge interval reads to decide whether
     *  to check in sooner next time. Defaults to false (never tightens) on anything unparseable -
     *  a false negative just keeps the current cadence, while a false positive could ratchet the
     *  interval down on noise alone. */
    @Test
    void isOffTrackReadsTheLeadingVerdictLineCaseInsensitively() {
        assertTrue(Judge.isOffTrack("OFF TRACK\n\nYou are stuck re-editing the same line."));
        assertTrue(Judge.isOffTrack("off track - stop re-editing the same line."), "case-insensitive");
        assertFalse(Judge.isOffTrack("ON TRACK\n\nKeep going, you're nearly done."));
        assertFalse(Judge.isOffTrack("You're on track."), "no leading verdict line at all - never fabricate one");
        assertFalse(Judge.isOffTrack(null), "a failed judge call (Judge.checkpoint()'s own try/catch) must not read as off-track");
        assertFalse(Judge.isOffTrack(""), "blank - no verdict detected");
    }

    /** #402/#405: a consistent, budget-driven triage nudge rather than depending on the judge's
     *  own per-checkpoint phrasing, which does this informally and inconsistently today. Appended
     *  AFTER the recommendation's own first line, never before it, so isOffTrack()'s own parsing
     *  of that first line is unaffected either way. */
    @Test
    void withTriageNoticeFiresOnceLessThan20PercentOfTheWallBudgetRemains() {
        final String onTrack = "ON TRACK\n\nKeep going.";
        assertEquals(onTrack, Judge.withTriageNotice(onTrack, 7999, 10000), "79.99% elapsed, just under the threshold - no notice yet");
        final String withNotice = Judge.withTriageNotice(onTrack, 8000, 10000);
        assertTrue(withNotice.startsWith(onTrack), "the original recommendation's own first line must be untouched");
        assertTrue(withNotice.contains("TRIAGE MODE"));
        assertFalse(Judge.isOffTrack(withNotice), "appending the notice to an ON TRACK verdict must not flip it off-track");

        final String offTrack = "OFF TRACK\n\nStop re-editing the same line.";
        assertTrue(Judge.isOffTrack(Judge.withTriageNotice(offTrack, 8000, 10000)),
                "appending the notice to an OFF TRACK verdict must not lose that verdict either");
    }

    @Test
    void withTriageNoticeNeverFiresOnAnUnlimitedWallBudget() {
        assertEquals("ON TRACK", Judge.withTriageNotice("ON TRACK", 999_999, 0), "0 means unlimited - no threshold to cross");
    }

    @Test
    void withTriageNoticeStandsAloneWhenTheRecommendationItselfWasBlank() {
        final String notice = Judge.withTriageNotice("", 9000, 10000);
        assertTrue(notice.contains("TRIAGE MODE"));
        assertFalse(notice.startsWith("\n"), "no leading blank line when there was nothing to prepend it to");
    }

    /** Found live: a restarted task's judge checkpoints used to collide with its earlier,
     *  abandoned attempt's own files (same taskId-turn&lt;N&gt;.md either time) - this is the
     *  disambiguator BenchController.taskJudge() groups by, so it must be a plain, lexicographically
     *  sortable string (no colons - not filename-safe on every OS - and fixed-width so two
     *  attempts always compare correctly regardless of how many fractional digits their own
     *  millisecond happens to need). */
    @Test
    void attemptStampIsColonFreeFixedWidthAndSortsChronologically() {
        final var earlier = Judge.attemptStamp(java.time.Instant.parse("2026-10-10T08:00:00.000Z").toEpochMilli());
        final var later = Judge.attemptStamp(java.time.Instant.parse("2026-10-10T15:00:00.500Z").toEpochMilli());
        assertFalse(earlier.contains(":"), "must be filename-safe on every OS, not just this one: " + earlier);
        assertEquals(earlier.length(), later.length(), "fixed width, so string comparison is also chronological comparison");
        assertTrue(earlier.compareTo(later) < 0, "an earlier attempt's stamp must sort before a later one's: " + earlier + " vs " + later);
    }

    @Test
    void externalBaseResolvesAKnownProviderPrefix() {
        assertEquals("https://api.openai.com/v1", judge.externalBase("openai/gpt-4", Map.of()));
    }

    @Test
    void externalBaseFallsBackToTheConfiguredEndpointForAnUnknownProvider() {
        assertEquals("http://127.0.0.1:9191/v1", judge.externalBase("localmodel/whatever", Map.of()));
    }

    /** #295: the twin of ReviewsTest's own equivalent case - see Judge.externalBase()'s comment. */
    @Test
    void externalBaseDoesNotThrowOnAReviewerStringWithNoProviderPrefixAtAll() {
        assertEquals("http://127.0.0.1:9191/v1", judge.externalBase("bare-model-name-no-slash", Map.of()));
    }

    /** #106/LLM-Providers: see ReviewsTest's identical-reasoning twin - the old "credential_env"
     *  override is gone, not reimplemented. */
    @Test
    void externalCredentialsReturnsOnlyProvidersWithARealKeyConfigured() {
        assertTrue(judge.externalCredentials().isEmpty(), "none of the seeded providers have a key set in this fresh test DB");
        providerStore.update(providerStore.byKey("openai").id(), "OpenAI", "openai",
                "https://api.openai.com/v1", "sk-openai-test", null);
        assertEquals(Map.of("openai", "sk-openai-test"), judge.externalCredentials());
    }

    // ---- #267: renderTrajectory() - the one piece of the prompt-construction pipeline that is
    // both pure (no I/O) and the most likely to silently mis-render a message type

    @Test
    void renderTrajectoryRendersAUserMessageWithItsOwnLabel() {
        final var out = judge.renderTrajectory(List.of(dev.langchain4j.data.message.UserMessage.from("do the task")));
        assertTrue(out.contains("USER: do the task"), out);
    }

    @Test
    void renderTrajectoryRendersAnAssistantTextMessage() {
        final var out = judge.renderTrajectory(List.of(dev.langchain4j.data.message.AiMessage.from("thinking out loud")));
        assertTrue(out.contains("ASSISTANT: thinking out loud"), out);
    }

    @Test
    void renderTrajectoryRendersAToolCallWithItsNameAndArguments() {
        final var req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c1").name("bash").arguments("{\"command\":\"ls\"}").build();
        final var out = judge.renderTrajectory(List.of(dev.langchain4j.data.message.AiMessage.from(List.of(req))));
        assertTrue(out.contains("ASSISTANT CALLED bash({\"command\":\"ls\"})"), out);
    }

    @Test
    void renderTrajectoryRendersAToolResultWithItsOwnToolNameAndCapsLongOutput() {
        final var req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c1").name("bash").arguments("{}").build();
        final var out = judge.renderTrajectory(List.of(dev.langchain4j.data.message.ToolExecutionResultMessage.from(req, "x".repeat(2000))));
        assertTrue(out.contains("TOOL RESULT (bash):"), out);
        assertTrue(out.contains("truncated at 1000 chars"), "tool results must be capped, same reasoning as the prompt's own diff/trajectory caps: " + out);
    }

    @Test
    void renderTrajectorySkipsAnAiMessageWithNeitherTextNorToolCalls() {
        final var out = judge.renderTrajectory(List.of(dev.langchain4j.data.message.AiMessage.from("")));
        assertTrue(out.isBlank(), "a content-free assistant turn must render as nothing, not a stray label: " + out);
    }

    // ---- #267: checkpoint()'s own exception contract - a judge failure must never take the task
    // down with it (same reasoning Judge's own class javadoc states explicitly)

    /** Forces a REAL exception deep inside the wrapped runCheckpoint() call: `rd` is a plain FILE,
     *  not a directory, so agent.run()'s own AgentSession construction (which creates `rd/sessions`
     *  as a subdirectory) cannot possibly succeed - no network reachability assumed either way. */
    @Test
    void checkpointSwallowsAnInternalExceptionAndReturnsNullInsteadOfPropagatingIt(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path tmp) throws Exception {
        final var rdAsAFile = tmp.resolve("not-a-directory");
        java.nio.file.Files.writeString(rdAsAFile, "x");
        final var ws = tmp.resolve("ws");
        java.nio.file.Files.createDirectories(ws);

        // external=true (an unrecognised provider prefix) and a short wall_sec: no local proxy is
        // started, and even if this somehow reached a real network attempt, it would be bounded to
        // a couple of seconds rather than hanging the test - belt and suspenders alongside the
        // forced-exception path this test is actually targeting.
        final var checkpoint = judge.checkpoint(Map.of("model", "m"), "run-1", ws, rdAsAFile,
                new RecordingProxyFactory(props), "T1", "goal", 0L, 0L, System.currentTimeMillis(),
                Map.of("model", "unrecognised-provider/m", "wall_sec", 2));

        final String rec = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> checkpoint.check(10, List.of()), "checkpoint() must swallow the exception, never propagate it to the caller's own loop");
        assertEquals(null, rec, "a judge failure must degrade to \"no recommendation,\" not throw");
    }
}
