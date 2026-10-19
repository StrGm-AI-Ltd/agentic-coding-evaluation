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
}
