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
 *  insert-once/replace-in-place behavior and failure handling. */
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
        final int n = CompactionStrategies.apply(null, msgs, 1, 0, range -> fail("summarize must not be called when structured_eviction isn't enabled"));
        assertEquals(2, n, "same as AgentSession.compact(msgs, 1) directly");
        assertTrue(((ToolExecutionResultMessage) msgs.get(3)).text().startsWith("[output dropped"));
    }

    @Test
    void structuredEvictionInsertsOneSummaryMessageRightAfterTheOriginalInstruction() {
        final var msgs = threeTurns();
        final int n = CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 1, 0,
                range -> "LANDED: nothing yet");
        assertEquals(1, n);
        assertEquals(9, msgs.size(), "one new message inserted, nothing removed");
        assertTrue(((UserMessage) msgs.get(2)).singleText().contains("LANDED: nothing yet"));
    }

    @Test
    void structuredEvictionUpdatesTheSameSummaryInPlaceRatherThanPilingUpDuplicates() {
        final var msgs = threeTurns();
        CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 1, 0, range -> "first pass");
        final int sizeAfterFirst = msgs.size();
        final int n = CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 1, 0, range -> "second pass");
        assertEquals(1, n);
        assertEquals(sizeAfterFirst, msgs.size(), "the summary is replaced in place, not appended again");
        assertTrue(((UserMessage) msgs.get(2)).singleText().contains("second pass"));
        assertFalse(((UserMessage) msgs.get(2)).singleText().contains("first pass"));
    }

    @Test
    void structuredEvictionNeverTakesTheTaskDownWhenSummarizationFails() {
        final var msgs = threeTurns();
        final var before = List.copyOf(msgs);
        final int n = CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 1, 0,
                range -> { throw new RuntimeException("upstream exploded"); });
        assertEquals(0, n);
        assertEquals(before, msgs, "nothing is mutated when the one-shot summarization call fails");
    }

    @Test
    void structuredEvictionIsANoOpWhenTooFewTurnsExistYetToEvictAnything() {
        final var msgs = threeTurns();
        final int n = CompactionStrategies.apply(List.of(CompactionStrategies.STRUCTURED_EVICTION), msgs, 10,
                0, range -> fail("summarize must not be called when nothing is eligible"));
        assertEquals(0, n);
    }

    @Test
    void bothStrategiesTogetherRunInOrderAndCountsAdd() {
        final var msgs = threeTurns();
        final int n = CompactionStrategies.apply(List.of(CompactionStrategies.TOOL_RESULT_MASK, CompactionStrategies.STRUCTURED_EVICTION),
                msgs, 1, 0, range -> "a summary");
        assertEquals(3, n, "2 stubbed tool results + 1 inserted summary message");
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
                msgs, 1, 0, range -> { seen[0] = List.copyOf(range); return "a summary"; });
        assertNotNull(seen[0], "the summarizer must have been called");
        assertTrue(seen[0].stream().anyMatch(m -> m instanceof ToolExecutionResultMessage t && t.text().equals("output 0")),
                "structured_eviction ran BEFORE the mask in this configured order, so it must see the real output");
    }

    @Test
    void maskBeforeStructuredEvictionStillShowsTheSummarizerAnAlreadyMaskedStub() {
        final var msgs = threeTurns();
        final List<ChatMessage>[] seen = new List[1];
        CompactionStrategies.apply(List.of(CompactionStrategies.TOOL_RESULT_MASK, CompactionStrategies.STRUCTURED_EVICTION),
                msgs, 1, 0, range -> { seen[0] = List.copyOf(range); return "a summary"; });
        assertTrue(seen[0].stream().anyMatch(m -> m instanceof ToolExecutionResultMessage t && t.text().startsWith("[output dropped")),
                "mask ran first in THIS configured order, so structured_eviction sees its stub, not the real output");
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
}
