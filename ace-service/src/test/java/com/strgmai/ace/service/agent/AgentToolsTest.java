package com.strgmai.ace.service.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Ports of the agent-tool behaviours: exact-match edits with uniqueness, ranged reads with caps,
 *  bash output hygiene (head+tail), and the structural compaction rules. */
class AgentToolsTest {

    @Test
    void editRequiresAnExactUniqueMatch() throws IOException {
        final var ws = Files.createTempDirectory("ws");
        final Path f = ws.resolve("code.java");
        Files.writeString(f, "int total = 1;\nint total = 2;\n");
        final AgentTools.Outcome notFound = AgentTools.edit(ws.toString(), Map.of("path", "code.java", "old_string", "missing", "new_string", "x"));
        assertTrue(notFound.isError());
        assertTrue(notFound.output().contains("not found"));
        final AgentTools.Outcome ambiguous = AgentTools.edit(ws.toString(), Map.of("path", "code.java", "old_string", "int total", "new_string", "x"));
        assertTrue(ambiguous.isError());
        assertTrue(ambiguous.output().contains("occurs 2 times"));
        final AgentTools.Outcome ok = AgentTools.edit(ws.toString(), Map.of("path", "code.java", "old_string", "int total = 1;", "new_string", "long total = 1;"));
        assertFalse(ok.isError());
        assertEquals("long total = 1;\nint total = 2;\n", Files.readString(f));
        final AgentTools.Outcome all = AgentTools.edit(ws.toString(), Map.of("path", "code.java", "old_string", "total", "new_string", "sum", "replace_all", true));
        assertFalse(all.isError());
        assertFalse(Files.readString(f).contains("total"));
    }

    @Test
    void readIsRangedAndCapped() throws IOException {
        final var ws = Files.createTempDirectory("ws");
        final Path big = ws.resolve("big.txt");
        Files.writeString(big, (String.join("", java.util.Collections.nCopies(500, "line\n"))));
        final AgentTools.Outcome all = AgentTools.read(ws.toString(), Map.of("path", "big.txt"));
        assertFalse(all.isError());
        // the read caps at READ_MAX_LINES (250) of the 500: the tail must announce the unread remainder -
        // the old substring check could never match because every line carries its "%5d: " prefix
        assertTrue(all.output().contains("more lines"), "the default read must cap the line count");   // capped at READ_MAX_LINES
        assertFalse(all.output().contains("  300:"), "line 300 must be beyond the 250-line cap");
        final AgentTools.Outcome range = AgentTools.read(ws.toString(), Map.of("path", "big.txt", "start_line", 3, "end_line", 5));
        assertTrue(range.output().contains("more lines"), range.output());   // the tail says how much was not read
        final AgentTools.Outcome missing = AgentTools.read(ws.toString(), Map.of("path", "nope.txt"));
        assertTrue(missing.isError());
    }

    @Test
    void bashOutputIsHeadTailCappedAndCarriesTheExitCode() throws IOException {
        final var ws = Files.createTempDirectory("ws");
        final AgentTools.Outcome out = AgentTools.bash(ws.toString(), Map.of("command", "seq 1 200"), Map.of());
        assertFalse(out.isError());
        assertTrue(out.output().startsWith("1\n"), out.output().substring(0, 20));
        assertTrue(out.output().contains("lines omitted"), "long output must be head+tail capped");
        assertTrue(out.output().contains("[exit 0]"));
        final AgentTools.Outcome fail = AgentTools.bash(ws.toString(), Map.of("command", "exit 3"), Map.of());
        assertTrue(fail.isError());
        assertTrue(fail.output().contains("[exit 3]"));
        final AgentTools.Outcome ansi = AgentTools.bash(ws.toString(), Map.of("command", "printf '\\033[31mred\\033[0m\\n'"), Map.of());
        assertFalse(ansi.output().contains("\u001b[31m"), "ANSI must be stripped");
    }

    @Test
    void compactionStubsOnlyOldestToolOutputsAndNeverThePack() {
        // system, pack (first user), then alternating assistant/toolResult pairs
        final dev.langchain4j.data.message.SystemMessage system = dev.langchain4j.data.message.SystemMessage.from("system");
        final dev.langchain4j.data.message.UserMessage pack = dev.langchain4j.data.message.UserMessage.from("the harness pack");
        final var req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c1").name("bash").arguments("{}").build();
        final java.util.List<dev.langchain4j.data.message.ChatMessage> msgs = new java.util.ArrayList<>();
        msgs.add(system); msgs.add(pack);
        for (int i = 0; i < 6; i++) {
            final var tr = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c" + i).name("bash").arguments("{}").build();
            msgs.add(dev.langchain4j.data.message.AiMessage.from("turn " + i, java.util.List.of(tr)));
            msgs.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(tr, "output " + i + " " + "x".repeat(50)));
        }
        final int stubbed = AgentSession.compact(msgs, 3);
        assertTrue(stubbed >= 2, "old tool outputs must be stubbed, got " + stubbed);
        assertEquals("system", ((dev.langchain4j.data.message.SystemMessage) msgs.get(0)).text());            // untouched
        assertTrue(((dev.langchain4j.data.message.ToolExecutionResultMessage) msgs.get(msgs.size() - 1)).text().startsWith("output"));   // recent kept
        assertTrue(((dev.langchain4j.data.message.ToolExecutionResultMessage) msgs.get(3)).text().startsWith("[output dropped"));         // oldest stubbed
    }

    @Test
    void theSessionFileResumesWithItsMessages() throws IOException {
        final var dir = Files.createTempDirectory("sessions");
        final var s = new AgentSession(dir, "sid-1", false);
        s.header(AgentToolsTest.class.getSimpleName(), "m", "/ws", null);
        s.system("system prompt");
        s.user("do it");
        final var req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c1").name("bash").arguments("{\"command\":\"ls\"}").build();
        s.assistant(null, "thinking", java.util.List.of(req), "tool_calls", Map.of("input", 10, "output", 5));
        s.toolResult("c1", "bash", "the output", false);
        final var resumed = new AgentSession(dir, "sid-1", true);
        assertEquals(s.path(), resumed.path());
        final var msgs = resumed.loadMessages();
        assertEquals(4, msgs.size());   // system + user + assistant(toolCall) + toolResult
        assertTrue(msgs.get(0) instanceof dev.langchain4j.data.message.SystemMessage);
        assertTrue(msgs.get(2) instanceof dev.langchain4j.data.message.AiMessage);
        assertTrue(msgs.get(3) instanceof dev.langchain4j.data.message.ToolExecutionResultMessage);
    }
}
