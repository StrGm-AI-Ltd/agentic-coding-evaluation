package com.strgmai.ace.service.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** AgentSession's JSONL persistence (header/message/compaction/end), --continue resume via
 *  loadMessages(), and usage() accounting - plus compact()'s keepRecentTurns boundary. */
class AgentSessionTest {

    private static List<ChatMessage> sixTurns() {
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

    /** Found live: keepRecentTurns=0 ("keep nothing verbatim") and a negative value both indexed
     *  idxAssist.get(idxAssist.size() - keepRecentTurns) past the end of the list, throwing
     *  IndexOutOfBoundsException and crashing the whole session instead of degrading gracefully. */
    @Test
    void keepRecentTurnsZeroStubsEverythingInsteadOfCrashing() {
        final var msgs = sixTurns();
        final int stubbed = AgentSession.compact(msgs, 0);
        assertEquals(3, stubbed, "all 3 tool results are eligible when nothing is kept verbatim");
        for (int i = 2; i < msgs.size(); i++)
            if (msgs.get(i) instanceof ToolExecutionResultMessage m)
                assertTrue(m.text().startsWith("[output dropped"), "index " + i + " must be stubbed");
    }

    @Test
    void negativeKeepRecentTurnsIsClampedToZeroInsteadOfCrashing() {
        final var msgs = sixTurns();
        assertEquals(3, AgentSession.compact(msgs, -5), "a negative value behaves exactly like 0");
    }

    @Test
    void positiveKeepRecentTurnsBehaviorIsUnaffectedByTheClamp() {
        // the normal case (already covered via AgentToolsTest with a richer fixture) - pinned here
        // too so a future change to the keep==0 branch can't silently break keep>=1
        final var msgs = sixTurns();
        final int stubbed = AgentSession.compact(msgs, 1);
        assertEquals(2, stubbed, "only the two oldest tool results are stubbed; the newest turn is kept");
        assertTrue(((ToolExecutionResultMessage) msgs.get(msgs.size() - 1)).text().startsWith("output"));
    }

    /** #417: a turn count alone never shrinks the protected window even when one of ITS OWN tool
     *  outputs is huge - the root cause #417 found live. keepRecentTurns=2 would normally protect
     *  turn1+turn2 (indices 4-7) verbatim, but turn1's own tool output is deliberately oversized here;
     *  the token ceiling must shrink the cut past it (stubbing turn1 too) while still never touching
     *  turn2, the genuinely newest one. */
    @Test
    void tokenBudgetShrinksTheProtectedWindowPastAnOversizedOlderTurnButNeverTheNewestOne() {
        final List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from("system"));
        msgs.add(UserMessage.from("the harness pack"));
        for (int i = 0; i < 3; i++) {
            final var req = ToolExecutionRequest.builder().id("c" + i).name("bash").arguments("{}").build();
            msgs.add(AiMessage.from("turn " + i, List.of(req)));
            msgs.add(ToolExecutionResultMessage.from(req, i == 1 ? "x".repeat(50_000) : "output " + i));
        }
        final int stubbed = AgentSession.compact(msgs, 2, 10);
        assertEquals(2, stubbed, "turn0's pair AND turn1's oversized pair are both stubbed");
        assertTrue(((ToolExecutionResultMessage) msgs.get(3)).text().startsWith("[output dropped"));
        assertTrue(((ToolExecutionResultMessage) msgs.get(5)).text().startsWith("[output dropped"), "turn1's oversized output must be shrunk out despite keepRecentTurns=2");
        assertEquals("output 2", ((ToolExecutionResultMessage) msgs.get(7)).text(), "the genuinely newest turn is never touched by the shrink");
    }

    /** #417 follow-up review (C10): if the SINGLE newest turn alone exceeds keepTokenBudget, the
     *  shrink loop's first iteration already breaks the budget, which - with no floor - would set
     *  cut all the way to msgs.size(): masking (or excluding from a structured_eviction summary)
     *  the very turn and tool results the model is about to act on next. effectiveCut() must never
     *  shrink past the newest AiMessage's own index, regardless of how oversized that turn is. */
    @Test
    void effectiveCutNeverShrinksPastTheNewestTurnEvenWhenThatTurnAloneExceedsTheBudget() {
        final List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from("system"));
        msgs.add(UserMessage.from("the harness pack"));
        for (int i = 0; i < 3; i++) {
            final var req = ToolExecutionRequest.builder().id("c" + i).name("bash").arguments("{}").build();
            msgs.add(AiMessage.from("turn " + i, List.of(req)));
            // the NEWEST turn's own output is the oversized one this time
            msgs.add(ToolExecutionResultMessage.from(req, i == 2 ? "x".repeat(50_000) : "output " + i));
        }
        final int newestAiIdx = msgs.size() - 2;   // index of the final turn's own AiMessage
        final int cut = AgentSession.effectiveCut(msgs, 2, 10);
        assertTrue(cut <= newestAiIdx, "cut must never exceed the newest turn's own AiMessage index: cut=" + cut + ", newestAiIdx=" + newestAiIdx);
        final int stubbed = AgentSession.compact(msgs, 2, 10);
        assertFalse(((ToolExecutionResultMessage) msgs.get(msgs.size() - 1)).text().startsWith("[output dropped"),
                "the newest turn's own (oversized) tool result must never be masked, however large it is");
    }

    @Test
    void tokenBudgetZeroOrBelowDisablesTheShrinkEntirely() {
        final var msgs = sixTurns();
        // same fixture/assertion as positiveKeepRecentTurnsBehaviorIsUnaffectedByTheClamp above,
        // just through the 3-arg form with the shrink explicitly disabled
        assertEquals(2, AgentSession.compact(msgs, 1, 0));
        assertEquals(2, AgentSession.compact(sixTurns(), 1, -5));
    }

    @Test
    void cutIndexMatchesTheOriginalTurnCountBoundary() {
        final var msgs = sixTurns();
        // sixTurns(): sys(0), user(1), then 3 turns of [AiMessage, ToolResult] at 2/3, 4/5, 6/7
        assertEquals(0, AgentSession.cutIndex(msgs, 10), "fewer turns than the floor: nothing eligible");
        assertEquals(8, AgentSession.cutIndex(msgs, 0), "keep nothing verbatim: everything eligible");
        assertEquals(6, AgentSession.cutIndex(msgs, 1), "keep the newest turn only: cut lands on its own AiMessage index");
    }

    @Test
    void loadMessagesRebuildsAllRoleTypesInOrder() throws IOException {
        final var dir = Files.createTempDirectory("session");
        final var session = new AgentSession(dir, "s1", false);
        session.system("sys prompt");
        session.user("the pack");
        final var req = ToolExecutionRequest.builder().id("c1").name("bash").arguments("{}").build();
        session.assistant(null, "doing it", List.of(req), "tool_calls", Map.of());
        session.toolResult("c1", "bash", "result text", false);

        final List<ChatMessage> msgs = session.loadMessages();
        assertEquals(4, msgs.size());
        assertEquals("sys prompt", ((SystemMessage) msgs.get(0)).text());
        assertEquals("the pack", ((UserMessage) msgs.get(1)).singleText());
        final var ai = (AiMessage) msgs.get(2);
        assertEquals("doing it", ai.text());
        assertEquals(1, ai.toolExecutionRequests().size());
        assertEquals("result text", ((ToolExecutionResultMessage) msgs.get(3)).text());
    }

    @Test
    void loadMessagesSkipsAMalformedLineInsteadOfThrowing() throws IOException {
        final var dir = Files.createTempDirectory("session");
        final var session = new AgentSession(dir, "s2", false);
        session.user("the pack");
        Files.writeString(session.path(), "not valid json\n", StandardOpenOption.APPEND);
        session.toolResult("c1", "bash", "after the corrupt line", false);

        final List<ChatMessage> msgs = session.loadMessages();
        assertEquals(2, msgs.size(), "the malformed line is skipped, not thrown");
        assertEquals("after the corrupt line", ((ToolExecutionResultMessage) msgs.get(1)).text());
    }

    @Test
    void loadMessagesReturnsEmptyWhenTheSessionFileDoesNotExistYet() throws IOException {
        final var dir = Files.createTempDirectory("session");
        final var session = new AgentSession(dir, "never-written", false);
        assertEquals(List.of(), session.loadMessages());
    }

    /** #305, found live: a task restarted under the SAME canonical session id (a paused/resumed
     *  job, same as every other R18 restart) leaves its OLD, abandoned file sitting next to the new
     *  one - both contain the id in their filename. Files.list() gives no ordering guarantee, so
     *  continueSession=true (the length-retry continuation, wrap-up, and the handoff fast path's own
     *  cold fallback all use it) must not just take whichever one a filesystem happens to list
     *  first; it must resolve to the NEWEST attempt, never a stale, superseded one. */
    @Test
    void continueSessionResolvesToTheNewestFileWhenMultipleAttemptsShareTheSameId() throws IOException {
        final var dir = Files.createTempDirectory("session");
        final var abandoned = new AgentSession(dir, "shared-id", false);
        abandoned.user("the first, abandoned attempt - interrupted almost immediately");
        // a later attempt always gets a LATER timestamp prefix in its own filename - force that
        // ordering explicitly rather than relying on two calls a millisecond apart
        Files.move(abandoned.path(), dir.resolve("2020-01-01T00-00-00.000Z_shared-id.jsonl"));
        final var real = new AgentSession(dir, "shared-id", false);
        real.user("the real, final attempt - a hundred real turns of work");
        Files.move(real.path(), dir.resolve("2030-01-01T00-00-00.000Z_shared-id.jsonl"));

        final var continuation = new AgentSession(dir, "shared-id", true);
        assertEquals("2030-01-01T00-00-00.000Z_shared-id.jsonl", continuation.path().getFileName().toString());
        assertEquals(List.of("the real, final attempt - a hundred real turns of work"),
                continuation.loadMessages().stream().map(m -> ((UserMessage) m).singleText()).toList());
    }

    @Test
    void usageAggregatesAcrossAssistantRecordsOnly() throws IOException {
        final var dir = Files.createTempDirectory("session");
        final var session = new AgentSession(dir, "s3", false);
        session.user("the pack");   // a non-assistant record must not count toward "turns"
        session.assistant(null, "a", List.of(), "stop", Map.of("input", 100, "output", 10));
        session.assistant(null, "b", List.of(), "stop", Map.of("input", 150, "output", 20));

        final Map<String, Long> u = AgentSession.usage(session.path());
        assertEquals(250L, u.get("input"));
        assertEquals(30L, u.get("output"));
        assertEquals(2L, u.get("turns"));
    }

    /** #417 follow-up review (C8): structured_eviction's own one-shot summarization call spends
     *  real tokens RecordingProxy already charges to the task, but nothing recorded it in the
     *  SESSION file's own accounting - usage() (the fallback token source for a session with no
     *  proxy) silently undercounted it. sidecarUsage()/usage()'s own "sidecar_usage" handling must
     *  (a) be summed separately, not folded into usage()'s own "turns"-counted assistant tally,
     *  and (b) never corrupt loadMessages()' resumed conversation (it only ever reads type=="message"). */
    @Test
    void sidecarUsageIsCountedSeparatelyFromOrdinaryTurnsAndNeverReentersLoadMessagesOnResume() throws IOException {
        final var dir = Files.createTempDirectory("session");
        final var session = new AgentSession(dir, "s3b", false);
        session.user("the pack");
        session.assistant(null, "a", List.of(), "stop", Map.of("input", 100, "output", 10));
        session.sidecarUsage("structured_eviction", 500, 50);

        final Map<String, Long> u = AgentSession.usage(session.path());
        assertEquals(100L, u.get("input"), "sidecar usage must not be folded into the ordinary-turn tally");
        assertEquals(1L, u.get("turns"), "a sidecar call is not a conversation turn");
        final Map<String, Long> sidecar = AgentSession.sidecarUsage(session.path());
        assertEquals(500L, sidecar.get("input"));
        assertEquals(50L, sidecar.get("output"));

        final var resumed = new AgentSession(dir, "s3b", true);
        assertEquals(List.of("the pack"), resumed.loadMessages().stream()
                .filter(m -> m instanceof UserMessage).map(m -> ((UserMessage) m).singleText()).toList());
    }

    /** #424: the tool-output archive is the session file itself - the (already AgentTools-capped)
     *  text toolResult() already writes, recallable by the same id the stub text now names. */
    @Test
    void recallToolOutputFindsTheOriginalTextByToolCallId() throws IOException {
        final var dir = Files.createTempDirectory("session");
        final var session = new AgentSession(dir, "s4", false);
        session.toolResult("c1", "bash", "the real, original output", false);
        assertEquals("the real, original output", AgentSession.recallToolOutput(session.path(), "c1"));
    }

    @Test
    void recallToolOutputSurvivesMaskingSinceCompactionNeverTouchesTheSessionFile() throws IOException {
        final var dir = Files.createTempDirectory("session");
        final var session = new AgentSession(dir, "s5", false);
        final var req = ToolExecutionRequest.builder().id("c1").name("bash").arguments("{}").build();
        session.toolResult("c1", "bash", "the real, original output", false);
        final List<ChatMessage> msgs = new ArrayList<>(List.of(
                SystemMessage.from("system"), UserMessage.from("pack"),
                AiMessage.from("turn", List.of(req)), ToolExecutionResultMessage.from(req, "the real, original output")));
        AgentSession.compact(msgs, 0);   // masks the in-memory copy - the session FILE is untouched
        assertTrue(((ToolExecutionResultMessage) msgs.get(3)).text().startsWith("[output dropped"));
        assertEquals("the real, original output", AgentSession.recallToolOutput(session.path(), "c1"));
    }

    @Test
    void recallToolOutputReturnsNullForAnUnknownIdOrMissingSession() {
        assertNull(AgentSession.recallToolOutput(Path.of("/no/such/session.jsonl"), "c1"));
        assertNull(AgentSession.recallToolOutput(null, "c1"));
    }

    /** #424: the stub text's own prefix is load-bearing (the one-shot re-stub guard checks it) -
     *  this pins that the new wording still starts with it, and still names a recallable id. */
    @Test
    void stubTextKeepsTheLoadBearingPrefixAndNamesTheRecallableId() {
        final var msgs = sixTurns();
        AgentSession.compact(msgs, 0);
        final String text = ((ToolExecutionResultMessage) msgs.get(3)).text();
        assertTrue(text.startsWith("[output dropped"), "the one-shot guard's own prefix must survive");
        assertTrue(text.contains("recall_tool_output(tool_call_id=\"c0\")"), text);
    }

    @Test
    void usageReturnsZeroedDefaultsForAMissingOrNullFile() {
        final Map<String, Long> missing = AgentSession.usage(Path.of("/no/such/session.jsonl"));
        assertEquals(0L, missing.get("input"));
        assertEquals(0L, missing.get("output"));
        assertEquals(0L, missing.get("turns"));
        assertEquals(missing, AgentSession.usage(null));
    }
}
