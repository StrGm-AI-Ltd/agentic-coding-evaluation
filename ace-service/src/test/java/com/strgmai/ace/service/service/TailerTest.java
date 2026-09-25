package com.strgmai.ace.service.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Port of the Tailer tests including the byte-exact UTF-8 offset fix: a journal line with
 *  invalid bytes must not drift the offset past what was consumed, or the next line is lost. */
class TailerTest {

    @Test
    void invalidUtf8InTheJournalDoesNotLoseSubsequentLines() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        final Path interactions = runDir.resolve("interactions.jsonl");
        // line 1 carries two invalid bytes (0xFF 0xFE): replacing them and re-encoding to count the offset would
        // advance past what was actually consumed, so the next line would start mid-way and be lost
        byte[] firstTwo = concat(concat(
                "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200, \"request\": \"".getBytes(),
                new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) ' ', (byte) 'b', (byte) 'r', (byte) 'o', (byte) 'k', (byte) 'e', (byte) 'n', (byte) 'u', (byte) 't', (byte) 'f', (byte) '8', (byte) '\"', (byte) '}', (byte) '\n'}),
                "{\"seq\": 2, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200}\n".getBytes());
        Files.write(interactions, firstTwo);
        final var tailer = new Tailer();

        final List<Object> seqs = new java.util.ArrayList<>();
        tailer.poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).forEach(e -> seqs.add(e.get("seq")));
        Files.write(interactions, concat(firstTwo,
                "{\"seq\": 3, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200}\n".getBytes()));
        tailer.poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).forEach(e -> seqs.add(e.get("seq")));

        assertEquals(List.of(1, 2, 3), seqs);
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void aPartiallyWrittenLineWaitsForTheNextPoll() throws Exception {
        Path runDir = Files.createTempDirectory("tailer");
        final Path interactions = runDir.resolve("interactions.jsonl");
        Files.writeString(interactions, "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/c");   // no newline yet
        final var tailer = new Tailer();
        assertTrue(tailer.poll(runDir).isEmpty());
        Files.writeString(interactions, "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200}\n");
        final List<Map<String, Object>> events = tailer.poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).toList();
        assertEquals(1, events.size());
        assertTrue(tailer.poll(runDir).isEmpty());   // a no-op poll reports nothing new
    }

    /** The live requests grid's latency/ttft columns always read "-", even mid-run: RecordingProxy
     *  always journals latency_sec and first_byte_ms, but tailRequests() parsed the line and just
     *  never forwarded either field into the "request" SSE event. */
    @Test
    void latencyAndTtftAreForwardedFromTheJournalIntoTheRequestEvent() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.writeString(runDir.resolve("interactions.jsonl"),
                "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200, "
                        + "\"latency_sec\": 12.34, \"first_byte_ms\": 340}\n");
        final var events = new Tailer().poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).toList();
        assertEquals(1, events.size());
        assertEquals(12.34, (Double) events.get(0).get("latency_sec"), 0.001);
        // first_byte_ms is milliseconds; the client's field name implies seconds - converted here
        assertEquals(0.34, (Double) events.get(0).get("ttft_sec"), 0.001);
    }

    /** A non-streamed request never gets a first_byte_ms - ttft_sec must be null, not 0 (0 would
     *  misleadingly read as an instant response). latency_sec is always present in practice, but the
     *  read is defensive the same way. */
    @Test
    void ttftIsNullNotZeroWhenTheRequestWasNotStreamed() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.writeString(runDir.resolve("interactions.jsonl"),
                "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200, \"latency_sec\": 5.0}\n");
        final var events = new Tailer().poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).toList();
        assertEquals(5.0, (Double) events.get(0).get("latency_sec"), 0.001);
        assertNull(events.get(0).get("ttft_sec"));
    }

    /** RecordingProxy stamps the agent's X-Ace-Session-Id header onto the journal line as
     *  "session_id"; oMLX reports prefill/decode speed itself in response.usage. Both must reach
     *  the live "request" event for the sessions grid's per-session speed averages. */
    @Test
    void sessionIdAndSpeedAreForwardedFromTheJournalIntoTheRequestEvent() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.writeString(runDir.resolve("interactions.jsonl"),
                "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200, "
                        + "\"session_id\": \"755478fc-f323-565e-92f8-1a5b10584e41\", "
                        + "\"response\": {\"usage\": {\"prompt_tokens_per_second\": 99.15, \"generation_tokens_per_second\": 24.05}}}\n");
        final var events = new Tailer().poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).toList();
        assertEquals(1, events.size());
        assertEquals("755478fc-f323-565e-92f8-1a5b10584e41", events.get(0).get("session_id"));
        assertEquals(99.15, (Double) events.get(0).get("prefill_tok_per_sec"), 0.001);
        assertEquals(24.05, (Double) events.get(0).get("decode_tok_per_sec"), 0.001);
    }

    /** No X-Ace-Session-Id header (a run predating this feature, or a non-agent caller) and no
     *  usage block (an error/refused request) - both must be absent, not a crash. */
    @Test
    void sessionIdAndSpeedAreNullWhenTheJournalLineHasNeither() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.writeString(runDir.resolve("interactions.jsonl"),
                "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 429, \"budget_exceeded\": true}\n");
        final var events = new Tailer().poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).toList();
        assertNull(events.get(0).get("session_id"));
        assertNull(events.get(0).get("prefill_tok_per_sec"));
        assertNull(events.get(0).get("decode_tok_per_sec"));
        assertNull(events.get(0).get("completion_tokens"));
    }

    /** The sessions grid's new "total tokens" column sums this per session - unlike
     *  budget_spent_completion_tokens (a per-proxy running counter that resets on a continuation's
     *  fresh proxy), this is the request's own actual completion token count. */
    @Test
    void completionTokensAreForwardedFromTheJournalIntoTheRequestEvent() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.writeString(runDir.resolve("interactions.jsonl"),
                "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200, "
                        + "\"response\": {\"usage\": {\"completion_tokens\": 340}}}\n");
        final var events = new Tailer().poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).toList();
        assertEquals(340L, events.get(0).get("completion_tokens"));
    }

    /** RecordingProxy journals abort_reason alongside client_aborted (2026-09-25) so the requests
     *  grid can show WHY an aborted request was aborted, not just that it was. */
    @Test
    void abortReasonIsForwardedFromTheJournalIntoTheRequestEvent() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.writeString(runDir.resolve("interactions.jsonl"),
                "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200, "
                        + "\"client_aborted\": true, \"abort_reason\": \"task-wall budget exceeded (900s)\"}\n");
        final var events = new Tailer().poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).toList();
        assertEquals(true, events.get(0).get("client_aborted"));
        assertEquals("task-wall budget exceeded (900s)", events.get(0).get("abort_reason"));
    }

    @Test
    void abortReasonIsNullWhenTheRequestWasNotAborted() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.writeString(runDir.resolve("interactions.jsonl"),
                "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200}\n");
        final var events = new Tailer().poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).toList();
        assertNull(events.get(0).get("abort_reason"));
    }

    @Test
    void aRunWithNoFilesReportsNothing() throws Exception {
        assertTrue(new Tailer().poll(Files.createTempDirectory("empty")).isEmpty());
    }

    @Test
    void stepsAreReportedFromPackAndInstructionFiles() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.createDirectories(runDir.resolve("packs"));
        Files.writeString(runDir.resolve("packs/T1.md"), "pack");
        Files.writeString(runDir.resolve("packs/T1-continue.md"), "cont");
        Files.writeString(runDir.resolve("packs/stable.md"), "the persistent pack, not a step");
        final List<Map<String, Object>> events = new Tailer().poll(runDir);
        assertEquals(2, events.size());   // T1 and its continuation; stable is not a step
        assertTrue(events.stream().anyMatch(e -> "T1".equals(e.get("step")) && Boolean.TRUE.equals(e.get("continuation"))));
    }

    private static final String SESSION_HEADER = "{\"type\": \"session\", \"id\": \"755478fc-f323-565e-92f8-1a5b10584e41\", "
            + "\"model\": \"m\", \"ts\": \"t\", \"agent\": \"a\", \"label\": \"T2\"}\n";

    /** AgentSession.header() writes the stage/task name it's actually running (RunBench's "name"
     *  argument) as "label" - the sessions grid's new "description" column is built from this. */
    @Test
    void sessionStartedForwardsTheSessionsLabel() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.createDirectories(runDir.resolve("sessions"));
        Files.writeString(runDir.resolve("sessions/2026-01-01T00-00-00.000Z_755478fc-f323-565e-92f8-1a5b10584e41.jsonl"), SESSION_HEADER);
        final var events = new Tailer().poll(runDir);
        final var started = events.stream().filter(e -> "session_started".equals(e.get("type"))).findFirst().orElseThrow();
        assertEquals("T2", started.get("label"));
    }

    /** The sessions grid's new "wall time" column needs the session's own start ts, computed against
     *  its "end" ts once the session finishes (see sessionEndForwardsItsOwnTimestamp below). */
    @Test
    void sessionStartedForwardsItsTimestamp() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.createDirectories(runDir.resolve("sessions"));
        Files.writeString(runDir.resolve("sessions/2026-01-01T00-00-00.000Z_755478fc-f323-565e-92f8-1a5b10584e41.jsonl"), SESSION_HEADER);
        final var events = new Tailer().poll(runDir);
        final var started = events.stream().filter(e -> "session_started".equals(e.get("type"))).findFirst().orElseThrow();
        assertEquals("t", started.get("ts"));
    }

    /** The live sessions grid always showed "running", even long after a session had genuinely
     *  finished: the old detection ported from Python looked for a "done after N turns (finish=X)"
     *  line in a *.log file nothing in this Java runner ever writes. The real signal is a session's
     *  own trailing {"type":"end"} line - this must be read and reported with the session's real id
     *  (not guessed as "the oldest still-open session", which also gets it wrong once sessions can
     *  finish out of start order). */
    @Test
    void sessionEndEmitsSessionDoneWithTheRealSessionIdAndFinishReason() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.createDirectories(runDir.resolve("sessions"));
        Files.writeString(runDir.resolve("sessions/2026-01-01T00-00-00.000Z_755478fc-f323-565e-92f8-1a5b10584e41.jsonl"),
                SESSION_HEADER + "{\"type\": \"end\", \"finish\": \"stop\", \"turns\": 2}\n");
        final var events = new Tailer().poll(runDir);
        final var done = events.stream().filter(e -> "session_done".equals(e.get("type"))).findFirst().orElseThrow();
        assertEquals("755478fc-f323-565e-92f8-1a5b10584e41", done.get("session_id"));
        assertEquals("stop", done.get("finish"));
    }

    /** Paired with the session's own start ts (sessionStartedForwardsItsTimestamp) to compute the
     *  sessions grid's "wall time" column. */
    @Test
    void sessionEndForwardsItsOwnTimestamp() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.createDirectories(runDir.resolve("sessions"));
        Files.writeString(runDir.resolve("sessions/2026-01-01T00-00-00.000Z_755478fc-f323-565e-92f8-1a5b10584e41.jsonl"),
                SESSION_HEADER + "{\"type\": \"end\", \"finish\": \"stop\", \"turns\": 2, \"ts\": \"2026-01-01T00:12:30Z\"}\n");
        final var done = new Tailer().poll(runDir).stream().filter(e -> "session_done".equals(e.get("type"))).findFirst().orElseThrow();
        assertEquals("2026-01-01T00:12:30Z", done.get("ts"));
    }

    @Test
    void sessionEndIsNotReportedTwice() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.createDirectories(runDir.resolve("sessions"));
        Files.writeString(runDir.resolve("sessions/2026-01-01T00-00-00.000Z_755478fc-f323-565e-92f8-1a5b10584e41.jsonl"),
                SESSION_HEADER + "{\"type\": \"end\", \"finish\": \"stop\", \"turns\": 2}\n");
        final var tailer = new Tailer();
        final long first = tailer.poll(runDir).stream().filter(e -> "session_done".equals(e.get("type"))).count();
        final long second = tailer.poll(runDir).stream().filter(e -> "session_done".equals(e.get("type"))).count();
        assertEquals(1, first);
        assertEquals(0, second);
    }

    @Test
    void aSessionStillInProgressProducesNoSessionDone() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.createDirectories(runDir.resolve("sessions"));
        Files.writeString(runDir.resolve("sessions/2026-01-01T00-00-00.000Z_755478fc-f323-565e-92f8-1a5b10584e41.jsonl"),
                SESSION_HEADER + "{\"type\": \"message\", \"message\": {\"role\": \"assistant\"}}\n");
        final var events = new Tailer().poll(runDir);
        assertTrue(events.stream().noneMatch(e -> "session_done".equals(e.get("type"))));
    }

    @Test
    void sessionEndArrivingOnALaterPollIsStillDetected() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.createDirectories(runDir.resolve("sessions"));
        final var f = runDir.resolve("sessions/2026-01-01T00-00-00.000Z_755478fc-f323-565e-92f8-1a5b10584e41.jsonl");
        Files.writeString(f, SESSION_HEADER);
        final var tailer = new Tailer();
        assertTrue(tailer.poll(runDir).stream().noneMatch(e -> "session_done".equals(e.get("type"))));
        Files.writeString(f, "{\"type\": \"end\", \"finish\": \"length\", \"turns\": 9}\n", java.nio.file.StandardOpenOption.APPEND);
        final var done = tailer.poll(runDir).stream().filter(e -> "session_done".equals(e.get("type"))).findFirst().orElseThrow();
        assertEquals("length", done.get("finish"));
    }

    /** RunBench writes execution_order.json (the real planned task waves) before any task session
     *  starts - the live view needs this to explain a numerically-later task's session starting
     *  before an earlier one in the same wave (e.g. T4 before T3, when T3 only depends on T2). */
    @Test
    void executionOrderIsReportedOnceWhenTheFileAppears() throws Exception {
        final var runDir = Files.createTempDirectory("tailer");
        Files.writeString(runDir.resolve("execution_order.json"), "[[\"T1\"],[\"T2\",\"T4\"],[\"T3\"]]");
        final var tailer = new Tailer();
        final var events = tailer.poll(runDir);
        final var order = events.stream().filter(e -> "execution_order".equals(e.get("type"))).findFirst().orElseThrow();
        assertEquals("[[\"T1\"],[\"T2\",\"T4\"],[\"T3\"]]".replace(" ", ""),
                order.get("waves").toString().replaceAll("\\s", ""));
        assertTrue(tailer.poll(runDir).stream().noneMatch(e -> "execution_order".equals(e.get("type"))),
                "the file never changes after it's written - reported once, not every poll");
    }

    @Test
    void aRunWithNoExecutionOrderFileReportsNone() throws Exception {
        final var events = new Tailer().poll(Files.createTempDirectory("tailer"));
        assertTrue(events.stream().noneMatch(e -> "execution_order".equals(e.get("type"))));
    }
}
