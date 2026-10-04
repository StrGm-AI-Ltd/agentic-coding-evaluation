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

    /** #243: found live 2026-10-04: zsh -lc is a LOGIN shell, so it sources /etc/zprofile - which on
     *  macOS unconditionally runs path_helper and rebuilds $PATH with system dirs first, silently
     *  demoting whatever this scrubbed env's own PATH put first (the docker shim) to the very end.
     *  Confirmed live: the docker shim's own invocation log was never written across an entire
     *  run, because every `docker` call resolved straight past it to a real CLI elsewhere on the
     *  rebuilt PATH. This pins that a scrubbed PATH's own first entry survives regardless. */
    @Test
    void bashPreservesAScrubbedPathsOwnOrderingDespiteTheLoginShellsPathHelper() throws IOException {
        final var ws = Files.createTempDirectory("ws");
        final var shimDir = Files.createTempDirectory("shimdir").toString();
        final AgentTools.Outcome out = AgentTools.bash(ws.toString(), Map.of("command", "echo $PATH"), Map.of("PATH", shimDir + ":/usr/bin:/bin"));
        assertFalse(out.isError(), out.output());
        assertTrue(out.output().startsWith(shimDir + ":"), "the scrubbed PATH's own first entry must still be first: " + out.output());
    }

    @Test
    void bashLeavesPathAloneWhenTheEnvDoesNotSetOne() throws IOException {
        // a bare unit-test env (no PATH at all) must behave exactly as before this fix - path_helper's
        // own system defaults still apply, nothing here forces PATH to an empty string
        final var ws = Files.createTempDirectory("ws");
        final AgentTools.Outcome out = AgentTools.bash(ws.toString(), Map.of("command", "echo hi"), Map.of());
        assertFalse(out.isError(), out.output());
        assertTrue(out.output().startsWith("hi"), out.output());
    }

    @Test
    void shellQuoteEscapesEmbeddedSingleQuotes() {
        assertEquals("'it'\\''s'", AgentTools.shellQuote("it's"));
        assertEquals("'plain'", AgentTools.shellQuote("plain"));
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
    void toolsRefuseToEscapeTheWorkspace() throws IOException {
        final var ws = Files.createTempDirectory("ws");
        final var outside = Files.createTempFile("outside", ".txt");
        Files.writeString(outside, "secret");

        final AgentTools.Outcome readOutside = AgentTools.read(ws.toString(), Map.of("path", outside.toString()));
        assertTrue(readOutside.isError());
        assertTrue(readOutside.output().contains("escapes the task workspace"));

        final AgentTools.Outcome writeOutside = AgentTools.write(ws.toString(), Map.of("path", outside.toString(), "content", "pwned"));
        assertTrue(writeOutside.isError());
        assertTrue(writeOutside.output().contains("escapes the task workspace"));
        assertEquals("secret", Files.readString(outside), "the write must never reach the file outside the workspace");

        final AgentTools.Outcome editOutside = AgentTools.edit(ws.toString(), Map.of("path", outside.toString(), "old_string", "secret", "new_string", "pwned"));
        assertTrue(editOutside.isError());
        assertTrue(editOutside.output().contains("escapes the task workspace"));
        assertEquals("secret", Files.readString(outside));

        // a ".."-climbing relative path is exactly as much an escape as an absolute one
        final AgentTools.Outcome traversal = AgentTools.write(ws.toString(), Map.of("path", "../" + outside.getFileName(), "content", "pwned"));
        assertTrue(traversal.isError());
        assertEquals("secret", Files.readString(outside));

        // a path that stays inside the workspace must still work normally
        final AgentTools.Outcome ok = AgentTools.write(ws.toString(), Map.of("path", "inside.txt", "content", "fine"));
        assertFalse(ok.isError());
    }

    @Test
    void theSessionFileResumesWithItsMessages() throws IOException {
        final var dir = Files.createTempDirectory("sessions");
        final var s = new AgentSession(dir, "sid-1", false);
        s.header(AgentToolsTest.class.getSimpleName(), "m", "/ws", null, "T1");
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
