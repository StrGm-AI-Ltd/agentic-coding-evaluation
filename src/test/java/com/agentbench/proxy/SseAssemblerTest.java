package com.agentbench.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Port of the SSE reassembly: content, reasoning, tool_calls (arguments concatenated per index),
 *  finish and usage. The chunks are BUILT with Jackson (no hand-escaped JSON literals: balanced
 *  braces are not something to get wrong twice). */
class SseAssemblerTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static byte[] chunk(String json) {
        return ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    /** {"choices":[{"delta":{"tool_calls":[TC...]}}]} with the tool_calls pieces given as raw strings */
    private static String toolChunk(String... toolCallJsons) {
        ObjectNode root = M.createObjectNode();
        ObjectNode delta = root.putArray("choices").addObject().putObject("delta");
        ArrayNode tcs = delta.putArray("tool_calls");
        for (String tcJson : toolCallJsons) {
            try { tcs.add(M.readTree(tcJson)); } catch (Exception e) { throw new IllegalArgumentException(e); }
        }
        return root.toString();
    }

    @Test
    void reassemblesAStreamedCompletion() {
        SseAssembler.Assembled a = SseAssembler.parse(List.of(
                chunk("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}"),
                chunk("{\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}"),
                chunk("{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}"),
                chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"),
                chunk("{\"usage\":{\"completion_tokens\":7,\"prompt_tokens\":100}}"),
                "data: [DONE]\n\n".getBytes()));
        assertEquals("Hello", a.content());
        assertEquals("thinking", a.reasoning());
        assertEquals("stop", a.finishReason());
        assertEquals(7, a.usage().path("completion_tokens").asInt());
    }

    @Test
    void parallelToolCallsConcatenateTheirArgumentsPerIndex() {
        String argPiece1 = "{\"" + "pa";             // what the model streams first: {"pa
        String argPiece2 = "th\": \"" + "x\"}";       // what completes it to: {"path": "x"}
        String c1 = toolChunk("{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"read\",\"arguments\":\"ARG1\"}}"
                .replace("ARG1", argPiece1.replace("\"", "\\\"")));
        String c2 = toolChunk(
                "{\"index\":0,\"function\":{\"arguments\":\"ARG2\"}}".replace("ARG2", argPiece2.replace("\"", "\\\"")),
                "{\"index\":1,\"id\":\"c2\",\"function\":{\"name\":\"bash\",\"arguments\":\"{}\"}}");
        String c3 = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}";   // (plain: no nested tool JSON)

        SseAssembler.Assembled a = SseAssembler.parse(List.of(chunk(c1), chunk(c2), chunk(c3)));
        assertEquals(2, a.toolCalls().size());
        assertEquals("read", a.toolCalls().get(0).get("name"));
        assertEquals(argPiece1 + argPiece2, a.toolCalls().get(0).get("arguments"));
        assertEquals("bash", a.toolCalls().get(1).get("name"));
        assertEquals("tool_calls", a.finishReason());
    }

    @Test
    void aNonDataLineIsIgnored() {
        SseAssembler.Assembled a = SseAssembler.parse(List.of(": keepalive\n".getBytes(), chunk("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}")));
        assertEquals("x", a.content());
        assertNull(a.finishReason());
    }
}
