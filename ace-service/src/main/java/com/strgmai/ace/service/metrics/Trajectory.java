package com.strgmai.ace.service.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** Port of metrics/trajectory.py: trajectory analysis from the recorded interactions.
 *  Input: interactions.jsonl (the proxy journal — the ground truth of what the model saw and said).
 *  Output: a per-turn trace and an effectiveness summary: context growth, tool profile, thrash
 *  (identical tool calls repeated; edit/read ping-pong on one file — the signature of a stuck
 *  agent), first artifact, reasoning share, and the harness-computed OBJECTIVE INDEX the
 *  trajectory reviewer is calibrated against. Turns made while Docker Desktop was up ON DEMAND
 *  are reported apart and excluded from the decode-floor statistic (that contention is the
 *  design, not the machine — R7). */
public final class Trajectory {
    private Trajectory() {}
    private static final Logger log = LoggerFactory.getLogger(Trajectory.class);
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern TEST_CMD = Pattern.compile("gradle|gradlew|pytest|npm test|mvn|\\bmake\\b");

    public record ToolCall(String name, String args) {}

    public record Turn(Object seq, String ts, Object status, Object genTps, String task, boolean budgetExceeded,
                       boolean clientAborted, boolean truncated, Object cachedTokens, Object promptTokens,
                       Object completionTokens, Object nMessages, Object latency, Object ttft, String finish,
                       int reasoningChars, int contentChars, List<ToolCall> toolCalls, boolean inDockerWindow) {}

    /** normalize the journal's chat records into turns (streamed and non-streamed shapes) */
    public static List<Turn> turnsFromProxy(final List<Map<String, Object>> recs) {
        final List<Turn> turns = new ArrayList<>();
        for (Map<String, Object> r : recs) {
            if (!String.valueOf(r.getOrDefault("path", "")).startsWith("/v1/chat/completions")) continue;
            final Map<String, Object> resp0 = r.get("response") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            Map<String, Object> resp = resp0;
            final List<ToolCall> tcs = new ArrayList<>();
            if (resp0.containsKey("choices")) {   // non-streamed shape -> normalise
                final Map<String, Object> ch = ((List<Map<String, Object>>) resp0.getOrDefault("choices", List.of())).get(0);
                final Map<String, Object> msg = ch.get("message") instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of();
                for (Map<String, Object> t : (List<Map<String, Object>>) msg.getOrDefault("tool_calls", List.of())) {
                    final Map<String, Object> fn = t.get("function") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
                    tcs.add(new ToolCall(String.valueOf(fn.get("name")), String.valueOf(fn.getOrDefault("arguments", ""))));
                }
                final Map<String, Object> n = new LinkedHashMap<>();
                n.put("content", msg.getOrDefault("content", ""));
                n.put("reasoning", msg.getOrDefault("reasoning_content", ""));
                n.put("tool_calls", tcs);
                n.put("finish_reason", ch.get("finish_reason"));
                n.put("usage", resp0.get("usage"));
                resp = n;
            } else
                for (Map<String, Object> t : (List<Map<String, Object>>) resp.getOrDefault("tool_calls", List.of()))
                    tcs.add(new ToolCall(String.valueOf(t.get("name")), String.valueOf(t.getOrDefault("arguments", ""))));
            final Map<String, Object> u = resp.get("usage") instanceof Map<?, ?> uu ? (Map<String, Object>) uu : Map.of();
            Object genTps = u.get("generation_tokens_per_second");
            if (genTps == null && u.get("completion_tokens") instanceof Number ct && r.get("latency_sec") instanceof Number lat && r.get("ttft_sec") instanceof Number ttft)
                genTps = Math.round(ct.doubleValue() / Math.max(0.1, lat.doubleValue() - ttft.doubleValue()) * 100) / 100.0;
            final Map<String, Object> pd = u.get("prompt_tokens_details") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            final String reasoning = String.valueOf(resp.getOrDefault("reasoning", ""));
            final String content = String.valueOf(resp.getOrDefault("content", ""));
            turns.add(new Turn(r.get("seq"), String.valueOf(r.get("ts")), r.get("status"), genTps, (String) r.get("task"),
                    Boolean.TRUE.equals(r.get("budget_exceeded")), Boolean.TRUE.equals(r.get("client_aborted")),
                    Boolean.TRUE.equals(r.get("truncated")), pd.get("cached_tokens"), u.get("prompt_tokens"), u.get("completion_tokens"),
                    r.get("n_messages"), r.get("latency_sec"), r.get("ttft_sec"),
                    resp.get("finish_reason") == null ? null : String.valueOf(resp.get("finish_reason")),
                    reasoning.length(), content.length(), tcs, false));
        }
        return turns;
    }

    static boolean isTestOrBuild(final ToolCall c) {
        if (!c.name().equals("bash")) return false;
        String cmd = "";
        try { cmd = String.valueOf(JSON.readTree(c.args()).path("command").asText("")); }
        catch (Exception e) { log.debug("could not parse bash tool args as JSON: {}", e.toString()); }
        return TEST_CMD.matcher(cmd).find();
    }

    static String argPath(final String args) {
        try {
            final JsonNode a = JSON.readTree(args);
            final String p = a.path("path").asText(a.path("file_path").asText(a.path("filePath").asText("")));
            if (!p.isEmpty()) return p;
            return a.path("command").asText("").substring(0, Math.min(60, a.path("command").asText("").length()));
        } catch (Exception e) {
            log.debug("could not parse tool args as JSON, falling back to the raw text: {}", e.toString());
            return args.substring(0, Math.min(60, args.length()));
        }
    }

    static boolean inWindows(Turn t, final List<Map<String, Object>> windows) {
        if (windows == null || windows.isEmpty() || t.ts() == null) return false;
        try {
            final OffsetDateTime ts = OffsetDateTime.parse(t.ts().replace("Z", "+00:00"));
            return windows.stream().filter(w -> w.get("start_iso") != null && w.get("end_iso") != null)
                    .anyMatch(w -> !ts.isBefore(OffsetDateTime.parse(String.valueOf(w.get("start_iso"))))
                            && !ts.isAfter(OffsetDateTime.parse(String.valueOf(w.get("end_iso")))));
        } catch (Exception e) {
            log.debug("could not parse turn/window timestamp, treating as outside the docker window: {}", e.toString());
            return false;
        }
    }

    /** the effectiveness summary; `derived` (manifest.derived) supplies the run's window and
     *  checkpoints so thresholds follow the setup (R5 C-16). */
    public static Map<String, Object> analyze(List<Turn> turns, final Map<String, Object> derived, final List<Map<String, Object>> dockerWindows) {
        final Map<String, Object> d = derived == null ? Map.of() : derived;
        final int ceiling = d.get("usable_context") instanceof Number n ? n.intValue() : 65536;
        final int trigger = d.get("compaction_trigger_tokens") instanceof Number n ? n.intValue() : 28000;
        final double floor = d.get("min_decode_tps") instanceof Number n ? n.doubleValue() : 10;
        final Map<String, Object> s = new LinkedHashMap<>();
        s.put("turns", turns.size());
        s.put("thresholds", Map.of("near_ceiling", (int) (0.9 * ceiling), "in_task_checkpoint", trigger, "decode_floor", floor));
        if (turns.isEmpty()) return s;
        for (Turn t : turns) s.computeIfAbsent("_docker_", x -> null);   // (marker; replaced below)
        s.remove("_docker_");
        turns = new ArrayList<>(turns);
        for (int i = 0; i < turns.size(); i++) {
            final Turn t = turns.get(i);
            turns.set(i, new Turn(t.seq(), t.ts(), t.status(), t.genTps(), t.task(), t.budgetExceeded(), t.clientAborted(),
                    t.truncated(), t.cachedTokens(), t.promptTokens(), t.completionTokens(), t.nMessages(), t.latency(), t.ttft(),
                    t.finish(), t.reasoningChars(), t.contentChars(), t.toolCalls(), inWindows(t, dockerWindows)));
        }
        final List<Integer> pt = turns.stream().filter(t -> t.promptTokens() != null).map(t -> ((Number) t.promptTokens()).intValue()).toList();
        final List<Integer> ct = turns.stream().filter(t -> t.completionTokens() != null).map(t -> ((Number) t.completionTokens()).intValue()).toList();
        s.put("prompt_tokens_first", pt.isEmpty() ? null : pt.get(0));
        s.put("prompt_tokens_last", pt.isEmpty() ? null : pt.get(pt.size() - 1));
        s.put("prompt_tokens_max", pt.isEmpty() ? null : Collections.max(pt));
        s.put("completion_tokens_total", ct.stream().mapToInt(Integer::intValue).sum());
        int drops = 0;
        for (int i = 1; i < pt.size(); i++) if (pt.get(i) < pt.get(i - 1) * 0.7) drops++;
        s.put("context_drops_gt30pct", drops);   // compaction / new-phase events
        if (pt.size() > 1) s.put("growth_tokens_per_turn", Math.round((pt.get(pt.size() - 1) - pt.get(0)) * 10.0 / Math.max(1, pt.size() - 1)) / 10.0);
        final List<Double> lat = turns.stream().filter(t -> t.latency() instanceof Number).map(t -> ((Number) t.latency()).doubleValue()).toList();
        if (!lat.isEmpty()) s.put("latency_mean_sec", Math.round(lat.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0);
        s.put("latency_max_sec", lat.isEmpty() ? null : Collections.max(lat));
        final List<Double> ttft = turns.stream().filter(t -> t.ttft() instanceof Number).map(t -> ((Number) t.ttft()).doubleValue()).toList();
        if (!ttft.isEmpty()) s.put("ttft_mean_sec", Math.round(ttft.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100) / 100.0);
        final List<Double> g = turns.stream().filter(t -> t.genTps() instanceof Number).map(t -> ((Number) t.genTps()).doubleValue()).toList();
        s.put("decode_tps_median", g.isEmpty() ? null : median(g));
        s.put("decode_tps_min", g.isEmpty() ? null : Collections.min(g));
        // decode falls with context length: the calibration floor applies to the SHORT-context regime
        final List<Turn> out = turns.stream().filter(t -> !t.inDockerWindow()).toList(), inw = turns.stream().filter(Turn::inDockerWindow).toList();
        final Map<String, Object> byCtx = new LinkedHashMap<>();
        byCtx.put("lt16k", medGenTps(out, t -> pTok(t) < 16000));
        byCtx.put("16k_30k", medGenTps(out, t -> pTok(t) >= 16000 && pTok(t) < 30000));
        byCtx.put("ge30k", medGenTps(out, t -> pTok(t) >= 30000));
        s.put("decode_tps_by_context", byCtx);
        s.put("decode_tps_median_short", byCtx.get("lt16k"));
        if (dockerWindows != null && !dockerWindows.isEmpty()) {
            // Map.of is null-hostile, and medGenTps legitimately returns null when none of the
            // in-window turns have a numeric genTps (e.g. every one of them errored or was budget-
            // refused before oMLX ever reported a decode speed) - a real crash seen live (NPE in
            // Map.of), fixed the same way byCtx just above already handles the identical case
            final Map<String, Object> dw = new LinkedHashMap<>();
            dw.put("windows", dockerWindows.size());
            dw.put("seconds", dockerWindows.stream().mapToDouble(w -> w.get("seconds") instanceof Number n ? n.doubleValue() : 0).sum());
            dw.put("turns", inw.size());
            dw.put("decode_tps_median", medGenTps(inw, t -> true));
            s.put("docker_window", dw);
        }
        s.put("prefix_cache_hit_turns", turns.stream().filter(t -> t.cachedTokens() != null && ((Number) t.cachedTokens()).intValue() > 0).count());
        final int rc = turns.stream().mapToInt(Turn::reasoningChars).sum(), cc = turns.stream().mapToInt(Turn::contentChars).sum();
        s.put("reasoning_share_pct", rc + cc > 0 ? Math.round(1000.0 * rc / (rc + cc)) / 10.0 : null);
        final Map<String, Integer> finishes = new LinkedHashMap<>();
        turns.forEach(t -> finishes.merge(t.finish() == null ? "none" : t.finish(), 1, Integer::sum));
        s.put("finish_reasons", finishes);
        s.put("http_errors", turns.stream().filter(t -> ((Number) t.status()).intValue() >= 400).count());
        s.put("stalled_turns_gt600s", turns.stream().filter(t -> t.latency() instanceof Number n && n.doubleValue() > 600).count());
        s.put("budget_refusals", turns.stream().filter(Turn::budgetExceeded).count());
        s.put("client_aborts", turns.stream().filter(Turn::clientAborted).count());
        s.put("truncated_streams", turns.stream().filter(Turn::truncated).count());
        // upstream failures the AGENT could react to; budget refusals and proxy 502s are the harness's/machine's (R4 C-13)
        s.put("agent_http_errors", turns.stream().filter(t -> ((Number) t.status()).intValue() >= 400 && !t.budgetExceeded() && ((Number) t.status()).intValue() != 502).count());

        // tool profile + thrash
        List<Object[]> calls = new ArrayList<>();   // [seq, name, args]
        turns.forEach(t -> t.toolCalls().forEach(c -> calls.add(new Object[]{t.seq(), c.name(), c.args()})));
        s.put("tool_calls_total", calls.size());
        final Map<String, Integer> profile = new LinkedHashMap<>();
        calls.forEach(c -> profile.merge((String) c[1], 1, Integer::sum));
        s.put("tool_profile", profile);
        final Map<String, Integer> sig = new LinkedHashMap<>();
        for (Object[] c : calls) {
            if (isTestOrBuild(new ToolCall((String) c[1], (String) c[2]))) continue;   // re-running the tests after an edit is discipline, not thrash (R4 C-13)
            sig.merge(sha1(c[1] + "|" + c[2]), 1, Integer::sum);
        }
        s.put("identical_calls_repeated", sig.values().stream().filter(v -> v > 1).mapToInt(v -> v - 1).sum());
        s.put("test_runs", calls.stream().filter(c -> isTestOrBuild(new ToolCall((String) c[1], (String) c[2]))).count());
        final Map<String, Integer> perFile = new LinkedHashMap<>();
        for (Object[] c : calls)
            if (List.of("edit", "write", "read").contains(c[1])) perFile.merge(argPath((String) c[2]), 1, Integer::sum);
        s.put("hot_files", perFile.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)));
        // ping-pong: same file touched >= 3 alternating consecutive times
        final List<Object[]> seqf = calls.stream().filter(c -> List.of("edit", "write", "read").contains(c[1])).toList();
        int pp = 0;
        for (int i = 2; i < seqf.size(); i++)
            if (argPath((String) seqf.get(i)[2]).equals(argPath((String) seqf.get(i - 1)[2]))
                    && argPath((String) seqf.get(i - 1)[2]).equals(argPath((String) seqf.get(i - 2)[2]))
                    && !seqf.get(i)[1].equals(seqf.get(i - 1)[1])) pp++;
        s.put("edit_read_pingpong", pp);
        s.put("turns_without_tool_call", turns.stream().filter(t -> t.toolCalls().isEmpty() && !"tool_calls".equals(t.finish())).count());

        // first artifact: first write/edit touching docs/ or src/
        for (int i = 0; i < turns.size(); i++) {
            final int idx = i;
            boolean hit = turns.get(i).toolCalls().stream().anyMatch(c -> List.of("write", "edit", "create").contains(c.name())
                    && Pattern.compile("(docs/|src/|build\\.gradle|docker-compose)").matcher(argPath(c.args())).find());
            if (hit) {
                s.put("first_artifact_turn", idx + 1);
                s.put("tokens_before_first_artifact", turns.subList(0, idx + 1).stream()
                        .mapToInt(t -> t.completionTokens() instanceof Number n ? n.intValue() : 0).sum());
                break;
            }
        }
        // verdict heuristics
        final List<String> flags = new ArrayList<>();
        if (((Number) s.getOrDefault("identical_calls_repeated", 0)).intValue() >= 5) flags.add("THRASH: " + s.get("identical_calls_repeated") + " identical tool calls repeated");
        if (pp >= 4) flags.add("PINGPONG: " + pp + " edit/read alternations on one file");
        if (s.get("prompt_tokens_max") != null && ((Number) s.get("prompt_tokens_max")).intValue() > (int) (0.9 * ceiling))
            flags.add("NEAR_CEILING: max prompt " + s.get("prompt_tokens_max") + " (window " + ceiling + ")");
        if (((Number) s.getOrDefault("http_errors", 0)).intValue() > 0) flags.add("ERRORS: " + s.get("http_errors") + " HTTP >=400 responses");
        if (s.get("reasoning_share_pct") != null && ((Number) s.get("reasoning_share_pct")).doubleValue() > 70)
            flags.add("REASONING_HEAVY: " + s.get("reasoning_share_pct") + "% of output was reasoning");
        if (((Number) s.getOrDefault("stalled_turns_gt600s", 0)).intValue() > 0) flags.add("STALLS: " + s.get("stalled_turns_gt600s") + " turns over 600 s (machine-side; not in the objective index)");
        if (byCtx.get("lt16k") != null && ((Number) byCtx.get("lt16k")).doubleValue() < floor)
            flags.add("SLOW_DECODE: median " + byCtx.get("lt16k") + " tok/s under 16k context, below the setup's floor " + floor + " tok/s - check Docker/memory contention");
        s.put("flags", flags);
        return s;
    }

    static int pTok(Turn t) { return t.promptTokens() instanceof Number n ? n.intValue() : 0; }
    static Object medGenTps(final List<Turn> turns, final java.util.function.Predicate<Turn> regime) {
        List<Double> xs = turns.stream().filter(t -> t.genTps() instanceof Number).filter(regime)
                .map(t -> ((Number) t.genTps()).doubleValue()).toList();
        return xs.isEmpty() ? null : median(xs);
    }
    static double median(List<Double> xs) { List<Double> s = new ArrayList<>(xs); Collections.sort(s); return s.get(s.size() / 2); }

    /** tool results are visible in the NEXT request's trailing `tool` messages: map seq -> result texts */
    public static Map<Object, List<String>> toolResults(final List<Map<String, Object>> recs) {
        final Map<Object, List<String>> out = new LinkedHashMap<>();
        Map<String, Object> prevSeq = new LinkedHashMap<>();   // per task tag: parallel tasks interleave in the merged journal
        for (Map<String, Object> r : recs) {
            if (!String.valueOf(r.getOrDefault("path", "")).startsWith("/v1/chat/completions")) continue;
            final Map<String, Object> req = r.get("request") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            final List<Map<String, Object>> msgs = (List<Map<String, Object>>) req.getOrDefault("messages", List.of());
            final List<String> tail = new ArrayList<>();
            for (int i = msgs.size() - 1; i >= 0; i--)
                if ("tool".equals(msgs.get(i).get("role"))) tail.add(String.valueOf(msgs.get(i).get("content")));
                else break;
            final String k = String.valueOf(r.getOrDefault("task", ""));
            if (prevSeq.get(k) != null && !tail.isEmpty()) out.put(prevSeq.get(k), tail.reversed());
            prevSeq.put(k, r.get("seq"));
        }
        return out;
    }

    /** condensed, human/LLM-readable trajectory: one block per turn; COMPLETE by default, with a
     *  per-task turn index heading the file so a reviewer can jump (R4 C-14). */
    public static String transcript(final List<Map<String, Object>> recs, final List<Turn> turns, final Map<String, String[]> taskWindows) {
        final Map<Object, List<String>> results = toolResults(recs);
        final List<String> lines = new ArrayList<>(List.of("# Trajectory transcript (condensed by the harness)", ""));
        if (taskWindows != null && !taskWindows.isEmpty()) {
            lines.add("## Turn index by task");
            for (Map.Entry<String, String[]> e : taskWindows.entrySet()) {
                final List<Integer> tt = new ArrayList<>();
                for (int i = 0; i < turns.size(); i++) {
                    final Turn t = turns.get(i);
                    if (t.ts() == null) continue;
                    try {
                        final OffsetDateTime ts = OffsetDateTime.parse(t.ts().replace("Z", "+00:00"));
                        if (!ts.isBefore(OffsetDateTime.parse(e.getValue()[0])) && !ts.isAfter(OffsetDateTime.parse(e.getValue()[1]))) tt.add(i + 1);
                    } catch (Exception ex) { log.debug("could not parse task window timestamp for {}: {}", e.getKey(), ex.toString()); }
                }
                if (!tt.isEmpty()) lines.add("- " + e.getKey() + ": turns " + tt.get(0) + "-" + tt.get(tt.size() - 1) + " (" + tt.size() + " turns)");
            }
            lines.add("");
        }
        for (int i = 0; i < turns.size(); i++) {
            final Turn t = turns.get(i);
            lines.add("## turn " + (i + 1) + "  [seq " + t.seq() + "  " + (t.ts() == null ? "" : t.ts().substring(Math.min(11, t.ts().length()), Math.min(19, t.ts().length()))) + "Z  prompt "
                    + t.promptTokens() + " tok  out " + t.completionTokens() + " tok  " + (t.latency() instanceof Number n ? Math.round(n.doubleValue()) : 0) + "s  finish=" + t.finish() + "]");
            if (t.reasoningChars() > 0) lines.add("- thinking: " + t.reasoningChars() + " chars");
            for (ToolCall c : t.toolCalls()) {
                String arg;
                try {
                    final JsonNode d = JSON.readTree(c.args());
                    arg = d.path("command").asText(d.path("path").asText(d.path("file_path").asText(JSON.writeValueAsString(d))));
                } catch (Exception e) {
                    log.debug("could not parse tool call args for the transcript, using the raw text: {}", e.toString());
                    arg = c.args();
                }
                lines.add("- " + c.name() + ": " + arg.substring(0, Math.min(220, arg.length())).replace("\n", " "));
            }
            if (t.toolCalls().isEmpty() && t.contentChars() > 0) lines.add("- says (" + t.contentChars() + " chars)");
            for (String rtxt : results.getOrDefault(t.seq(), List.of()).subList(0, Math.min(4, results.getOrDefault(t.seq(), List.of()).size())))
                lines.add("  -> " + rtxt.substring(0, Math.min(300, rtxt.length())).replace("\n", " | "));
            lines.add("");
        }
        return String.join("\n", lines);
    }

    /** harness-computed trajectory quality 0-100 from MEASURABLE signals of the AGENT's behaviour
     *  only. Penalties: identical non-test calls repeated (2 each, cap 20), edit/read ping-pong
     *  (2 each, cap 10), upstream HTTP errors the agent could react to (1 each, cap 10),
     *  over-budget tasks (10 each, cap 30), tasks reported done that the harness verified RED (10
     *  each, cap 30). Not penalised (R4 C-13): test re-runs, budget refusals, proxy 502s, stalls. */
    public static Map.Entry<Integer, Map<String, Integer>> objectiveIndex(final Map<String, Object> s, final Map<String, Object> manifest) {
        final Map<String, Integer> pen = new LinkedHashMap<>();
        pen.put("thrash", Math.min(20, 2 * ((Number) s.getOrDefault("identical_calls_repeated", 0)).intValue()));
        pen.put("pingpong", Math.min(10, 2 * ((Number) s.getOrDefault("edit_read_pingpong", 0)).intValue()));
        pen.put("errors", Math.min(10, ((Number) s.getOrDefault("agent_http_errors", 0)).intValue()));
        final List<Map<String, Object>> tasks = manifest.get("tasks") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        pen.put("over_budget_tasks", Math.min(30, 10 * (int) tasks.stream().filter(t -> Boolean.TRUE.equals(t.get("over_budget"))).count()));
        pen.put("unverified_done", Math.min(30, 10 * (int) tasks.stream().filter(t -> "done".equals(t.get("reported")) && conclusiveRed(t.get("verification"))).count()));
        final int total = pen.values().stream().mapToInt(Integer::intValue).sum();
        return Map.entry(Math.max(0, 100 - total), pen);
    }

    /** green is None when the verification could not run (inconclusive is never the agent's fault — R4 C-2) */
    static boolean conclusiveRed(final Object v) {
        if (!(v instanceof Map<?, ?> m)) return false;
        final boolean ran = Boolean.TRUE.equals(m.get("ran"));
        final boolean green = Boolean.FALSE.equals(m.get("green"));
        final int executed = m.get("executed") instanceof Number n ? n.intValue() : 0;
        final List<Integer> rcs = m.get("rc") instanceof List<?> l ? (List<Integer>) l : List.of(1);
        return ran && green && !(executed == 0 && rcs.stream().anyMatch(rc -> rc != 0));
    }

    static String sha1(final String s) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes())).substring(0, 10); }
        catch (Exception e) {
            log.debug("SHA-1 unavailable, falling back to hashCode() for thrash detection: {}", e.toString());
            return String.valueOf(s.hashCode());
        }
    }
}
