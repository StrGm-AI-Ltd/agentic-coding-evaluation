package com.strgmai.ace.service.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** #417: dispatch/ordering across the pluggable strategies, and structured_eviction's own
 *  insert-once/replace-in-place behavior and failure handling. Plus (#417 follow-up review) the
 *  defects an independent second review found in this same mechanism after it shipped: a
 *  degenerate head-only summarization range (C1), a prior summary lost on trim (C2), an uncapped
 *  head (C3), structured_eviction's own boundary diverging from tool_result_mask's shrunk one
 *  (C4), an unknown strategy name silently disabling compaction (C6), and a redundant re-
 *  summarization once nothing has changed (C9). */
class CompactionStrategiesTest {

    private static List<ChatMessage> threeTurns() {
        final List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from("system"));
        msgs.add(UserMessage.from("the harness pack"));
        for (int i = 0; i < 3; i++) {
            final var req = ToolExecutionRequest.builder().id("c" + i).name("bash").arguments("{}").build();
            msgs.add(AiMessage.from("turn " + i, List.of(req)));
            msgs.add(ToolExecutionResultMessage.from(req, "output " + i));
        }
        return msgs;
    }

    @Test
    void nullOrEmptyEnabledListFallsBackToToolResultMaskAlone() {
        final var msgs = threeTurns();
        final var result = CompactionStrategies.apply(null, msgs, 1, 0, new CompactionStrategies.State(),
                range -> fail("summarize must not be called when structured_eviction isn't enabled"));
        assertEquals(2, result.masked(), "same as AgentSession.compact(msgs, 1) directly");
        assertFalse(result.summarized());
        assertTrue(((ToolExecutionResultMessage) msgs.get(3)).text().startsWith("[output dropped"));
    }

    @Test
    void structuredEvictionInsertsOneSummaryMessageRightAfterTheOriginalInstruction() {
        final var msgs = threeTurns();
        final var result = CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 1, 0, new CompactionStrategies.State(),
                range -> "LANDED: nothing yet");
        assertEquals(0, result.masked());
        assertTrue(result.summarized());
        assertEquals(9, msgs.size(), "one new message inserted, nothing removed");
        assertTrue(((UserMessage) msgs.get(2)).singleText().contains("LANDED: nothing yet"));
    }

    @Test
    void structuredEvictionUpdatesTheSameSummaryInPlaceRatherThanPilingUpDuplicates() {
        final var msgs = threeTurns();
        // a fresh State per call: this test is about replace-in-place, not C9's own skip-when-
        // unchanged behavior (covered separately below)
        CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 1, 0, new CompactionStrategies.State(), range -> "first pass");
        final int sizeAfterFirst = msgs.size();
        final var result = CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 1, 0, new CompactionStrategies.State(), range -> "second pass");
        assertTrue(result.summarized());
        assertEquals(sizeAfterFirst, msgs.size(), "the summary is replaced in place, not appended again");
        assertTrue(((UserMessage) msgs.get(2)).singleText().contains("second pass"));
        assertFalse(((UserMessage) msgs.get(2)).singleText().contains("first pass"));
    }

    @Test
    void structuredEvictionNeverTakesTheTaskDownWhenSummarizationFails() {
        final var msgs = threeTurns();
        final var before = List.copyOf(msgs);
        final var result = CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 1, 0, new CompactionStrategies.State(),
                range -> { throw new RuntimeException("upstream exploded"); });
        assertEquals(0, result.masked());
        assertFalse(result.summarized());
        assertEquals(before, msgs, "nothing is mutated when the one-shot summarization call fails");
    }

    @Test
    void structuredEvictionIsANoOpWhenTooFewTurnsExistYetToEvictAnything() {
        final var msgs = threeTurns();
        final var result = CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 10, 0, new CompactionStrategies.State(),
                range -> fail("summarize must not be called when nothing is eligible"));
        assertEquals(0, result.masked());
        assertFalse(result.summarized());
    }

    @Test
    void bothStrategiesTogetherRunInOrderAndCountsAreReportedSeparately() {
        final var msgs = threeTurns();
        // #417 follow-up review (C7): used to be ONE summed int (3 = "2 stubbed + 1 inserted") -
        // structured_eviction never frees a single token (it only inserts), so folding it into the
        // same count as tool_result_mask's own stub count misreported "a summary was written" as
        // "N turns stubbed" whenever masking didn't actually happen.
        final var result = CompactionStrategies.apply(List.of(CompactionStrategies.TOOL_RESULT_MASK, CompactionStrategies.STRUCTURED_EVICTION),
                msgs, 1, 0, new CompactionStrategies.State(), range -> "a summary");
        assertEquals(2, result.masked(), "2 stubbed tool results");
        assertTrue(result.summarized());
        assertTrue(((ToolExecutionResultMessage) msgs.get(4)).text().startsWith("[output dropped"), "mask ran against the ORIGINAL indices, before the summary insert shifted them");
    }

    /** Found live (#417 follow-up review): apply() used to be two independent `if` checks, so
     *  tool_result_mask ALWAYS ran first no matter what order a task's own config/UI reorder
     *  control said - structured_eviction's summarizer never actually saw real tool output, only
     *  an already-masked stub, whenever both were enabled. This proves the configured order is now
     *  genuinely honored: with structured_eviction listed FIRST, the summarizer must see the real
     *  text, not "[output dropped...]". */
    @Test
    void structuredEvictionBeforeMaskSeesTheRealToolOutputNotAnAlreadyMaskedStub() {
        final var msgs = threeTurns();
        final List<ChatMessage>[] seen = new List[1];
        // range may be a live view backed by msgs (capForSummarization can return it unchanged) -
        // copy it NOW, inside the lambda, so a LATER strategy's mutation in this same apply() call
        // can't retroactively change what this assertion observes.
        CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION, CompactionStrategies.TOOL_RESULT_MASK),
                msgs, 1, 0, new CompactionStrategies.State(), range -> { seen[0] = List.copyOf(range); return "a summary"; });
        assertNotNull(seen[0], "the summarizer must have been called");
        assertTrue(seen[0].stream().anyMatch(m -> m instanceof ToolExecutionResultMessage t && t.text().equals("output 0")),
                "structured_eviction ran BEFORE the mask in this configured order, so it must see the real output");
    }

    @Test
    void maskBeforeStructuredEvictionStillShowsTheSummarizerAnAlreadyMaskedStub() {
        final var msgs = threeTurns();
        final List<ChatMessage>[] seen = new List[1];
        CompactionStrategies.apply(List.of(CompactionStrategies.TOOL_RESULT_MASK, CompactionStrategies.STRUCTURED_EVICTION),
                msgs, 1, 0, new CompactionStrategies.State(), range -> { seen[0] = List.copyOf(range); return "a summary"; });
        assertTrue(seen[0].stream().anyMatch(m -> m instanceof ToolExecutionResultMessage t && t.text().startsWith("[output dropped")),
                "mask ran first in THIS configured order, so structured_eviction sees its stub, not the real output");
    }

    /** #417 follow-up review (C6): a task override set via the restart-task endpoint never goes
     *  through RunSpec's own validation - an unknown entry reaching apply()'s own switch used to
     *  be silently ignored by a bare `default -> 0`; this pins that a sibling, VALID entry in the
     *  same list still runs correctly regardless (dispatch is per-entry, not all-or-nothing). The
     *  trim/validate/log behavior itself lives in CompactionStrategies.parse(), tested below. */
    @Test
    void unknownStrategyNameDoesNotPreventAValidSiblingEntryFromRunning() {
        final var msgs = threeTurns();
        final var result = CompactionStrategies.apply(List.of("bogus", CompactionStrategies.TOOL_RESULT_MASK), msgs, 1, 0, new CompactionStrategies.State(),
                range -> fail("structured_eviction wasn't configured"));
        assertEquals(2, result.masked());
        assertFalse(result.summarized());
    }

    /** found live (124 occurrences across one run's own logs): ERROR_LINE_EXTRACTION and
     *  TOOL_OUTPUT_RECALL are real, KNOWN strategies with no masking logic of their own to run in
     *  apply()'s loop (they're enforced elsewhere, via plain `enabledStrategies.contains(...)`
     *  checks) - but with no explicit case for either, apply()'s own `default` branch logged BOTH
     *  as "unknown ... ignored" on every single compaction event of every run whose list included
     *  them (which DEFAULT itself always does), directly contradicting the very KNOWN set the
     *  warning itself printed in the same breath. Captures the real log output (not just apply()'s
     *  return value, which the old, buggy code already produced identically) since the defect was
     *  entirely in what got logged, not in any masking behavior. */
    @Test
    void applyNeverLogsErrorLineExtractionOrToolOutputRecallAsUnknown() {
        final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CompactionStrategies.class);
        final var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            CompactionStrategies.apply(CompactionStrategies.DEFAULT, threeTurns(), 1, 0, new CompactionStrategies.State(),
                    range -> fail("structured_eviction wasn't configured"));
            assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("unknown compaction strategy")),
                    "DEFAULT's own entries must never be logged as unknown: " + appender.list);
        } finally { logger.detachAppender(appender); }
    }

    @Test
    void parseTrimsEachEntryAndDropsUnknownNamesInsteadOfSilentlyDisablingEverythingElse() {
        assertEquals(List.of(CompactionStrategies.TOOL_RESULT_MASK, CompactionStrategies.STRUCTURED_EVICTION),
                CompactionStrategies.parse(" tool_result_mask ,  structured_eviction "), "a human-typed CSV with spaces must still resolve correctly");
        assertEquals(List.of(CompactionStrategies.TOOL_RESULT_MASK), CompactionStrategies.parse("tool_result_mask,bogus"), "the valid entry survives a bad sibling");
        assertEquals(List.of(), CompactionStrategies.parse("bogus"), "an all-invalid value is an empty list, not a crash");
        assertEquals(List.of(), CompactionStrategies.parse(""));
        assertEquals(List.of(), CompactionStrategies.parse(null));
    }

    /** #417 follow-up review (C9): once every old tool result is already stubbed (tool_result_mask's
     *  own one-shot guard then returns 0 forever), a prompt that stays over the trigger used to
     *  re-fire a fresh, near-identical-input summarization every single turn. The SAME State
     *  instance across two calls with nothing new added must skip the second summarization. */
    @Test
    void structuredEvictionSkipsARedundantReSummarizationWhenTheTailHasNotGrown() {
        final var msgs = threeTurns();
        final var state = new CompactionStrategies.State();
        final int[] calls = {0};
        CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 1, 0, state, range -> { calls[0]++; return "pass " + calls[0]; });
        assertEquals(1, calls[0]);
        final var result = CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 1, 0, state, range -> { calls[0]++; return "pass " + calls[0]; });
        assertEquals(1, calls[0], "the summarizer must NOT be called again - nothing new to summarize");
        assertFalse(result.summarized(), "nothing changed this pass");
    }

    @Test
    void structuredEvictionDoesReSummarizeOnceTheTailActuallyGrows() {
        final var msgs = threeTurns();
        final var state = new CompactionStrategies.State();
        CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 1, 0, state, range -> "first");
        // insert a genuinely new turn right before the final (newest, still-protected) turn's own
        // pair, so it becomes part of the OLDER/summarizable tail rather than the protected head
        final int insertAt = msgs.size() - 2;
        final var req = ToolExecutionRequest.builder().id("c-new").name("bash").arguments("{}").build();
        msgs.add(insertAt, AiMessage.from("extra", List.of(req)));
        msgs.add(insertAt + 1, ToolExecutionResultMessage.from(req, "more output"));
        final var result = CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 1, 0, state, range -> "second");
        assertTrue(result.summarized(), "the tail grew - the summary must be refreshed");
    }

    /** #417 follow-up review (C4): structured_eviction's own eligible range used to come from
     *  cutIndex()'s turn-count-only boundary while tool_result_mask shrank its OWN boundary
     *  further by keepTokenBudget - a divergence that could leave a blind spot (content neither
     *  masked-and-protected nor summarized). Both now share AgentSession.effectiveCut(); this
     *  pins that structured_eviction's own summarized range matches it exactly. The oversized
     *  turn here is deliberately small enough (2000 chars) to trigger effectiveCut's OWN
     *  token-budget shrink without also tripping capForSummarization's unrelated, much larger
     *  40,000-char cap - isolating this test from C1/C2/C3's own trimming behavior. */
    @Test
    void structuredEvictionsOwnRangeMatchesAgentSessionsEffectiveCutExactly() {
        final List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from("system"));
        msgs.add(UserMessage.from("the harness pack"));
        for (int i = 0; i < 3; i++) {
            final var req = ToolExecutionRequest.builder().id("c" + i).name("bash").arguments("{}").build();
            msgs.add(AiMessage.from("turn " + i, List.of(req)));
            msgs.add(ToolExecutionResultMessage.from(req, i == 1 ? "x".repeat(2_000) : "output " + i));
        }
        final int expectedCut = AgentSession.effectiveCut(msgs, 2, 10);
        final List<ChatMessage>[] seen = new List[1];
        // copy NOW, inside the lambda: structuredEvict's own msgs.add(2, ...) right after this
        // returns would otherwise invalidate a live subList view backed by msgs
        CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 2, 10, new CompactionStrategies.State(),
                range -> { seen[0] = List.copyOf(range); return "a summary"; });
        assertNotNull(seen[0]);
        assertEquals(expectedCut, seen[0].size(), "structured_eviction's own range must cover exactly [0, effectiveCut), same as tool_result_mask's own boundary");
    }

    /** Found live (#417 follow-up review): the range structured_eviction summarizes had nothing
     *  bounding its size before it went into a single advisory LLM call, unlike Judge's own capped
     *  diff/trajectory (MAX_DIFF_CHARS/MAX_TRAJECTORY_CHARS). */
    @Test
    void capForSummarizationLeavesASmallRangeUntouched() {
        final var msgs = threeTurns();
        assertSame(msgs, CompactionStrategies.capForSummarization(msgs), "well under the cap - no trimming needed");
    }

    @Test
    void capForSummarizationKeepsTheHeadAndTheNewestTurnAlignedPortionWhenOversized() {
        final List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from("system"));
        msgs.add(UserMessage.from("the harness pack"));
        for (int i = 0; i < 5; i++) {
            final var req = ToolExecutionRequest.builder().id("c" + i).name("bash").arguments("{}").build();
            msgs.add(AiMessage.from("turn " + i, List.of(req)));
            // each turn alone is well over MAX_SUMMARIZE_CHARS / 5, forcing the cap to drop some
            msgs.add(ToolExecutionResultMessage.from(req, "x".repeat(15_000)));
        }
        final List<ChatMessage> trimmed = CompactionStrategies.capForSummarization(msgs);
        assertTrue(trimmed.size() < msgs.size(), "an oversized range must be trimmed");
        assertEquals(msgs.get(0), trimmed.get(0), "the system message is always kept");
        assertEquals(msgs.get(1), trimmed.get(1), "the original task instruction is always kept");
        assertInstanceOf(AiMessage.class, trimmed.get(2), "the kept tail must start exactly at a turn boundary - never a lone tool result with no AiMessage");
        assertEquals(msgs.get(msgs.size() - 1), trimmed.get(trimmed.size() - 1), "the NEWEST turn is kept, not the oldest");
    }

    /** #417 follow-up review (C1): capForSummarization can legitimately trim away EVERY turn of
     *  real conversation, leaving only the head - found live: one oversized tool result (e.g. a
     *  large file write) is enough. Firing the summarization call anyway sends the model a
     *  request with nothing to summarize; a local model fabricates a plausible-looking answer
     *  from the task instruction alone, and that fabrication then gets committed as settled fact.
     *  This pins that structured_eviction detects the degenerate case and skips the call entirely
     *  rather than ever reaching summarize.apply(...). */
    @Test
    void structuredEvictionSkipsTheCallEntirelyWhenTrimmingLeavesNothingButTheHead() {
        final List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from("system"));
        msgs.add(UserMessage.from("the harness pack"));
        final var req = ToolExecutionRequest.builder().id("c0").name("write").arguments("{}").build();
        msgs.add(AiMessage.from("turn 0", List.of(req)));
        msgs.add(ToolExecutionResultMessage.from(req, "x".repeat(50_000)));   // alone, already over MAX_SUMMARIZE_CHARS
        final var result = CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 0, 0, new CompactionStrategies.State(),
                range -> fail("summarize must never be called when nothing but the head survives trimming"));
        assertFalse(result.summarized());
    }

    /** #417 follow-up review (C2): the previous summary (always at index 2 once one exists) used
     *  to be dropped from the trimmed input whenever trimming fired - each pass re-summarized a
     *  fresh window and lost everything earlier passes had already distilled, directly
     *  contradicting the "update in place" behavior this mechanism advertises. This pins that the
     *  prior summary survives in the capped/trimmed head even when the tail is oversized enough
     *  to force trimming. */
    @Test
    void capForSummarizationKeepsAPriorSummaryInTheHeadAcrossTrimming() {
        final List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from("system"));
        msgs.add(UserMessage.from("the harness pack"));
        msgs.add(UserMessage.from("## Context summary (maintained by the harness)\n\nfirst version"));
        for (int i = 0; i < 5; i++) {
            final var req = ToolExecutionRequest.builder().id("c" + i).name("bash").arguments("{}").build();
            msgs.add(AiMessage.from("turn " + i, List.of(req)));
            msgs.add(ToolExecutionResultMessage.from(req, "x".repeat(15_000)));
        }
        final List<ChatMessage> trimmed = CompactionStrategies.capForSummarization(msgs);
        assertTrue(trimmed.size() < msgs.size(), "an oversized range must still be trimmed");
        assertInstanceOf(UserMessage.class, trimmed.get(2), "the prior summary's own slot (index 2) must survive in the head");
        assertTrue(((UserMessage) trimmed.get(2)).singleText().contains("first version"), "the prior summary must survive trimming, not be silently dropped");
        assertInstanceOf(AiMessage.class, trimmed.get(3), "the kept tail must still start exactly at a turn boundary");
    }

    /** #417 follow-up review (C3): the head (system prompt / original instruction / prior
     *  summary) used to be exempt from capping entirely - an oversized one of those three alone
     *  (a huge prior summary, say) could exceed the whole budget before a single turn of real
     *  conversation was even considered. Each head message is now capped individually. */
    @Test
    void capForSummarizationCapsAnOversizedHeadMessageEvenWhenTheTotalWouldOtherwiseFit() {
        final List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from("system"));
        msgs.add(UserMessage.from("the harness pack"));
        msgs.add(UserMessage.from("## Context summary (maintained by the harness)\n\n" + "x".repeat(20_000)));   // alone over MAX_HEAD_MESSAGE_CHARS
        final var req = ToolExecutionRequest.builder().id("c0").name("bash").arguments("{}").build();
        msgs.add(AiMessage.from("turn 0", List.of(req)));
        msgs.add(ToolExecutionResultMessage.from(req, "small output"));
        final List<ChatMessage> trimmed = CompactionStrategies.capForSummarization(msgs);
        assertNotSame(msgs, trimmed, "the oversized head message alone must trigger capping even though the total is well under MAX_SUMMARIZE_CHARS");
        assertTrue(((UserMessage) trimmed.get(2)).singleText().length() < 20_000, "the prior summary's own text must be capped");
        assertTrue(((UserMessage) trimmed.get(2)).singleText().startsWith("## Context summary"), "the marker prefix must survive the cap (it's at the very start of the text)");
    }
}
