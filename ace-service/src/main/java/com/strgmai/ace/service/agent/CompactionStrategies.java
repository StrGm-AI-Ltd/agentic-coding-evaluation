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
    /** #423: keep up to MAX_ERROR_LINES_KEPT FAILED/Exception/assertion-diff/stack-frame lines from
     *  a bash output's own omitted middle, verbatim, instead of letting that range go completely
     *  dark (AgentTools.bash()'s own deterministic rescope of #417's original mechanism 4). */
    public static final String ERROR_LINE_EXTRACTION = "error_line_extraction";
    /** #424: offers the recall_tool_output tool at all, and is what AgentSession's own masking
     *  stub text points at ("call recall_tool_output(...)") - disabled, a session falls back to
     *  the pre-#424 "re-run the command if you need it" wording instead, so the stub never
     *  references a tool the model structurally cannot call. */
    public static final String TOOL_OUTPUT_RECALL = "tool_output_recall";
    /** #332: offers the record_lesson tool at all - not itself a compaction mechanism, but reuses
     *  this exact pluggable-session-capability toggle (the same vehicle ERROR_LINE_EXTRACTION/
     *  TOOL_OUTPUT_RECALL already are, neither of which is "compaction" in the strictest sense
     *  either) rather than threading a second, parallel enable/disable mechanism through every
     *  call site this one already reaches. On by default per #332's own decided design. */
    public static final String LESSON_RECORDING = "lesson_recording";
    public static final Set<String> KNOWN = Set.of(TOOL_RESULT_MASK, STRUCTURED_EVICTION, ERROR_LINE_EXTRACTION, TOOL_OUTPUT_RECALL, LESSON_RECORDING);
    /** today's own behavior, unchanged, when a run/task configures nothing - #423/#424 were always
     *  unconditionally on before either had an off switch, so the default must keep both on;
     *  structured_eviction stays opt-in, matching its own existing default. #332's record_lesson
     *  defaults on too, per its own issue's decided design ("a UI option, on by default"). */
    public static final List<String> DEFAULT = List.of(TOOL_RESULT_MASK, ERROR_LINE_EXTRACTION, TOOL_OUTPUT_RECALL, LESSON_RECORDING);

    /** #417 follow-up review (C6): a task override set via the restart-task endpoint never goes
     *  through RunSpec's own compact-constructor validation at all (only a run-level RunSpec
     *  does) - an untrimmed CSV entry (a human typing "a, b" rather than the UI's own "a,b") used
     *  to reach apply()'s own `default` branch silently, disabling compaction for that task
     *  entirely with zero trace. Trims each entry and drops (with a warning, not silently) anything
     *  not in KNOWN; an all-invalid result is an empty list, which apply() itself already treats as
     *  "nothing configured" (falls back to DEFAULT), never a total silent disable. */
    public static List<String> parse(final String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        final List<String> result = new ArrayList<>();
        for (final String raw : csv.split(",")) {
            final String s = raw.strip();
            if (s.isEmpty()) continue;
            if (!KNOWN.contains(s)) { log.warn("unknown compaction strategy \"{}\" ignored (must be one of {})", s, KNOWN); continue; }
            result.add(s);
        }
        return result;
    }

    private static final String SUMMARY_MARKER = "## Context summary (maintained by the harness)";

    /** Found live (#417 follow-up review): the range structured_eviction summarizes can span many
     *  turns, each with its own tool output - unlike Judge's own single diff/trajectory, there was
     *  nothing here bounding how much text that one-shot call actually sends. Same character-cap
     *  convention Judge's MAX_DIFF_CHARS/MAX_TRAJECTORY_CHARS (12k/16k) already use, scaled up since
     *  this range is many turns, not one diff - see capForSummarization() below. */
    private static final int MAX_SUMMARIZE_CHARS = 40_000;

    /** #417 follow-up review (C3): the head (system prompt, original task instruction, and any
     *  prior summary) used to be exempt from capping entirely, so an oversized one of those three
     *  alone could already exceed MAX_SUMMARIZE_CHARS before a single turn of real conversation
     *  was even considered. Each head message is capped to this individually. */
    private static final int MAX_HEAD_MESSAGE_CHARS = 10_000;

    /** the result of one apply() call: `masked` is tool_result_mask's own stub count,
     *  `summarized` is whether structured_eviction wrote a (new or updated) summary this pass -
     *  found live (#417 follow-up review, C7): these used to be summed into one int, so
     *  structured_eviction's own contribution (which never frees a single token - it only INSERTS
     *  a summary message) silently inflated the "N turns stubbed" the operator-facing transcript
     *  reports, misreporting "1" as "1 turn stubbed" when no masking happened at all. */
    public record Result(int masked, boolean summarized) {}

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
     *  site this fires from) and never invoked at all when structured_eviction isn't enabled.
     *  `state` carries just enough across repeated calls within ONE session's own turn loop to let
     *  structured_eviction skip a redundant re-summarization (see State's own javadoc) - pass a
     *  fresh State per session, never shared across sessions. */
    public static Result apply(final List<String> enabled, final List<ChatMessage> msgs, final int keepRecentTurns,
                                final int keepTokenBudget, final State state, final Function<List<ChatMessage>, String> summarize) {
        final List<String> strategies = enabled == null || enabled.isEmpty() ? DEFAULT : enabled;
        int masked = 0;
        boolean summarized = false;
        for (final String strategy : strategies) {
            switch (strategy) {
                case TOOL_RESULT_MASK -> masked += AgentSession.compact(msgs, keepRecentTurns, keepTokenBudget, strategies.contains(TOOL_OUTPUT_RECALL));
                case STRUCTURED_EVICTION -> summarized |= structuredEvict(msgs, keepRecentTurns, keepTokenBudget, state, summarize);
                // #417 follow-up review (C6): this used to be a silent `default -> 0` with a
                // comment claiming "RunSpec's own validation already rejects an unknown name
                // before it ever reaches here" - true for a run-level RunSpec, FALSE for a task
                // override set via the restart-task endpoint, which never goes through RunSpec's
                // own validation at all (see RunBench.effectiveCompactionStrategies). An untrimmed
                // CSV entry (a human typing "a, b" rather than the UI's own "a,b") used to disable
                // compaction for that task entirely with zero trace - this at least logs it.
                default -> log.warn("unknown compaction strategy \"{}\" ignored (must be one of {})", strategy, KNOWN);
            }
        }
        return new Result(masked, summarized);
    }

    /** #417 follow-up review (C9): carries just enough state across apply() calls within ONE
     *  session's own turn loop to skip a redundant re-summarization when the eligible range's own
     *  content hasn't grown since the last pass - found live: once every old tool result is
     *  already stubbed (tool_result_mask's one-shot guard returns 0 forever after), a prompt that
     *  stays over the trigger re-fires a fresh, near-identical-input summarization every single
     *  turn, each one re-deriving the same document from scratch. Tracks the TOTAL CHAR LENGTH of
     *  the tail portion being summarized, not a raw index: an index would be thrown off by
     *  structuredEvict's own insert (which shifts every later index by +1 the first time a summary
     *  is written), while the tail's own char length is unaffected by that insert (the summary
     *  itself lives in the HEAD, never the tail - see structuredEvict's own headEnd split).
     *  Mutable and single-session-scoped by construction: one ReferenceAgent.run() call, one turn
     *  loop, one owner - no concurrency concern (the loop that calls apply() already owns `msgs`
     *  exclusively). */
    public static final class State {
        private long lastTailChars = -1;
    }

    /** #417 mechanism 6: a fixed-outline summary document instead of free prose - the same pattern
     *  ACE's own inter-task handoff-notes/lessons.md already use successfully, applied within a
     *  single session. Written/updated as ONE message right after the original task instruction
     *  (index 2), replaced in place on later passes rather than piling up duplicates - "updating the
     *  same document rather than regenerating it from scratch", per the issue's own research.
     *  `cut` now comes from AgentSession.effectiveCut() (#417 follow-up review, C4) - the EXACT
     *  same boundary tool_result_mask itself protects, not cutIndex()'s turn-count-only boundary -
     *  so the two strategies can no longer diverge on what's actually "recent" (see effectiveCut's
     *  own javadoc for the blind-spot this closes). That boundary always lands on a complete turn
     *  (see cutIndex's own javadoc), so the one-shot summarization request built from it is always
     *  a structurally valid request on its own. */
    private static boolean structuredEvict(final List<ChatMessage> msgs, final int keepRecentTurns, final int keepTokenBudget,
                                            final State state, final Function<List<ChatMessage>, String> summarize) {
        final int cut = AgentSession.effectiveCut(msgs, keepRecentTurns, keepTokenBudget);
        if (cut <= 2 || summarize == null) return false;
        final boolean hasPriorSummary = msgs.get(2) instanceof UserMessage um0 && um0.singleText().startsWith(SUMMARY_MARKER);
        final int headEnd = hasPriorSummary ? 3 : 2;
        long tailChars = 0;
        for (int i = headEnd; i < cut; i++) tailChars += textLength(msgs.get(i));
        // #417 follow-up review (C9): nothing beyond the head has grown since the summary already
        // sitting at index 2 was written from this exact same tail - firing an identical call again
        // would just re-derive the same document at real token cost for no new information.
        if (state != null && state.lastTailChars == tailChars) return false;
        final List<ChatMessage> capped = capForSummarization(msgs.subList(0, cut));
        // #417 follow-up review (C1): capForSummarization can legitimately trim away EVERY turn of
        // real conversation, leaving only the head (system prompt / original instruction / prior
        // summary) - found live: one oversized tool result (e.g. a large file write) is enough.
        // Firing the summarization call anyway sends the model a request with nothing to
        // summarize; a local model dutifully fabricates LANDED/STATE/DECISIONS from the task
        // instruction alone, and that fabrication then gets committed as settled fact. Checking
        // for at least one AiMessage (always and only present in the real tail, never the head -
        // see capForSummarization's own head/tail split) is representation-agnostic: it holds
        // whether headEnd is 2 or 3.
        if (capped.stream().noneMatch(m -> m instanceof AiMessage)) return false;
        final String summary;
        try {
            summary = summarize.apply(capped);
        } catch (Exception e) {
            // never take the task down for a failed summarization call - but found live (#417
            // follow-up review): this used to be a bare `return 0` with no logger anywhere in this
            // class at all, so a failure here (a BudgetExhausted/ContextWindowExceeded is a genuine
            // RuntimeException from ReferenceAgent.chatWithRetry, not a hypothetical) was invisible -
            // nothing on disk or in the log could tell an operator structured_eviction was silently
            // doing nothing on exactly the long tasks where it matters most.
            log.warn("structured_eviction's own summarization call failed, skipping this pass", e);
            return false;
        }
        if (summary == null || summary.isBlank()) return false;
        final String text = SUMMARY_MARKER + "\n\n" + summary;
        if (hasPriorSummary) msgs.set(2, UserMessage.from(text));
        else msgs.add(2, UserMessage.from(text));
        if (state != null) state.lastTailChars = tailChars;
        return true;
    }

    /** Found live (#417 follow-up review): the range summarized here can span many turns, each with
     *  its own tool output, with nothing bounding its total size before it goes into a single
     *  advisory LLM call - unlike Judge's own capped diff/trajectory. Keeps the head (the system
     *  prompt, the original task instruction, and - #417 follow-up review, C2 - any PRIOR summary
     *  already sitting at index 2, so trimming never silently regenerates the document from
     *  scratch and loses what an earlier pass already distilled) plus the LARGEST trailing,
     *  turn-aligned portion that still fits MAX_SUMMARIZE_CHARS, the same "keep the newest part"
     *  reasoning Judge's own capTail() already applies. Turn-aligned: the cut point is rounded
     *  forward to the next AiMessage index, so an AiMessage's own tool calls are never separated
     *  from their results - the request sent to the model stays structurally valid. Each head
     *  message is ALSO capped individually (#417 follow-up review, C3) - previously only the total
     *  was checked, so an oversized head alone (a huge prior summary, say) could already exceed
     *  the whole budget before a single turn of real conversation was considered. Returns the SAME
     *  `range` instance, unchanged, when nothing needs capping - callers may rely on that identity
     *  (see CompactionStrategiesTest's own pinned test) rather than a merely-equal copy. */
    static List<ChatMessage> capForSummarization(final List<ChatMessage> range) {
        if (range.size() <= 2) return range;
        final boolean hasPriorSummary = range.get(2) instanceof UserMessage um && um.singleText().startsWith(SUMMARY_MARKER);
        final int headEnd = hasPriorSummary ? 3 : 2;
        long headChars = 0;
        boolean headOversized = false;
        for (int i = 0; i < headEnd; i++) {
            final long len = textLength(range.get(i));
            headChars += len;
            if (len > MAX_HEAD_MESSAGE_CHARS) headOversized = true;   // any ONE head message alone over its own cap, not the sum against collective capacity
        }
        long total = headChars;
        for (int i = headEnd; i < range.size(); i++) total += textLength(range.get(i));
        if (total <= MAX_SUMMARIZE_CHARS && !headOversized) return range;

        final List<ChatMessage> head = new ArrayList<>(headEnd);
        for (int i = 0; i < headEnd; i++) head.add(capMessageText(range.get(i), MAX_HEAD_MESSAGE_CHARS));
        if (headEnd >= range.size()) return head;
        long kept = 0;
        for (final ChatMessage m : head) kept += textLength(m);
        int cutFrom = range.size();
        for (int i = range.size() - 1; i >= headEnd; i--) {
            final long next = kept + textLength(range.get(i));
            if (next > MAX_SUMMARIZE_CHARS) break;
            kept = next;
            cutFrom = i;
        }
        int start = cutFrom;
        while (start < range.size() && !(range.get(start) instanceof AiMessage)) start++;
        final List<ChatMessage> trimmed = new ArrayList<>(head);
        if (start < range.size()) trimmed.addAll(range.subList(start, range.size()));
        return trimmed;
    }

    private static ChatMessage capMessageText(final ChatMessage m, final int n) {
        if (m instanceof SystemMessage s && s.text().length() > n) return SystemMessage.from(cap(s.text(), n));
        if (m instanceof UserMessage u && u.singleText().length() > n) return UserMessage.from(cap(u.singleText(), n));
        return m;
    }

    private static String cap(final String text, final int n) {
        return text.length() <= n ? text : text.substring(0, n) + "\n… [truncated at " + n + " chars]";
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
