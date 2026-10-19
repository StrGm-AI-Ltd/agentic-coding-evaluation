package com.strgmai.ace.service.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** #417: which compaction strategy (or combination, in this order) a run or task has enabled -
 *  the pluggable layer on top of AgentSession's own mechanisms. Mirrors this codebase's existing
 *  static-utility style (AgentSession/JournalFacts/SseAssembler are all final classes with static
 *  methods, not an interface hierarchy) rather than introducing a new Strategy interface for
 *  exactly two implementations. */
public final class CompactionStrategies {
    private static final Logger log = LoggerFactory.getLogger(CompactionStrategies.class);
    private CompactionStrategies() { }

    public static final String TOOL_RESULT_MASK = "tool_result_mask";
    public static final String STRUCTURED_EVICTION = "structured_eviction";
    public static final Set<String> KNOWN = Set.of(TOOL_RESULT_MASK, STRUCTURED_EVICTION);
    /** today's own behavior, unchanged, when a run/task configures nothing. */
    public static final List<String> DEFAULT = List.of(TOOL_RESULT_MASK);

    private static final String SUMMARY_MARKER = "## Context summary (maintained by the harness)";

    /** Found live (#417 follow-up review): the range structured_eviction summarizes can span many
     *  turns, each with its own tool output - unlike Judge's own single diff/trajectory, there was
     *  nothing here bounding how much text that one-shot call actually sends. Same character-cap
     *  convention Judge's MAX_DIFF_CHARS/MAX_TRAJECTORY_CHARS (12k/16k) already use, scaled up since
     *  this range is many turns, not one diff - see capForSummarization() below. */
    private static final int MAX_SUMMARIZE_CHARS = 40_000;

    /** Runs each enabled strategy once, in the CONFIGURED ORDER - found live (#417 follow-up
     *  review): this used to be two independent `if (strategies.contains(...))` checks, so
     *  tool_result_mask always ran first regardless of what a task's own reordered list said,
     *  silently contradicting both this method's own javadoc and the UI's reorder control. A
     *  switch per entry, in list order, makes "structured_eviction before tool_result_mask" (so the
     *  summarizer sees the real tool output, not an already-masked stub) an actual, reachable
     *  configuration rather than dead UI. No mid-pass budget recheck between strategies in v1
     *  (simplicity first; revisit once there's a 3rd+ strategy and real evidence it's needed).
     *  `summarize` is structured_eviction's own one-shot LLM call - built by the caller
     *  (ReferenceAgent already has a live chatModel/deadline/retry loop in scope at the one call
     *  site this fires from) and never invoked at all when structured_eviction isn't enabled. */
    public static int apply(final List<String> enabled, final List<ChatMessage> msgs, final int keepRecentTurns,
                             final int keepTokenBudget, final Function<List<ChatMessage>, String> summarize) {
        final List<String> strategies = enabled == null || enabled.isEmpty() ? DEFAULT : enabled;
        int n = 0;
        for (final String strategy : strategies) {
            n += switch (strategy) {
                case TOOL_RESULT_MASK -> AgentSession.compact(msgs, keepRecentTurns, keepTokenBudget);
                case STRUCTURED_EVICTION -> structuredEvict(msgs, keepRecentTurns, summarize);
                default -> 0;   // RunSpec's own validation already rejects an unknown name before it ever reaches here
            };
        }
        return n;
    }

    /** #417 mechanism 6: a fixed-outline summary document instead of free prose - the same pattern
     *  ACE's own inter-task handoff-notes/lessons.md already use successfully, applied within a
     *  single session. Written/updated as ONE message right after the original task instruction
     *  (index 2), replaced in place on later passes rather than piling up duplicates - "updating the
     *  same document rather than regenerating it from scratch", per the issue's own research. Only
     *  ever reads/writes msgs.subList(0, cutIndex(...)): that boundary always lands on a complete
     *  turn (see cutIndex's own javadoc), so the one-shot summarization request built from it is
     *  always a structurally valid request on its own. */
    private static int structuredEvict(final List<ChatMessage> msgs, final int keepRecentTurns,
                                        final Function<List<ChatMessage>, String> summarize) {
        final int cut = AgentSession.cutIndex(msgs, keepRecentTurns);
        if (cut <= 2 || summarize == null) return 0;
        final String summary;
        try {
            summary = summarize.apply(capForSummarization(msgs.subList(0, cut)));
        } catch (Exception e) {
            // never take the task down for a failed summarization call - but found live (#417
            // follow-up review): this used to be a bare `return 0` with no logger anywhere in this
            // class at all, so a failure here (a BudgetExhausted/ContextWindowExceeded is a genuine
            // RuntimeException from ReferenceAgent.chatWithRetry, not a hypothetical) was invisible -
            // nothing on disk or in the log could tell an operator structured_eviction was silently
            // doing nothing on exactly the long tasks where it matters most.
            log.warn("structured_eviction's own summarization call failed, skipping this pass", e);
            return 0;
        }
        if (summary == null || summary.isBlank()) return 0;
        final String text = SUMMARY_MARKER + "\n\n" + summary;
        if (msgs.get(2) instanceof UserMessage um && um.singleText().startsWith(SUMMARY_MARKER)) msgs.set(2, UserMessage.from(text));
        else msgs.add(2, UserMessage.from(text));
        return 1;
    }

    /** Found live (#417 follow-up review): the range summarized here can span many turns, each with
     *  its own tool output, with nothing bounding its total size before it goes into a single
     *  advisory LLM call - unlike Judge's own capped diff/trajectory. Keeps the head (the system
     *  prompt and the original task instruction at indices 0/1 - always needed for the summary to
     *  make sense) plus the LARGEST trailing, turn-aligned portion that still fits
     *  MAX_SUMMARIZE_CHARS, the same "keep the newest part" reasoning Judge's own capTail() already
     *  applies. Turn-aligned: the cut point is rounded forward to the next AiMessage index, so an
     *  AiMessage's own tool calls are never separated from their results - the request sent to the
     *  model stays structurally valid. */
    static List<ChatMessage> capForSummarization(final List<ChatMessage> range) {
        if (range.size() <= 2) return range;
        final long headChars = textLength(range.get(0)) + textLength(range.get(1));
        long total = headChars;
        for (int i = 2; i < range.size(); i++) total += textLength(range.get(i));
        if (total <= MAX_SUMMARIZE_CHARS) return range;
        long kept = headChars;
        int cutFrom = range.size();
        for (int i = range.size() - 1; i >= 2; i--) {
            final long next = kept + textLength(range.get(i));
            if (next > MAX_SUMMARIZE_CHARS) break;
            kept = next;
            cutFrom = i;
        }
        int start = cutFrom;
        while (start < range.size() && !(range.get(start) instanceof AiMessage)) start++;
        final List<ChatMessage> trimmed = new ArrayList<>(List.of(range.get(0), range.get(1)));
        if (start < range.size()) trimmed.addAll(range.subList(start, range.size()));
        return trimmed;
    }

    private static long textLength(final ChatMessage m) {
        if (m instanceof AiMessage ai) {
            long len = ai.text() == null ? 0 : ai.text().length();
            if (ai.hasToolExecutionRequests()) for (final ToolExecutionRequest c : ai.toolExecutionRequests()) len += c.arguments().length();
            return len;
        }
        if (m instanceof ToolExecutionResultMessage t) return t.text().length();
        if (m instanceof UserMessage u) return u.singleText().length();
        if (m instanceof SystemMessage s) return s.text().length();
        return 0;
    }
}
