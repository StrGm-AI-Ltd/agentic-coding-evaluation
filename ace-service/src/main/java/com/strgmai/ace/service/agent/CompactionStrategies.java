package com.strgmai.ace.service.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** #417: which compaction strategy (or combination, in this order) a run or task has enabled -
 *  the pluggable layer on top of AgentSession's own mechanisms. Mirrors this codebase's existing
 *  static-utility style (AgentSession/JournalFacts/SseAssembler are all final classes with static
 *  methods, not an interface hierarchy) rather than introducing a new Strategy interface for
 *  exactly two implementations. */
public final class CompactionStrategies {
    private CompactionStrategies() { }

    public static final String TOOL_RESULT_MASK = "tool_result_mask";
    public static final String STRUCTURED_EVICTION = "structured_eviction";
    public static final Set<String> KNOWN = Set.of(TOOL_RESULT_MASK, STRUCTURED_EVICTION);
    /** today's own behavior, unchanged, when a run/task configures nothing. */
    public static final List<String> DEFAULT = List.of(TOOL_RESULT_MASK);

    private static final String SUMMARY_MARKER = "## Context summary (maintained by the harness)";

    /** Runs each enabled strategy once, in the configured order. No mid-pass budget recheck between
     *  strategies in v1 (simplicity first; revisit once there's a 3rd+ strategy and real evidence
     *  it's needed). `summarize` is structured_eviction's own one-shot LLM call - built by the
     *  caller (ReferenceAgent already has a live chatModel/deadline/retry loop in scope at the one
     *  call site this fires from) and never invoked at all when structured_eviction isn't enabled. */
    public static int apply(final List<String> enabled, final List<ChatMessage> msgs, final int keepRecentTurns,
                             final int keepTokenBudget, final Function<List<ChatMessage>, String> summarize) {
        final List<String> strategies = enabled == null || enabled.isEmpty() ? DEFAULT : enabled;
        int n = 0;
        if (strategies.contains(TOOL_RESULT_MASK)) n += AgentSession.compact(msgs, keepRecentTurns, keepTokenBudget);
        if (strategies.contains(STRUCTURED_EVICTION)) n += structuredEvict(msgs, keepRecentTurns, summarize);
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
            summary = summarize.apply(msgs.subList(0, cut));
        } catch (Exception e) {
            return 0;   // never take the task down for a failed summarization call
        }
        if (summary == null || summary.isBlank()) return 0;
        final String text = SUMMARY_MARKER + "\n\n" + summary;
        if (msgs.get(2) instanceof UserMessage um && um.singleText().startsWith(SUMMARY_MARKER)) msgs.set(2, UserMessage.from(text));
        else msgs.add(2, UserMessage.from(text));
        return 1;
    }
}
