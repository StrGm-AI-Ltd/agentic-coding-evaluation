package com.agentbench.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Port of the JournalFacts tests (including the parse-cache behaviours: windows, appends,
 *  rewrites, the first in-window request's sampler facts, foreign workspace refs). */
class JournalFactsTest {

    // every test builds a temp .jsonl; track and delete them so CI/local runs don't accumulate
        private final Set<Path> temps = new HashSet<>();

    private Path track(Path p) { temps.add(p); return p; }

    @AfterEach
    void cleanUp() throws IOException {
        for (Path p : temps) Files.deleteIfExists(p);
        temps.clear();
    }

    private static String chat(String ts, String topExtra, String respExtra, String requestJson) {
        String usage = respExtra.contains("\"usage\"") ? "" : "\"usage\": {\"completion_tokens\": 2, \"prompt_tokens\": 50}";
        String respSep = (respExtra.isBlank() || usage.isBlank()) ? "" : ",";
        return "{\"ts\": \"" + ts + "\", \"path\": \"/v1/chat/completions\", \"status\": 200" + topExtra
                + ", \"request\": " + requestJson + ", \"response\": {" + respExtra + respSep + usage + "}}\n";
    }

    private static final String REQ = "{\"messages\": [{\"role\": \"system\", \"content\": \"s\"}], \"tools\": [], \"temperature\": 1}";

    @Test
    void windowsFilterTheCachedEntries() throws Exception {
        Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, chat("2026-09-14T10:00:00Z", "", "", REQ)
                + "{\"ts\": \"2026-09-14T10:05:00Z\", \"path\": \"/v1/models\", \"status\": 200}\n"    // not a chat completion: never counted
                + chat("2026-09-14T11:00:00Z", "", "", REQ));
        assertEquals(2, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("requests")).intValue());
        assertEquals(2, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("requests")).intValue());   // served from the cache
        Map<String, Object> w = JournalFacts.facts(j.toString(), "2026-09-14T10:30:00Z", null, null, null, null);
        assertEquals(1, ((Number) w.get("requests")).intValue());
        assertEquals(2L, w.get("completion_tokens"));
        assertEquals(1, ((Number) ((Map<?, ?>) w.get("sampler_effective")).get("temperature")).intValue());    // the first IN-WINDOW request's params
        assertEquals(50L, w.get("first_prompt_tokens"));
    }

    @Test
    void appendsAreSeenAndARewriteRebuildsTheCache() throws Exception {
        Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, chat("2026-09-14T10:00:00Z", "", "", REQ));
        assertEquals(1, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("requests")).intValue());
        Files.writeString(j, chat("2026-09-14T11:00:00Z", "", "", REQ), java.nio.file.StandardOpenOption.APPEND);
        assertEquals(2, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("requests")).intValue());
        Files.writeString(j, chat("2026-09-14T12:00:00Z", "", "", REQ));    // truncated + rewritten: the cache rebuilds
        assertEquals(1, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("requests")).intValue());
    }

    @Test
    void budgetRefusalsCountSeparatelyFromErrors() throws Exception {
        Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, chat("2026-09-14T10:00:00Z", ", \"status\": 429, \"budget_exceeded\": true", "", REQ));
        Map<String, Object> f = JournalFacts.facts(j.toString(), null, null, null, null, null);
        assertEquals(1, ((Number) f.get("budget_refusals")).intValue());
        assertEquals(0, ((Number) f.get("errors")).intValue());
    }

    @Test
    void foreignWorkspaceRefsNeedNormaliseToBeJudged() throws Exception {
        Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, "{\"ts\": \"2026-09-14T10:00:00Z\", \"path\": \"/v1/chat/completions\", \"status\": 200, "
                + "\"request\": {\"messages\": [{\"role\": \"user\", \"content\": \"see /tmp/agentbench-ws/run2/workspace/x\"}]}, "
                + "\"response\": {\"usage\": {\"completion_tokens\": 1}}}\n");
        assertEquals(0, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("foreign_workspace_refs")).intValue());
        assertEquals(1, ((Number) JournalFacts.facts(j.toString(), null, null, null,
                List.of("/tmp/agentbench-ws/run1/workspace"), null).get("foreign_workspace_refs")).intValue());   // another run's workspace
        assertEquals(0, ((Number) JournalFacts.facts(j.toString(), null, null, null,
                List.of("/tmp/agentbench-ws/run2/workspace"), null).get("foreign_workspace_refs")).intValue());   // its own
    }

    @Test
    void tagFiltersParallelTasksRecords() throws Exception {
        Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, chat("2026-09-14T10:00:00Z", ", \"task\": \"T1\"", "", REQ) + chat("2026-09-14T10:01:00Z", ", \"task\": \"T2\"", "", REQ));
        assertEquals(2, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("requests")).intValue());
        assertEquals(1, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, "T1").get("requests")).intValue());
    }

    @Test
    void reasoningEffortsAreCounted() throws Exception {
        Path j = track(Files.createTempFile("j", ".jsonl"));
        String hi = "{\"messages\": [], \"tools\": [], \"chat_template_kwargs\": {\"reasoning_effort\": \"high\"}}";
        String med = "{\"messages\": [], \"tools\": [], \"chat_template_kwargs\": {\"reasoning_effort\": \"medium\"}}";
        Files.writeString(j, chat("2026-09-14T10:00:00Z", "", "", hi) + chat("2026-09-14T10:01:00Z", "", "", med)
                + chat("2026-09-14T10:02:00Z", "", "", med) + chat("2026-09-14T10:03:00Z", "", "", REQ));
        assertEquals(Map.of("high", 1, "medium", 2, "none", 1), JournalFacts.facts(j.toString(), null, null, null, null, null).get("reasoning_efforts"));
    }

    @Test
    void lastFinishAndLastPromptTokensSeeTheLatestInWindow() throws Exception {
        Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, chat("2026-09-14T10:00:00Z", "", "\"finish_reason\": \"tool_calls\", \"usage\": {\"completion_tokens\": 1, \"prompt_tokens\": 100}", REQ)
                + chat("2026-09-14T11:00:00Z", "", "\"finish_reason\": \"length\", \"usage\": {\"completion_tokens\": 1, \"prompt_tokens\": 42000}", REQ));
        assertEquals("length", JournalFacts.lastFinish(j.toString(), "2026-09-14T10:30:00Z"));
        assertEquals(42000L, JournalFacts.lastPromptTokens(j.toString(), "2026-09-14T10:30:00Z"));
        Path one = track(Files.createTempFile("j-one", ".jsonl"));   // a window that covers exactly one record sees ITS finish
        Files.writeString(one, chat("2026-09-14T10:00:00Z", "", "\"finish_reason\": \"tool_calls\", \"usage\": {\"completion_tokens\": 1, \"prompt_tokens\": 100}", REQ));
        assertEquals("tool_calls", JournalFacts.lastFinish(one.toString(), "2026-09-14T09:00:00Z"));
        assertEquals(100L, JournalFacts.lastPromptTokens(one.toString(), "2026-09-14T09:00:00Z"));
    }
}
