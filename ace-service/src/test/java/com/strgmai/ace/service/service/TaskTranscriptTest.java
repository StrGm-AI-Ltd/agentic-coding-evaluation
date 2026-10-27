package com.strgmai.ace.service.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The operator-facing "Live" tab's own text rendering - distinct from (and deliberately
 *  simpler than) Judge.renderTrajectory(), since it reads raw session JSONL directly rather
 *  than already-parsed ChatMessage objects, specifically so "thinking" content survives. */
class TaskTranscriptTest {

    @Test
    void renderLineRendersAUserMessage() {
        final var line = "{\"type\":\"message\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"do the thing\"}]}}";
        assertEquals("USER:\ndo the thing", TaskTranscript.renderLine(line));
    }

    @Test
    void renderLineIncludesAssistantThinkingBeforeItsFinalText() {
        final var line = "{\"type\":\"message\",\"message\":{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"thinking\",\"text\":\"let me check the schema first\"},"
                + "{\"type\":\"text\",\"text\":\"done\"}]}}";
        final var rendered = TaskTranscript.renderLine(line);
        assertEquals("ASSISTANT (thinking):\nlet me check the schema first\n\nASSISTANT:\ndone", rendered);
    }

    @Test
    void renderLineRendersAToolCallWithItsArguments() {
        final var line = "{\"type\":\"message\",\"message\":{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"toolCall\",\"name\":\"write\",\"arguments\":{\"path\":\"a.txt\"}}]}}";
        final var rendered = TaskTranscript.renderLine(line);
        assertTrue(rendered.startsWith("ASSISTANT CALLED write("), rendered);
        assertTrue(rendered.contains("a.txt"), rendered);
    }

    @Test
    void renderLineRendersAToolResultAndFlagsAnErrorOne() {
        final var ok = "{\"type\":\"message\",\"message\":{\"role\":\"toolResult\",\"toolName\":\"read\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"file contents\"}],\"isError\":false}}";
        assertEquals("TOOL RESULT (read):\nfile contents", TaskTranscript.renderLine(ok));

        final var err = "{\"type\":\"message\",\"message\":{\"role\":\"toolResult\",\"toolName\":\"read\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"no such file\"}],\"isError\":true}}";
        assertEquals("TOOL RESULT (read, ERROR):\nno such file", TaskTranscript.renderLine(err));
    }

    /** #327: when a failureCategory IS present (every session recorded after that fix), it's
     *  shown directly alongside the plain "ERROR" flag the previous test above still pins for an
     *  older session with no such field - WHY the call failed, not just THAT it did. */
    @Test
    void renderLineShowsTheFailureCategoryWhenPresent() {
        final var err = "{\"type\":\"message\",\"message\":{\"role\":\"toolResult\",\"toolName\":\"edit\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"old_string not found\"}],\"isError\":true,\"failureCategory\":\"NOT_FOUND\"}}";
        assertEquals("TOOL RESULT (edit, ERROR: NOT_FOUND):\nold_string not found", TaskTranscript.renderLine(err));
    }

    @Test
    void renderLineSkipsTheSystemMessageAndTheSessionHeader() {
        final var system = "{\"type\":\"message\",\"message\":{\"role\":\"system\",\"content\":[{\"type\":\"text\",\"text\":\"boilerplate\"}]}}";
        assertNull(TaskTranscript.renderLine(system));
        final var header = "{\"type\":\"session\",\"id\":\"x\",\"label\":\"T13\"}";
        assertNull(TaskTranscript.renderLine(header));
    }

    @Test
    void renderLineRendersCompactionAndEndMarkers() {
        assertEquals("[history compacted: 4 tool result(s) stubbed]",
                TaskTranscript.renderLine("{\"type\":\"compaction\",\"stubbed\":4,\"summarized\":false,\"prompt_tokens_before\":1000}"));
        assertEquals("[session ended: finish=stop, 12 turns]",
                TaskTranscript.renderLine("{\"type\":\"end\",\"turns\":12,\"finish\":\"stop\"}"));
    }

    /** #417 follow-up review (C7): "stubbed" and "summarized" are two genuinely different
     *  mechanisms' own contributions - a structured_eviction-only pass stubs nothing at all, so
     *  it must never be rendered as "0 turns stubbed" (or worse, folded into a count that implies
     *  masking happened when it didn't). */
    @Test
    void renderLineRendersAStructuredEvictionOnlyCompactionDistinctlyFromStubbing() {
        assertEquals("[history compacted: a structured summary written]",
                TaskTranscript.renderLine("{\"type\":\"compaction\",\"stubbed\":0,\"summarized\":true,\"prompt_tokens_before\":1000}"));
        assertEquals("[history compacted: 2 tool result(s) stubbed, a structured summary written]",
                TaskTranscript.renderLine("{\"type\":\"compaction\",\"stubbed\":2,\"summarized\":true,\"prompt_tokens_before\":1000}"));
    }

    @Test
    void renderLineIsNullForBlankOrUnparsableInput() {
        assertNull(TaskTranscript.renderLine(""));
        assertNull(TaskTranscript.renderLine(null));
        assertNull(TaskTranscript.renderLine("not json at all"));
    }

    @Test
    void capLeavesShortTextUntouchedAndTruncatesLongTextWithAVisibleMarker() {
        assertEquals("short", TaskTranscript.cap("short", 100));
        final var truncated = TaskTranscript.cap("0123456789", 5);
        assertTrue(truncated.startsWith("01234"));
        assertTrue(truncated.contains("truncated at 5 chars"));
    }

    @Test
    void capTailKeepsTheNewestPartNotTheOldest() {
        final var kept = TaskTranscript.capTail("0123456789", 5);
        assertTrue(kept.endsWith("56789"));
        assertTrue(kept.contains("older chars omitted"));
    }

    /** The live tab's whole point: the FIRST poll for a task's session gets everything written
     *  so far (capped to a tail), and the NEXT poll - after more lines land - gets only the new
     *  increment, not the whole file re-rendered again. */
    @Test
    void pollReturnsATailCappedCatchUpBatchThenOnlyIncrementalLinesAfterThat() throws Exception {
        final var sessionsDir = Files.createTempDirectory("transcript-test");
        final var file = sessionsDir.resolve("2026-01-01T00-00-00.000Z_755478fc-f323-565e-92f8-1a5b10584e41.jsonl");
        Files.writeString(file, "{\"type\":\"session\",\"id\":\"s\",\"label\":\"T13\"}\n"
                + "{\"type\":\"message\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"first\"}]}}\n");
        final var transcript = new TaskTranscript();

        final var first = transcript.poll(sessionsDir, "T13", 20_000);
        assertEquals("USER:\nfirst", first);

        Files.writeString(file, "{\"type\":\"message\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"second\"}]}}\n",
                java.nio.file.StandardOpenOption.APPEND);
        final var second = transcript.poll(sessionsDir, "T13", 20_000);
        assertEquals("USER:\nsecond", second, "only the NEW line, not first+second re-rendered");
    }

    @Test
    void pollReturnsNullWhenNoSessionMatchesTheTaskIdYet() throws Exception {
        final var sessionsDir = Files.createTempDirectory("transcript-test");
        assertNull(new TaskTranscript().poll(sessionsDir, "T13", 20_000));
    }

    /** Found live: a session file caught mid-write (header line not yet complete, so unparsable)
     *  used to have its "" read permanently cached via Map.computeIfAbsent(), meaning once a
     *  restarted task's own new file was seen in that half-written state even once, this class
     *  would never recognize it as belonging to taskId again for the rest of the connection -
     *  the Live tab would silently freeze on the OLD attempt forever. readLabel() returning null
     *  (not "") for a transient read failure is the fix: computeIfAbsent's own documented
     *  contract ("if the function returns null, no mapping is recorded") makes this retry
     *  correctly on the very next poll, matching Tailer.newSessions()'s own established
     *  behavior for the identical race. */
    @Test
    void pollRetriesASessionFileCaughtMidWriteInsteadOfPermanentlyTreatingItAsNonMatching() throws Exception {
        final var sessionsDir = Files.createTempDirectory("transcript-test");
        final var file = sessionsDir.resolve("2026-01-01T00-00-00.000Z_755478fc-f323-565e-92f8-1a5b10584e41.jsonl");
        // a half-written header line: valid UTF-8, but not yet valid JSON (no closing brace) -
        // exactly what a concurrent AgentSession.header() write looks like mid-flush
        Files.writeString(file, "{\"type\":\"session\",\"id\":\"s\",\"label\":\"T13");
        final var transcript = new TaskTranscript();

        assertNull(transcript.poll(sessionsDir, "T13", 20_000), "not yet readable - must not match, and must not cache that as permanent");

        Files.writeString(file, "\"}\n{\"type\":\"message\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertEquals("USER:\nhi", transcript.poll(sessionsDir, "T13", 20_000),
                "now that the header is complete, this file must be recognized as T13's - the earlier failed read must not have been cached forever");
    }

    /** Found live (same bug class as #305/Tailer's own ordering fix): a restarted task's own NEW
     *  session file must win over its earlier, abandoned attempt's - and the catch-up batch for
     *  that NEW file must be capped again, not treated as a continuation of the old one's offset. */
    @Test
    void pollSwitchesToARestartedTasksNewerSessionFileAndCapsItsOwnCatchUpBatchAgain() throws Exception {
        final var sessionsDir = Files.createTempDirectory("transcript-test");
        final var oldFile = sessionsDir.resolve("2026-01-01T00-00-00.000Z_755478fc-f323-565e-92f8-1a5b10584e41.jsonl");
        Files.writeString(oldFile, "{\"type\":\"session\",\"id\":\"old\",\"label\":\"T13\"}\n"
                + "{\"type\":\"message\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"abandoned attempt\"}]}}\n");
        final var transcript = new TaskTranscript();
        assertEquals("USER:\nabandoned attempt", transcript.poll(sessionsDir, "T13", 20_000));

        final var newFile = sessionsDir.resolve("2026-01-02T00-00-00.000Z_a1111111-1111-1111-1111-111111111111.jsonl");
        Files.writeString(newFile, "{\"type\":\"session\",\"id\":\"new\",\"label\":\"T13\"}\n"
                + "{\"type\":\"message\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"restarted attempt\"}]}}\n");
        assertEquals("USER:\nrestarted attempt", transcript.poll(sessionsDir, "T13", 20_000),
                "must switch to the newer file's own content, not keep tailing the abandoned one");
    }
}
