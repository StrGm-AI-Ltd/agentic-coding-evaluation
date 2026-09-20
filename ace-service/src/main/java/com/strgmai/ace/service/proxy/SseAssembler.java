package com.strgmai.ace.service.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/** Port of record_proxy.py's parse_sse_chunks: reassemble a streamed chat completion —
 *  content, reasoning, tool_calls (arguments concatenated per index), finish, usage. */
public final class SseAssembler {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SseAssembler() {}

    public record Assembled(String content, String reasoning, List<Map<String, Object>> toolCalls, String finishReason, JsonNode usage) {}

    public static Assembled parse(List<byte[]> chunks) {
        StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        Map<Integer, Map<String, Object>> tools = new TreeMap<>();
        String finish = null; JsonNode usage = null;
        for (byte[] raw : chunks) {
            for (String line : new String(raw, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).strip();
                if (data.equals("[DONE]")) continue;
                JsonNode obj;
                try { obj = JSON.readTree(data); } catch (Exception e) { continue; }
                if (obj.hasNonNull("usage")) usage = obj.get("usage");
                for (JsonNode ch : obj.path("choices")) {
                    JsonNode d = ch.path("delta");
                    if (!d.path("content").isMissingNode() && !d.path("content").isNull() && !d.path("content").asText("").isEmpty()) text.append(d.path("content").asText());
                    if (!d.path("reasoning_content").isMissingNode() && !d.path("reasoning_content").isNull()) reasoning.append(d.path("reasoning_content").asText());
                    if (!d.path("reasoning").isMissingNode() && d.path("reasoning").isTextual()) reasoning.append(d.path("reasoning").asText());
                    for (JsonNode tc : d.path("tool_calls")) {
                        int i = tc.path("index").asInt(0);
                        Map<String, Object> t = tools.computeIfAbsent(i, x -> new LinkedHashMap<>());
                        if (tc.path("id").isTextual()) t.put("id", tc.path("id").asText());
                        JsonNode fn = tc.path("function");
                        if (fn.path("name").isTextual()) t.put("name", fn.path("name").asText());
                        if (fn.path("arguments").isTextual()) t.put("arguments", t.getOrDefault("arguments", "") + fn.path("arguments").asText());
                    }
                    if (!ch.path("finish_reason").isMissingNode() && !ch.path("finish_reason").isNull() && !ch.path("finish_reason").asText("").isEmpty()) finish = ch.path("finish_reason").asText();
                }
            }
        }
        return new Assembled(text.toString(), reasoning.toString(), new ArrayList<>(tools.values()), finish, usage);
    }
}
