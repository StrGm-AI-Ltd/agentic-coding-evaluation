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

    @Test
    void usageReturnsZeroedDefaultsForAMissingOrNullFile() {
        final Map<String, Long> missing = AgentSession.usage(Path.of("/no/such/session.jsonl"));
        assertEquals(0L, missing.get("input"));
        assertEquals(0L, missing.get("output"));
        assertEquals(0L, missing.get("turns"));
        assertEquals(missing, AgentSession.usage(null));
    }
}
