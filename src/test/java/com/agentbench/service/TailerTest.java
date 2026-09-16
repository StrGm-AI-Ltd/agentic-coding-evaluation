package com.agentbench.service;

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
        Path runDir = Files.createTempDirectory("tailer");
        Path interactions = runDir.resolve("interactions.jsonl");
        // line 1 carries two invalid bytes (0xFF 0xFE): replacing them and re-encoding to count the offset would
        // advance past what was actually consumed, so the next line would start mid-way and be lost
        byte[] firstTwo = concat(concat(
                "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200, \"request\": \"".getBytes(),
                new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) ' ', (byte) 'b', (byte) 'r', (byte) 'o', (byte) 'k', (byte) 'e', (byte) 'n', (byte) 'u', (byte) 't', (byte) 'f', (byte) '8', (byte) '\"', (byte) '}', (byte) '\n'}),
                "{\"seq\": 2, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200}\n".getBytes());
        Files.write(interactions, firstTwo);
        Tailer tailer = new Tailer();

        List<Object> seqs = new java.util.ArrayList<>();
        tailer.poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).forEach(e -> seqs.add(e.get("seq")));
        Files.write(interactions, concat(firstTwo,
                "{\"seq\": 3, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200}\n".getBytes()));
        tailer.poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).forEach(e -> seqs.add(e.get("seq")));

        assertEquals(List.of(1, 2, 3), seqs);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void aPartiallyWrittenLineWaitsForTheNextPoll() throws Exception {
        Path runDir = Files.createTempDirectory("tailer");
        Path interactions = runDir.resolve("interactions.jsonl");
        Files.writeString(interactions, "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/c");   // no newline yet
        Tailer tailer = new Tailer();
        assertTrue(tailer.poll(runDir).isEmpty());
        Files.writeString(interactions, "{\"seq\": 1, \"ts\": \"t\", \"path\": \"/v1/chat/completions\", \"status\": 200}\n");
        List<Map<String, Object>> events = tailer.poll(runDir).stream().filter(e -> "request".equals(e.get("type"))).toList();
        assertEquals(1, events.size());
        assertTrue(tailer.poll(runDir).isEmpty());   // a no-op poll reports nothing new
    }

    @Test
    void aRunWithNoFilesReportsNothing() throws Exception {
        assertTrue(new Tailer().poll(Files.createTempDirectory("empty")).isEmpty());
    }

    @Test
    void stepsAreReportedFromPackAndInstructionFiles() throws Exception {
        Path runDir = Files.createTempDirectory("tailer");
        Files.createDirectories(runDir.resolve("packs"));
        Files.writeString(runDir.resolve("packs/T1.md"), "pack");
        Files.writeString(runDir.resolve("packs/T1-continue.md"), "cont");
        Files.writeString(runDir.resolve("packs/stable.md"), "the persistent pack, not a step");
        List<Map<String, Object>> events = new Tailer().poll(runDir);
        assertEquals(2, events.size());   // T1 and its continuation; stable is not a step
        assertTrue(events.stream().anyMatch(e -> "T1".equals(e.get("step")) && Boolean.TRUE.equals(e.get("continuation"))));
    }
}
