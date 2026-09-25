package com.strgmai.ace.service.runner;

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

    private static String chat(final String ts, final String topExtra, final String respExtra, final String requestJson) {
        return chat(ts, 200, topExtra, respExtra, requestJson);
    }

    // an explicit status overrides the template's 200 - the old topExtra-only way produced a DUPLICATE
    // "status" key (RFC 8259: members SHOULD be unique; the test passed only by last-wins parsing luck)
    private static String chat(final String ts, final int status, final String topExtra, final String respExtra, final String requestJson) {
        final String usage = respExtra.contains("\"usage\"") ? "" : "\"usage\": {\"completion_tokens\": 2, \"prompt_tokens\": 50}";
        final String respSep = (respExtra.isBlank() || usage.isBlank()) ? "" : ",";
        return "{\"ts\": \"" + ts + "\", \"path\": \"/v1/chat/completions\", \"status\": " + status + topExtra
                + ", \"request\": " + requestJson + ", \"response\": {" + respExtra + respSep + usage + "}}\n";
    }

    private static final String REQ = "{\"messages\": [{\"role\": \"system\", \"content\": \"s\"}], \"tools\": [], \"temperature\": 1}";

    /** #72: top_k/repetition_penalty/reasoning_effort must reach sampler_effective too, the same
     *  way temperature/top_p already do - this is the comparability key's only real signal for
     *  "these two runs used different sampler settings" (StatsService.KEY_FIELDS "sampler"). */
    @Test
    void samplerEffectiveCapturesTheNewSamplerKnobs() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        final String req = "{\"messages\": [], \"tools\": [], \"temperature\": 0.7, \"top_p\": 0.9, "
                + "\"top_k\": 40, \"repetition_penalty\": 1.1, \"reasoning_effort\": \"high\"}";
        Files.writeString(j, chat("2026-09-25T10:00:00Z", "", "", req));
        final Map<?, ?> sampler = (Map<?, ?>) JournalFacts.facts(j.toString(), null, null, null, null, null).get("sampler_effective");
        assertEquals(40, ((Number) sampler.get("top_k")).intValue());
        assertEquals(1.1, ((Number) sampler.get("repetition_penalty")).doubleValue(), 0.001);
        assertEquals("high", sampler.get("reasoning_effort"));
    }

    @Test
    void samplerEffectiveOmitsTheNewSamplerKnobsWhenNotOnTheRequest() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, chat("2026-09-25T10:00:00Z", "", "", REQ));
        final Map<?, ?> sampler = (Map<?, ?>) JournalFacts.facts(j.toString(), null, null, null, null, null).get("sampler_effective");
        assertNull(sampler.get("top_k"));
        assertNull(sampler.get("repetition_penalty"));
        assertNull(sampler.get("reasoning_effort"));
    }

    @Test
    void windowsFilterTheCachedEntries() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, chat("2026-09-14T10:00:00Z", "", "", REQ)
                + "{\"ts\": \"2026-09-14T10:05:00Z\", \"path\": \"/v1/models\", \"status\": 200}\n"    // not a chat completion: never counted
                + chat("2026-09-14T11:00:00Z", "", "", REQ));
        assertEquals(2, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("requests")).intValue());
        assertEquals(2, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("requests")).intValue());   // served from the cache
        final Map<String, Object> w = JournalFacts.facts(j.toString(), "2026-09-14T10:30:00Z", null, null, null, null);
        assertEquals(1, ((Number) w.get("requests")).intValue());
        assertEquals(2L, w.get("completion_tokens"));
        assertEquals(1, ((Number) ((Map<?, ?>) w.get("sampler_effective")).get("temperature")).intValue());    // the first IN-WINDOW request's params
        assertEquals(50L, w.get("first_prompt_tokens"));
    }

    @Test
    void appendsAreSeenAndARewriteRebuildsTheCache() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, chat("2026-09-14T10:00:00Z", "", "", REQ));
        assertEquals(1, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("requests")).intValue());
        Files.writeString(j, chat("2026-09-14T11:00:00Z", "", "", REQ), java.nio.file.StandardOpenOption.APPEND);
        assertEquals(2, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("requests")).intValue());
        Files.writeString(j, chat("2026-09-14T12:00:00Z", "", "", REQ));    // truncated + rewritten: the cache rebuilds
        assertEquals(1, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("requests")).intValue());
    }

    @Test
    void budgetRefusalsCountSeparatelyFromErrors() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, chat("2026-09-14T10:00:00Z", 429, ", \"budget_exceeded\": true", "", REQ));
        final Map<String, Object> f = JournalFacts.facts(j.toString(), null, null, null, null, null);
        assertEquals(1, ((Number) f.get("budget_refusals")).intValue());
        assertEquals(0, ((Number) f.get("errors")).intValue());
    }

    @Test
    void foreignWorkspaceRefsNeedNormaliseToBeJudged() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
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
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, chat("2026-09-14T10:00:00Z", ", \"task\": \"T1\"", "", REQ) + chat("2026-09-14T10:01:00Z", ", \"task\": \"T2\"", "", REQ));
        assertEquals(2, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, null).get("requests")).intValue());
        assertEquals(1, ((Number) JournalFacts.facts(j.toString(), null, null, null, null, "T1").get("requests")).intValue());
    }

    @Test
    void reasoningEffortsAreCounted() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        final String hi = "{\"messages\": [], \"tools\": [], \"chat_template_kwargs\": {\"reasoning_effort\": \"high\"}}";
        final String med = "{\"messages\": [], \"tools\": [], \"chat_template_kwargs\": {\"reasoning_effort\": \"medium\"}}";
        Files.writeString(j, chat("2026-09-14T10:00:00Z", "", "", hi) + chat("2026-09-14T10:01:00Z", "", "", med)
                + chat("2026-09-14T10:02:00Z", "", "", med) + chat("2026-09-14T10:03:00Z", "", "", REQ));
        assertEquals(Map.of("high", 1, "medium", 2, "none", 1), JournalFacts.facts(j.toString(), null, null, null, null, null).get("reasoning_efforts"));
    }

    @Test
    void lastFinishAndLastPromptTokensSeeTheLatestInWindow() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, chat("2026-09-14T10:00:00Z", "", "\"finish_reason\": \"tool_calls\", \"usage\": {\"completion_tokens\": 1, \"prompt_tokens\": 100}", REQ)
                + chat("2026-09-14T11:00:00Z", "", "\"finish_reason\": \"length\", \"usage\": {\"completion_tokens\": 1, \"prompt_tokens\": 42000}", REQ));
        assertEquals("length", JournalFacts.lastFinish(j.toString(), "2026-09-14T10:30:00Z"));
        assertEquals(42000L, JournalFacts.lastPromptTokens(j.toString(), "2026-09-14T10:30:00Z"));
        Path one = track(Files.createTempFile("j-one", ".jsonl"));   // a window that covers exactly one record sees ITS finish
        Files.writeString(one, chat("2026-09-14T10:00:00Z", "", "\"finish_reason\": \"tool_calls\", \"usage\": {\"completion_tokens\": 1, \"prompt_tokens\": 100}", REQ));
        assertEquals("tool_calls", JournalFacts.lastFinish(one.toString(), "2026-09-14T09:00:00Z"));
        assertEquals(100L, JournalFacts.lastPromptTokens(one.toString(), "2026-09-14T09:00:00Z"));
    }

    /** #32: run stats had no latency/TTFT at all - RecordingProxy always journals latency_sec, and
     *  first_byte_ms on streamed requests, but nothing ever averaged them until now. */
    @Test
    void avgLatencyAndFirstByteAreAveragedAcrossChatCompletions() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j,
                chat("2026-09-14T10:00:00Z", ", \"latency_sec\": 10.0, \"first_byte_ms\": 100", "", REQ)
                        + chat("2026-09-14T10:01:00Z", ", \"latency_sec\": 20.0, \"first_byte_ms\": 300", "", REQ)
                        // a non-streamed request: latency counts, but there is no first byte time to average in
                        + chat("2026-09-14T10:02:00Z", ", \"latency_sec\": 30.0", "", REQ));
        final Map<String, Object> f = JournalFacts.facts(j.toString(), null, null, null, null, null);
        assertEquals(20.0, ((Number) f.get("avg_latency_sec")).doubleValue(), 0.001, "(10+20+30)/3");
        assertEquals(200L, ((Number) f.get("avg_first_byte_ms")).longValue(), "(100+300)/2, the non-streamed request excluded");
    }

    @Test
    void budgetRefusalsAreExcludedFromTheLatencyAverage() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j,
                chat("2026-09-14T10:00:00Z", ", \"latency_sec\": 10.0", "", REQ)
                        // a budget refusal is an instant local rejection: always latency_sec=0, not real latency
                        + chat("2026-09-14T10:01:00Z", 429, ", \"budget_exceeded\": true, \"latency_sec\": 0.0", "", REQ));
        final Map<String, Object> f = JournalFacts.facts(j.toString(), null, null, null, null, null);
        assertEquals(10.0, ((Number) f.get("avg_latency_sec")).doubleValue(), 0.001);
    }

    /** New metric: how prefill/decode speed depends on context size - each request is bucketed by
     *  its prompt_tokens into fixed 1024-token-wide buckets (bucket index = prompt_tokens / 1024), so
     *  the X axis has fine, consistent resolution regardless of the run's own context window size.
     *  oMLX's own reported per-request prompt_tokens_per_second/generation_tokens_per_second are
     *  averaged within each bucket. */
    @Test
    void speedByContextBucketsPromptTokensAndAveragesSpeedPerBucket() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j,
                chat("2026-09-14T10:00:00Z", "", "\"usage\": {\"completion_tokens\": 1, \"prompt_tokens\": 500, "
                        + "\"prompt_tokens_per_second\": 200.0, \"generation_tokens_per_second\": 40.0}", REQ)
                        + chat("2026-09-14T10:01:00Z", "", "\"usage\": {\"completion_tokens\": 1, \"prompt_tokens\": 800, "
                        + "\"prompt_tokens_per_second\": 100.0, \"generation_tokens_per_second\": 20.0}", REQ)
                        + chat("2026-09-14T10:02:00Z", "", "\"usage\": {\"completion_tokens\": 1, \"prompt_tokens\": 10000, "
                        + "\"prompt_tokens_per_second\": 90.0, \"generation_tokens_per_second\": 18.0}", REQ));
        final Map<String, Object> f = JournalFacts.facts(j.toString(), null, null, null, null, null);
        final List<Map<String, Object>> buckets = (List<Map<String, Object>>) f.get("speed_by_context");
        assertEquals(2, buckets.size());
        assertEquals(0L, buckets.get(0).get("context_lo"));
        assertEquals(1024L, buckets.get(0).get("context_hi"));
        assertEquals(2L, buckets.get(0).get("requests"));
        assertEquals(150.0, ((Number) buckets.get(0).get("avg_prefill_tok_per_sec")).doubleValue(), 0.001, "(200+100)/2");
        assertEquals(30.0, ((Number) buckets.get(0).get("avg_decode_tok_per_sec")).doubleValue(), 0.001, "(40+20)/2");
        assertEquals(9216L, buckets.get(1).get("context_lo"));
        assertEquals(10240L, buckets.get(1).get("context_hi"));
        assertEquals(1L, buckets.get(1).get("requests"));
        assertEquals(90.0, ((Number) buckets.get(1).get("avg_prefill_tok_per_sec")).doubleValue(), 0.001);
    }

    /** A run capped at a small context window (e.g. 8K) must not be flattened into 1-2 buckets by a
     *  fixed scheme - a run capped at a small context window (e.g. 8K) still gets a full spread of
     *  1024-token points, not just 1-2 buckets. Eight requests, one exactly at the start of each
     *  1024-token band from 0 to 7168, land in eight distinct buckets. */
    @Test
    void aSmallContextWindowRunStillGetsFineGrainedBuckets() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        final var sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            final long pt = i * 1024L;
            sb.append(chat("2026-09-14T10:0" + i + ":00Z", "", "\"usage\": {\"completion_tokens\": 1, \"prompt_tokens\": " + pt
                    + ", \"prompt_tokens_per_second\": 100.0, \"generation_tokens_per_second\": 20.0}", REQ));
        }
        Files.writeString(j, sb.toString());
        final Map<String, Object> f = JournalFacts.facts(j.toString(), null, null, null, null, null);
        final List<Map<String, Object>> buckets = (List<Map<String, Object>>) f.get("speed_by_context");
        assertEquals(8, buckets.size());
        assertEquals(0L, buckets.get(0).get("context_lo"));
        assertEquals(7168L, buckets.get(7).get("context_lo"));
        assertTrue(buckets.stream().allMatch(b -> ((Number) b.get("requests")).longValue() == 1));
    }

    /** A request with no prompt_tokens at all (an error/refused request) is skipped entirely -
     *  it cannot be attributed to any context-size bucket. */
    @Test
    void speedByContextIsAbsentWithNoPromptTokensAnywhere() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, "{\"ts\": \"2026-09-14T10:00:00Z\", \"path\": \"/v1/chat/completions\", \"status\": 429, "
                + "\"budget_exceeded\": true}\n");
        final Map<String, Object> f = JournalFacts.facts(j.toString(), null, null, null, null, null);
        assertFalse(f.containsKey("speed_by_context"));
    }

    @Test
    void avgLatencyAndFirstByteAreAbsentWithNothingToAverage() throws Exception {
        final Path j = track(Files.createTempFile("j", ".jsonl"));
        Files.writeString(j, "{\"ts\": \"2026-09-14T10:00:00Z\", \"path\": \"/v1/models\", \"status\": 200}\n");
        final Map<String, Object> f = JournalFacts.facts(j.toString(), null, null, null, null, null);
        assertFalse(f.containsKey("avg_latency_sec"));
        assertFalse(f.containsKey("avg_first_byte_ms"));
    }

    /** #100: CACHE used to be an unbounded ConcurrentHashMap - one entry per unique journal path
     *  ever parsed, held for the JVM's life. Confirms it's now a bounded LRU instead: parsing well
     *  more than the bound's worth of distinct journal files must never grow the cache past it. */
    @Test
    void journalCacheIsBoundedNotUnbounded() throws Exception {
        final int wellPastTheBound = 100;   // JournalFacts.MAX_CACHED_JOURNALS is 64
        for (int i = 0; i < wellPastTheBound; i++) {
            final Path j = track(Files.createTempFile("j" + i, ".jsonl"));
            Files.writeString(j, "{\"ts\": \"2026-09-14T10:00:00Z\", \"path\": \"/v1/models\", \"status\": 200}\n");
            JournalFacts.facts(j.toString(), null, null, null, null, null);
        }
        assertTrue(JournalFacts.cacheSize() < wellPastTheBound,
                "the cache must have evicted older entries, not grown to fit every file ever parsed: size=" + JournalFacts.cacheSize());
    }
}
