package com.agentbench.runner;

import com.agentbench.config.BenchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.agentbench.docker.DockerService;

/** Port of runner/context_probe.py, step 0 of a benchmark run: measure the context window this
 *  SETUP actually offers, then derive every context-dependent knob from it.
 *
 *  Why: the harness will be pointed at models of different sizes and quantisations on the same
 *  machine. The window a setup offers is min(server cap, memory) and is not knowable from the
 *  config: the server cap (`max_model_len`, from ~/.omlx/model_settings.json: max_context_window)
 *  is an operator setting, and the memory guard's dynamic ceiling depends on the weights, the KV
 *  quantisation, MTP and the prefill working set. So the harness PROBES it: prompts of growing
 *  length (each a prefix-extension of the previous, so the prefix cache keeps every step cheap)
 *  up to the cap, refining near the first failure; at every step it records prefill and decode
 *  rates (decode falls with context length — the per-setup validity floor and the time model come
 *  from this curve) and, when the oMLX log is readable, the memory guard's usage vs ceiling.
 *
 *  The probe OWNS the server cap: before measuring it raises the model's max_context_window to
 *  the model's positional limit (a probe that stops at the operator's own setting measures
 *  nothing), and after measuring it sets the cap to the measured window (a second, independent
 *  guard against a runaway prompt). The result is cached per setup (checkpoint + settings +
 *  server version + host); a cacheable result is never one set by a transient failure. */
public final class ContextProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final Path CACHE_DIR = Path.of(System.getProperty("user.home"), ".cache/agentbench-jls/context-probe");
    public static final Path LOG = Path.of(System.getProperty("user.home"), ".omlx/logs/server.log");
    public static final Path OMLX_BIN = Path.of(System.getProperty("user.home"), ".omlx/bin/omlx");
    public static final Path MODEL_SETTINGS = Path.of(System.getProperty("user.home"), ".omlx/model_settings.json");
    public static final Path OMLX_SETTINGS = Path.of(System.getProperty("user.home"), ".omlx/settings.json");
    static final String LINE = "line %06d: alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike november oscar.\n";
    static final String SYSTEM = "You are a text buffer. Reply with the single word OK.";
    static final String SYSTEM_GEN = "You are a patient technical writer. Answer at length, in flowing prose, and keep writing until you are cut off.";
    static final String DECODE_TASK = "Now write a detailed explanation, in plain prose paragraphs without lists or code, of how a limit order book matches buy "
            + "and sell orders, how partial fills and cancellations are handled, and why price-time priority matters. Keep writing until stopped.";

    /** port of DEFAULTS (all fractions of the window, from config `context.*`) */
    public static final Map<String, Object> DEFAULTS = Map.ofEntries(
            Map.entry("probe", true), Map.entry("raise_cap", true), Map.entry("set_cap_to_measured", true),
            Map.entry("positional_limit_default", 262144), Map.entry("step_tokens", 8192), Map.entry("refine_tokens", 2048),
            Map.entry("safety_tokens", 4096), Map.entry("min_usable", 16384), Map.entry("probe_max_tokens", 64),
            Map.entry("decode_samples", List.of(List.of(2000, 256), List.of(6000, 256), List.of(12000, 256))),
            Map.entry("retry_pause_sec", 8), Map.entry("request_timeout_sec", 1800), Map.entry("trigger_fraction", 0.43),
            Map.entry("keep_recent_fraction", 0.12), Map.entry("max_output_fraction", 0.125),
            Map.entry("task_budget_windows", 1.25), Map.entry("plan_budget_windows", 0.9),
            Map.entry("impl_budget_windows_l7", 6.1), Map.entry("impl_budget_windows", 3.05),
            Map.entry("decode_floor_fraction", 0.6), Map.entry("perf_fraction", 0.5), Map.entry("max_parallel_probe", 4),
            Map.entry("parallel_gain_per_agent", 0.25), Map.entry("parallel_prompt_tokens", 10000),
            Map.entry("parallel_out_tokens", 512), Map.entry("pack_reference_window", 65536),
            Map.entry("pack_scale_min", 0.25), Map.entry("cache_max_age_days", 30));

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    /** One chat completion, STREAMED (oMLX reports usage and generation_tokens_per_second only in
     *  the final SSE usage chunk; a non-streamed response carries neither). */
    record Post(int status, JsonNode usage, String finish, Double ttftSec, int contentChars, String error, double latency) {}
    Post post(String endpoint, String key, Map<String, Object> body, int timeoutSec) {
        body = new HashMap<>(body);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        long t0 = System.nanoTime(); Double ttft = null; JsonNode usage = null; String finish = null; int chars = 0;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSec)).header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
            HttpResponse<java.io.InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (!r.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
                JsonNode obj = JSON.readTree(r.body().readAllBytes());
                JsonNode ch = obj.path("choices").path(0);
                return new Post(r.statusCode(), obj.path("usage"), ch.path("finish_reason").asText(null), null, 0, null, elapsed(t0));
            }
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(r.body()));
            StringBuilder u = null;
            for (String line; (line = br.readLine()) != null; ) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).strip();
                if (data.equals("[DONE]")) break;
                JsonNode obj;
                try { obj = JSON.readTree(data); } catch (Exception e) { continue; }
                if (obj.hasNonNull("usage")) usage = obj.get("usage");
                for (JsonNode ch : obj.path("choices")) {
                    JsonNode d = ch.path("delta");
                    if ((!d.path("content").isMissingNode() && !d.path("content").asText("").isEmpty())
                            || !d.path("reasoning_content").isMissingNode() || !d.path("reasoning").isMissingNode()) {
                        if (ttft == null) ttft = elapsed(t0);
                        chars += d.path("content").asText("").length() + d.path("reasoning_content").asText("").length();
                    }
                    if (!ch.path("finish_reason").isMissingNode() && !ch.path("finish_reason").asText("").isEmpty()) finish = ch.path("finish_reason").asText();
                }
            }
            double lat = elapsed(t0);
            ObjectNode nu = usage == null || !usage.isObject() ? JSON.createObjectNode() : (ObjectNode) usage.deepCopy();
            if (nu.hasNonNull("completion_tokens") && ttft != null && lat - ttft > 0.05 && !nu.hasNonNull("generation_tokens_per_second"))
                nu.put("generation_tokens_per_second", Math.round(nu.get("completion_tokens").asDouble() / (lat - ttft) * 100) / 100.0);
            if (nu.hasNonNull("prompt_tokens") && ttft != null && ttft > 0)
                nu.put("prompt_tokens_per_second", Math.round(nu.get("prompt_tokens").asDouble() / ttft * 10) / 10.0);
            return new Post(r.statusCode(), nu, finish, ttft == null ? null : Math.round(ttft * 100) / 100.0, chars, null, Math.round(lat * 100) / 100.0);
        } catch (Exception e) {
            return new Post(599, null, null, null, 0, String.valueOf(e).substring(0, Math.min(200, String.valueOf(e).length())), elapsed(t0));
        }
    }

    private static double elapsed(long t0) { return (System.nanoTime() - t0) / 1e9; }

    public Map<String, JsonNode> models(String endpoint, String key) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/models"))
                    .header("Authorization", "Bearer " + key).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode data = JSON.readTree(r.body()).path("data");
            Map<String, JsonNode> out = new LinkedHashMap<>();
            for (JsonNode m : data) out.put(m.path("id").asText(), m);
            return out;
        } catch (Exception e) { return Map.of(); }
    }

    JsonNode health(String endpoint, String key) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(endpoint.replaceAll("/v1/?$", "") + "/health"))
                    .header("Authorization", "Bearer " + key).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return JSON.readTree(r.body());
        } catch (Exception e) { return JSON.createObjectNode(); }
    }

    /** Memory-guard lines the server logged since `since`: max usage and the ceiling it was sized
     *  against. Reads the live log and any rotated file of the same day (R5 C-22). */
    Map<String, Object> guardFacts(String since) {
        java.util.regex.Pattern re = java.util.regex.Pattern.compile("usage ([\\d.]+)GB vs sizing target ([\\d.]+)GB, dynamic ceiling ([\\d.]+)");
        List<Double> usage = new ArrayList<>(), target = new ArrayList<>(), ceiling = new ArrayList<>();
        List<Path> files = new ArrayList<>(List.of(LOG));
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(LOG.getParent(), LOG.getFileName() + ".*")) {
            var all = new ArrayList<Path>(); ds.forEach(all::add);
            files.addAll(all.subList(Math.max(0, all.size() - 2), all.size()));
        } catch (Exception ignore) {}
        for (Path f : files) {
            try {
                for (String line : Files.readAllLines(f)) {
                    if (line.length() >= 19 && line.substring(0, Math.min(19, line.length())).compareTo(since) < 0) continue;
                    java.util.regex.Matcher m = re.matcher(line);
                    if (m.find()) { usage.add(Double.parseDouble(m.group(1))); target.add(Double.parseDouble(m.group(2))); ceiling.add(Double.parseDouble(m.group(3))); }
                }
            } catch (IOException ignore) {}
        }
        if (usage.isEmpty()) return null;
        return Map.of("max_usage_gb", Collections.max(usage), "sizing_target_gb", target.get(target.size() - 1),
                "dynamic_ceiling_gb", ceiling.get(ceiling.size() - 1), "samples", usage.size());
    }

    /** the model's own maximum (text_config.max_position_embeddings) — the cap the probe raises the server to */
    public static int positionalLimit(String model, List<String> modelDirs, int dflt) {
        for (String d : modelDirs) {
            Path cp = Path.of(d, model, "config.json");
            if (Files.isRegularFile(cp)) {
                try {
                    JsonNode c = JSON.readTree(Files.readString(cp));
                    JsonNode t = c.has("text_config") && c.get("text_config").isObject() ? c.get("text_config") : c;
                    return t.path("max_position_embeddings").asInt(dflt);
                } catch (Exception e) { return dflt; }
            }
        }
        return dflt;
    }

    /** write the per-model `max_context_window` profile (it overrides the server's default); returns the previous value */
    public static Object setModelCap(String model, int value) throws IOException {
        ObjectNode d = Files.exists(MODEL_SETTINGS) ? (ObjectNode) JSON.readTree(Files.readString(MODEL_SETTINGS)) : JSON.createObjectNode();
        if (!d.has("models") || !d.get("models").isObject()) d.putObject("models");
        ObjectNode entry = d.with("models").with(model);
        Object prev = entry.has("max_context_window") ? entry.get("max_context_window").asInt() : null;
        entry.put("max_context_window", value);
        Files.writeString(MODEL_SETTINGS, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(d));
        return prev;
    }

    /** `omlx restart` with the orphan trap handled: the old server must be gone and exactly one new one must answer /health */
    public void restartServer(String endpoint, String key) {
        if (!Files.exists(OMLX_BIN)) throw new IllegalStateException("~/.omlx/bin/omlx not present: cannot restart the server");
        List<Long> old = pids();
        try { new ProcessBuilder(OMLX_BIN.toString(), "restart").start().waitFor(180, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
        long t0 = System.currentTimeMillis();
        boolean healthy = false;
        while (System.currentTimeMillis() - t0 < 150_000) {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            if ("healthy".equals(health(endpoint, key).path("status").asText())) { healthy = true; break; }
        }
        List<Long> now = pids();
        List<Long> survivors = old.stream().filter(now::contains).toList();
        try {
            if (!survivors.isEmpty() && now.size() > survivors.size()) {   // the app started a new server but the old one still holds resources
                for (long p : survivors) ProcessHandle.of(p).ifPresent(ProcessHandle::destroy);
            } else if (!survivors.isEmpty() && !healthy) {                   // nothing replaced the old server: kill it and start fresh
                for (long p : survivors) ProcessHandle.of(p).ifPresent(ProcessHandle::destroy);
                Thread.sleep(3000);
                new ProcessBuilder(OMLX_BIN.toString(), "start").start().waitFor(180, TimeUnit.SECONDS);
                long t = System.currentTimeMillis();
                while (System.currentTimeMillis() - t < 150_000) {
                    Thread.sleep(5000);
                    if ("healthy".equals(health(endpoint, key).path("status").asText())) { healthy = true; break; }
                }
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
        if (!healthy) throw new IllegalStateException("oMLX did not come back healthy after the restart; check `pgrep -x omlx-server` for an orphan");
    }

    static List<Long> pids() {
        DockerService.Sh out = DockerService.sh(8, "pgrep", "-x", "omlx-server");
        return Arrays.stream(out.out().split("\n")).filter(s -> !s.isBlank()).map(Long::parseLong).toList();
    }

    Integer serverCap(String endpoint, String key, String model) {
        JsonNode m = models(endpoint, key).get(model);
        return m == null || m.path("max_model_len").isMissingNode() ? null : m.get("max_model_len").asInt();
    }

    /** before measuring: lift the model's server cap to its positional limit so the MEMORY is what the probe finds */
    public Map<String, Object> prepareCap(String model, String endpoint, String key, List<String> modelDirs, Map<String, Object> o) throws IOException {
        Integer before = serverCap(endpoint, key, model);
        int wanted = positionalLimit(model, modelDirs, (Integer) o.get("positional_limit_default"));
        Map<String, Object> info = new LinkedHashMap<>(Map.of("cap_before", before, "positional_limit", wanted, "cap_probe", before, "restarts", 0));
        if (!Boolean.TRUE.equals(o.get("raise_cap")) || !Files.exists(OMLX_BIN)) return info;
        if (before != null && before >= wanted) return info;
        setModelCap(model, wanted);
        restartServer(endpoint, key);
        info.merge("restarts", 1, (a, b) -> (int) a + (int) b);
        Integer now = serverCap(endpoint, key, model);
        info.put("cap_probe", now);
        if (now == null || now != wanted) throw new IllegalStateException("server reports max_model_len=" + now + " after setting " + wanted
                + ": the per-model profile did not apply; check ~/.omlx/model_settings.json");
        return info;
    }

    /** after measuring: the server enforces the measured window itself */
    public Object finalizeCap(String model, String endpoint, String key, int usable, Map<String, Object> o) throws IOException {
        if (!Boolean.TRUE.equals(o.get("set_cap_to_measured")) || !Files.exists(OMLX_BIN)) return null;
        Integer cur = serverCap(endpoint, key, model);
        if (cur != null && cur == usable) return usable;
        setModelCap(model, usable);
        restartServer(endpoint, key);
        return serverCap(endpoint, key, model);
    }

    /** what a probe result is valid for: the checkpoint, its server-side settings (except the cap,
     *  which the probe itself controls), the server version, the machine. A contended measurement
     *  can never be served to a clean run (R5 C-4). */
    public record Setup(Map<String, Object> facts, String key) {}
    public Setup setupKey(String model, String endpoint, String key, List<String> modelDirs) throws Exception {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("model", model); facts.put("endpoint", endpoint);
        for (String d : modelDirs) {
            Path mp = Path.of(d, model);
            if (Files.isDirectory(mp)) {
                for (String f : List.of("config.json", "model.safetensors.index.json")) {
                    Path p = mp.resolve(f);
                    if (Files.isRegularFile(p)) facts.put(f, sha256(p));
                }
                break;
            }
        }
        if (Files.exists(MODEL_SETTINGS)) {
            JsonNode ms = JSON.readTree(Files.readString(MODEL_SETTINGS)).path("models").path(model);
            Map<String, Object> keep = new LinkedHashMap<>();
            if (ms.isObject()) ms.fields().forEachRemaining(e -> { if (!e.getKey().equals("max_context_window")) keep.put(e.getKey(), e.getValue()); });
            facts.put("model_settings", keep);
        } else facts.put("model_settings", null);
        facts.put("memory_settings", Files.exists(OMLX_SETTINGS) ? JSON.readTree(Files.readString(OMLX_SETTINGS)).path("memory") : null);
        facts.put("omlx_version", Files.exists(OMLX_BIN) ? com.agentbench.docker.DockerService.sh(20, OMLX_BIN.toString(), "version").out().strip() : null);
        facts.put("host_mem", com.agentbench.docker.DockerService.sh(10, "sysctl", "-n", "hw.memsize").out().strip());
        facts.put("docker_up", com.agentbench.docker.DockerService.dockerRunning());
        return new Setup(facts, sha256(JSON.writeValueAsString(facts)));
    }

    record Step(String status, Object promptTokens, Object completionTokens, Object cachedTokens, Object decodeTps,
                Object prefillTps, Object ttftSec, String finish, double latency, String error, int lines) {}

    private Step ask(String endpoint, String key, String model, Map<String, Object> o, int nLines, int maxTokens, boolean generate) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= nLines; i++) text.append(String.format(java.util.Locale.ROOT, LINE, i));
        text.append(generate ? DECODE_TASK : "Reply with the single word OK.");
        Post r = post(endpoint, key, Map.of("model", model,
                "messages", List.of(Map.of("role", "system", "content", generate ? SYSTEM_GEN : SYSTEM), Map.of("role", "user", "content", text.toString())),
                "max_tokens", maxTokens, "temperature", 0), (Integer) o.get("request_timeout_sec"));
        JsonNode u = r.usage() == null ? JSON.createObjectNode() : r.usage();
        return new Step(String.valueOf(r.status()), u.path("prompt_tokens").isMissingNode() ? null : u.path("prompt_tokens").asInt(),
                u.path("completion_tokens").isMissingNode() ? null : u.path("completion_tokens").asInt(),
                u.path("prompt_tokens_details").path("cached_tokens").isMissingNode() ? null : u.path("prompt_tokens_details").path("cached_tokens").asInt(),
                u.path("generation_tokens_per_second").isMissingNode() ? null : u.path("generation_tokens_per_second").asDouble(),
                u.path("prompt_tokens_per_second").isMissingNode() ? null : u.path("prompt_tokens_per_second").asDouble(),
                r.ttftSec(), r.finish(), r.latency(), r.error() == null ? "" : r.error(), nLines);
    }

    /** a transient server failure (5xx, timeout, reset) is retried once after a pause; only a
     *  REPRODUCED failure counts (R5 C-12). */
    Step askRetry(String endpoint, String key, String model, Map<String, Object> o, int nLines, int maxTokens, boolean generate) {
        Step r = ask(endpoint, key, model, o, nLines, maxTokens, generate);
        if (List.of("400", "413", "200").contains(r.status())) return r;
        try { Thread.sleep((Integer) o.get("retry_pause_sec") * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return ask(endpoint, key, model, o, nLines, maxTokens, generate);
    }

    /** three short-context prompts asked to GENERATE: the pure decode rate (R5 C-8) */
    List<Step> decodeSamples(String endpoint, String key, String model, Map<String, Object> o, double tpl, double overhead) {
        List<Step> samples = new ArrayList<>();
        for (List<Object> s : (List<List<Object>>) o.get("decode_samples")) {
            int nTok = ((Number) s.get(0)).intValue(), outTok = ((Number) s.get(1)).intValue();
            Step r = askRetry(endpoint, key, model, o, linesFor(nTok, tpl, overhead), outTok, true);
            samples.add(r);
        }
        return samples;
    }

    static int linesFor(int tokens, double tpl, double overhead) { return Math.max(1, (int) ((tokens - overhead) / tpl)); }

    /** the probe: ascending prompt lengths up to the server cap; binary refinement at the first
     *  failure. Returns the probe record (Map, JSON-shape). */
    public Map<String, Object> probe(String model, String endpoint, String key, Map<String, Object> o) {
        String tStart = Instant.now().toString();
        Integer cap = serverCap(endpoint, key, model);
        // calibrate tokens per line (two points), verified on every later step from the server's own prompt_tokens
        Step a = ask(endpoint, key, model, o, 10, 8, false), b = ask(endpoint, key, model, o, 110, 8, false);
        if (!"200".equals(a.status()) || !"200".equals(b.status()))
            return Map.of("ok", false, "error", "calibration failed: " + (a.error() + b.error()), "cap", cap == null ? "" : cap);
        double tpl = (b.promptTokens() instanceof Number bn ? bn.doubleValue() : 0) - (a.promptTokens() instanceof Number an ? an.doubleValue() : 0);
        tpl /= 100.0;
        double overhead = (a.promptTokens() instanceof Number an ? an.doubleValue() : 0) - 10 * tpl;
        List<Step> samples = decodeSamples(endpoint, key, model, o, tpl, overhead);
        int hardMax = cap == null ? 262144 : cap;
        if (o.containsKey("up_to")) hardMax = Math.min(hardMax, (Integer) o.get("up_to"));   // --up-to: measure the working range only
        int top = hardMax - (Integer) o.get("probe_max_tokens") - 8;
        List<Integer> targets = new ArrayList<>();
        for (int t = (Integer) o.get("step_tokens"); t < top; t += (Integer) o.get("step_tokens")) targets.add(t);
        targets.add(top);
        List<Step> curve = new ArrayList<>();
        int lastOk = 0, firstFail = 0;
        for (int target : targets) {
            Step r = askRetry(endpoint, key, model, o, linesFor(target, tpl, overhead), (Integer) o.get("probe_max_tokens"), false);
            curve.add(r);
            if ("200".equals(r.status())) lastOk = r.promptTokens() instanceof Number n ? n.intValue() : lastOk;
            else { firstFail = target; break; }
        }
        if (firstFail > 0 && firstFail - lastOk > (Integer) o.get("refine_tokens")) {   // binary refinement between the last success and the failure
            int lo = lastOk, hi = firstFail;
            for (int i = 0; i < 4 && hi - lo > (Integer) o.get("refine_tokens"); i++) {
                int mid = (lo + hi) / 2;
                Step r = askRetry(endpoint, key, model, o, linesFor(mid, tpl, overhead), (Integer) o.get("probe_max_tokens"), false);
                curve.add(r);
                if ("200".equals(r.status())) { lo = r.promptTokens() instanceof Number n ? n.intValue() : lo; lastOk = Math.max(lastOk, lo); }
                else hi = mid;
            }
        }
        Step fail = curve.stream().filter(s -> !"200".equals(s.status())).findFirst().orElse(null);
        String binding = null;
        if (fail != null) {
            // the memory guard ALSO answers 400 "Prompt too long": a rejection well below the cap is memory, not the setting
            boolean atCap = cap != null && firstFail >= cap - (Integer) o.get("step_tokens") - (Integer) o.get("probe_max_tokens");
            binding = (List.of("400", "413").contains(fail.status()) && atCap
                    && java.util.regex.Pattern.compile("too long|max_model_len|context", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(fail.error()).find())
                    ? "cap" : "memory_or_server";
        } else if (o.containsKey("up_to") && lastOk >= (Integer) o.get("up_to") - (Integer) o.get("probe_max_tokens") - 64) binding = "probe_limit";
        else if (cap != null && lastOk >= cap - (Integer) o.get("probe_max_tokens") - 64) binding = "cap";
        List<Double> shortDps = samples.stream().filter(s -> "200".equals(s.status()) && s.decodeTps() instanceof Double)
                .map(s -> (Double) s.decodeTps()).toList();
        Double shortMedian = shortDps.isEmpty() ? null : median(shortDps);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("ok", lastOk > 0);
        // never cache a window set by a transient failure: a 4xx (cap or memory guard) is deterministic; a 5xx must reproduce
        rec.put("cacheable", lastOk > 0 && ("cap".equals(binding) || "probe_limit".equals(binding)
                || (fail != null && List.of("400", "413").contains(fail.status())) || (fail == null && cap != null)));
        rec.put("started", tStart);
        rec.put("cap_max_model_len", cap);
        rec.put("max_ok_prompt_tokens", lastOk);
        rec.put("first_fail_tokens", firstFail > 0 ? firstFail : null);
        rec.put("binding", binding);
        rec.put("decode_tps_short", shortMedian == null ? null : Math.round(shortMedian * 10) / 10.0);
        rec.put("memory_guard", guardFacts(tStart));
        rec.put("tokens_per_line", Math.round(tpl * 1000) / 1000.0);
        rec.put("curve", curve.stream().map(s -> Map.of("status", s.status(), "prompt_tokens", s.promptTokens(), "decode_tps", s.decodeTps())).toList());
        return rec;
    }

    static double median(List<Double> xs) { List<Double> s = new ArrayList<>(xs); Collections.sort(s); return s.get(s.size() / 2); }

    /** re-measure only the decode samples of a cached record (the window itself stands) */
    public Map<String, Object> refreshDecode(String model, String endpoint, String key, Map<String, Object> rec, Map<String, Object> o) {
        double tpl = rec.get("tokens_per_line") instanceof Number n ? n.doubleValue() : 31.0;
        List<Double> dps = decodeSamples(endpoint, key, model, o, tpl, 35).stream()
                .filter(s -> "200".equals(s.status()) && s.decodeTps() instanceof Double).map(s -> (Double) s.decodeTps()).toList();
        if (!dps.isEmpty()) rec.put("decode_tps_short", Math.round(median(dps) * 10) / 10.0);
        return rec;
    }

    /** How many agents can this setup run at once and still gain? The honest metric is WORK PER
     *  WALL: speedup(K) = K x wall(1) / wall(K). max_parallel = the largest K with every stream
     *  ok, per-stream decode above the validity floor, and speedup >= 1 + gain x (K-1). */
    public Map<String, Object> parallelCapacity(String model, String endpoint, String key, Map<String, Object> o, Double floor, double tpl) throws Exception {
        int overhead = 35;
        StringBuilder shared = new StringBuilder();
        for (int i = 1; i <= Math.max(1, (int) ((8000 - overhead) / tpl)); i++) shared.append(String.format(java.util.Locale.ROOT, LINE, i));
        int tailLines = Math.max(1, (int) ((((Integer) o.get("parallel_prompt_tokens")) - 8000) / tpl));
        int nonce = (int) (System.currentTimeMillis() / 1000) % 100000;
        List<Map<String, Object>> levels = new ArrayList<>();
        double wall1 = 0; int best = 1;
        for (int K = 1; K <= (Integer) o.get("max_parallel_probe"); K++) {
            Post[] res = new Post[K];
            long t0 = System.nanoTime();
            Thread[] ths = new Thread[K];
            for (int k = 0; k < K; k++) {
                final int idx = k;
                ths[k] = new Thread(() -> {
                    StringBuilder tail = new StringBuilder();
                    for (int i = 1; i <= tailLines; i++) tail.append(String.format(java.util.Locale.ROOT, LINE, 100000 * (idx + 1) + nonce * 7 + i));
                    res[idx] = post(endpoint, key, Map.of("model", model,
                            "messages", List.of(Map.of("role", "system", "content", SYSTEM_GEN),
                                    Map.of("role", "user", "content", shared.toString() + tail + DECODE_TASK)),
                            "max_tokens", o.get("parallel_out_tokens"), "temperature", 0), (Integer) o.get("request_timeout_sec"));
                });
                ths[k].start();
            }
            for (Thread t : ths) t.join();
            double wall = (System.nanoTime() - t0) / 1e9;
            List<Post> ok = Arrays.stream(res).filter(r -> r != null && r.status() == 200 && r.usage() != null && r.usage().hasNonNull("completion_tokens")).toList();
            if (K == 1 && !ok.isEmpty()) wall1 = wall;
            Double speedup = wall1 > 0 ? Math.round(K * wall1 / wall * 100) / 100.0 : null;
            List<Double> per = ok.stream().map(r -> r.usage().path("generation_tokens_per_second").asDouble(0)).filter(d -> d > 0).sorted().toList();
            Double perMed = per.isEmpty() ? null : per.get(per.size() / 2);
            levels.add(Map.of("K", K, "ok", ok.size(), "wall_sec", Math.round(wall * 10) / 10.0,
                    "speedup_work_per_wall", speedup == null ? "" : speedup, "per_stream_decode_median", perMed == null ? "" : perMed));
            boolean gaining = speedup != null && speedup >= 1 + ((Number) o.get("parallel_gain_per_agent")).doubleValue() * (K - 1);
            boolean fastEnough = perMed != null && (floor == null || perMed >= floor);
            if (ok.size() == K && gaining && fastEnough) best = K;
            else break;
        }
        return Map.of("levels", levels, "max_parallel", best);
    }

    /** the largest probed context at which decode was still at or above the floor: speed, not
     *  memory, is the practical limit of a long window. */
    public static Integer performanceWindow(Map<String, Object> rec, Double floor) {
        int[][] pts = curvePoints(rec);
        if (pts.length == 0 || floor == null) return null;
        int lastOk = -1;
        for (int[] p : pts) if (p[1] >= floor) lastOk = Math.max(lastOk, p[0]);
        return lastOk < 0 ? pts[0][0] : lastOk;
    }

    @SuppressWarnings("unchecked")
    static int[][] curvePoints(Map<String, Object> rec) {
        List<int[]> pts = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) rec.getOrDefault("curve", List.of()))
            if ("200".equals(String.valueOf(c.get("status"))) && c.get("decode_tps") instanceof Double && c.get("prompt_tokens") instanceof Integer)
                pts.add(new int[]{(Integer) c.get("prompt_tokens"), (int) Math.round((Double) c.get("decode_tps"))});
        return pts.toArray(new int[0][]);
    }

    /** decode speed (tok/s) at a prompt of `n` tokens, linearly interpolated on the probe curve; flat past the ends */
    public static Double decodeAt(List<double[]> curve, double n) {
        if (curve == null || curve.isEmpty()) return null;
        List<double[]> pts = new ArrayList<>(curve);
        pts.sort(Comparator.comparingDouble(p -> p[0]));
        if (n <= pts.get(0)[0]) return pts.get(0)[1];
        if (n >= pts.get(pts.size() - 1)[0]) return pts.get(pts.size() - 1)[1];
        for (int i = 1; i < pts.size(); i++)
            if (pts.get(i - 1)[0] <= n && n <= pts.get(i)[0]) {
                double x0 = pts.get(i - 1)[0], y0 = pts.get(i - 1)[1], x1 = pts.get(i)[0], y1 = pts.get(i)[1];
                double interp = y0 + (y1 - y0) * (n - x0) / Math.max(1, x1 - x0);   // the WHOLE expression, then round (Python: round(expr, 2))
                return Math.round(interp * 100) / 100.0;
            }
        return pts.get(pts.size() - 1)[1];
    }

    /** every context-dependent knob from the measured window (the `derived` manifest block).
     *  The EFFECTIVE window = min(memory, performance, operator cap) drives checkpoints, packs and
     *  output; token budgets in windows are additionally capped by what can be GENERATED in the
     *  wall budget (wall x short-context decode x 0.8). */
    public static Map<String, Object> derive(Map<String, Object> rec, BenchProperties props, Integer taskWallSec) {
        Map<String, Object> o = DEFAULTS;
        int maxOk = rec.get("max_ok_prompt_tokens") instanceof Number n ? n.intValue() : 0;
        int memoryWindow;
        if ("cap".equals(rec.get("binding")) && rec.get("cap_max_model_len") instanceof Number c)
            memoryWindow = c.intValue() / 2048 * 2048;   // a cap is exact (after prepare_cap it is the model's positional limit)
        else
            memoryWindow = (maxOk - (int) o.get("safety_tokens")) / 2048 * 2048;   // a memory-bound limit gets the safety margin
        Double dps = rec.get("decode_tps_short") instanceof Number n ? n.doubleValue() : null;
        Double floor = dps == null ? null : Math.round(dps * ((Number) o.get("decode_floor_fraction")).doubleValue() * 10) / 10.0;
        Double perfFloor = dps == null ? null : Math.round(dps * ((Number) o.get("perf_fraction")).doubleValue() * 10) / 10.0;
        Integer perf = performanceWindow(rec, perfFloor);
        Map<String, Integer> candidates = new LinkedHashMap<>();
        candidates.put("memory", Math.max(memoryWindow, 0));
        if (perf != null) candidates.put("performance", perf / 2048 * 2048);
        if (props.contextWindow() != null) candidates.put("operator cap", props.contextWindow());
        Map.Entry<String, Integer> limited = candidates.entrySet().stream().min(Map.Entry.comparingByValue()).orElse(null);
        String limitedBy = limited == null ? "memory" : limited.getKey();
        if ("cap".equals(rec.get("binding")) && "memory".equals(limitedBy)) limitedBy = "positional/cap";
        int usable = Math.max(limited == null ? 2048 : limited.getValue(), 2048);
        double scale = Math.max(((Number) o.get("pack_scale_min")).doubleValue(), Math.min(1.0, usable / (double) (int) o.get("pack_reference_window")));
        int maxOut = Math.min(props.maxOutputTokens() == null ? 8192 : props.maxOutputTokens(), (int) (usable * ((Number) o.get("max_output_fraction")).doubleValue()) / 512 * 512);
        int trigger = (int) (usable * ((Number) o.get("trigger_fraction")).doubleValue()) / 256 * 256;
        Integer genCap = null;
        if (taskWallSec != null && dps != null) genCap = (int) (taskWallSec * dps * 0.8) / 1000 * 1000;
        int taskTokens = (int) (usable * ((Number) o.get("task_budget_windows")).doubleValue()) / 1000 * 1000;
        if (genCap != null) taskTokens = Math.min(taskTokens, Math.max(genCap, 10000));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("usable_context", usable);
        d.put("memory_window", memoryWindow);
        d.put("performance_window", perf);
        d.put("performance_floor_tps", perfFloor);
        d.put("window_limited_by", limitedBy);
        d.put("max_output_tokens", maxOut);
        d.put("compaction_trigger_tokens", trigger);
        d.put("compaction", Map.of("reserveTokens", Math.max(4096, usable - trigger),
                "keepRecentTokens", (int) (usable * ((Number) o.get("keep_recent_fraction")).doubleValue()) / 256 * 256));
        d.put("pack_scale", Math.round(scale * 1000) / 1000.0);
        d.put("task_tokens", taskTokens);
        d.put("task_tokens_generation_cap", genCap);
        d.put("plan_tokens", (int) (usable * ((Number) o.get("plan_budget_windows")).doubleValue()) / 1000 * 1000);
        d.put("impl_tokens_l7", (int) (usable * ((Number) o.get("impl_budget_windows_l7")).doubleValue()) / 1000 * 1000);
        d.put("impl_tokens", (int) (usable * ((Number) o.get("impl_budget_windows")).doubleValue()) / 1000 * 1000);
        d.put("min_decode_tps", floor);
        d.put("decode_tps_short", dps);
        d.put("max_parallel", rec.get("parallel_capacity") instanceof Map<?, ?> pc && ((Map<?, ?>) pc).get("max_parallel") instanceof Number n ? n.intValue() : 1);
        d.put("binding", rec.get("binding"));
        d.put("safety_applied", !"cap".equals(rec.get("binding")));
        d.put("too_small", usable < (int) o.get("min_usable"));
        return d;
    }

    /** cached probe for this setup (or a fresh one). Returns {probe, derived, cache_path, error}. */
    public Map<String, Object> ensure(BenchProperties props, boolean fresh, Integer taskWallSec, Map<String, Object> overrides) throws Exception {
        Map<String, Object> o = new LinkedHashMap<>(DEFAULTS);
        if (overrides != null) o.putAll(overrides);
        List<String> modelDirs = List.of();
        if (Files.exists(OMLX_SETTINGS)) {
            JsonNode md = JSON.readTree(Files.readString(OMLX_SETTINGS)).path("model").path("model_dirs");
            if (md.isArray()) { List<String> dirs = new ArrayList<>(); md.forEach(n -> dirs.add(n.asText())); modelDirs = dirs; }
        }
        Setup setup = setupKey(props.model(), props.endpoint(), props.apiKey() == null ? "" : props.apiKey(), modelDirs);
        Files.createDirectories(CACHE_DIR);
        Path path = CACHE_DIR.resolve(props.model() + "-" + setup.key() + ".json");
        Map<String, Object> rec = null;
        if (!fresh && Files.exists(path)) {
            try {
                rec = JSON.readValue(Files.readString(path), Map.class);
                double ageDays = (System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis()) / 86400_000.0;
                if (ageDays > (int) o.get("cache_max_age_days") || !Boolean.TRUE.equals(rec.get("ok"))) rec = null;
                else if (rec.get("decode_tps_short") == null && !Boolean.TRUE.equals(setup.facts().get("docker_up")))
                    rec = refreshDecode(props.model(), props.endpoint(), props.apiKey() == null ? "" : props.apiKey(), rec, o);
                else if (rec.get("parallel_capacity") == null && !Boolean.TRUE.equals(setup.facts().get("docker_up")) && (int) o.get("max_parallel_probe") > 0) {
                    Double fl = rec.get("decode_tps_short") instanceof Number n ? Math.round(n.doubleValue() * ((Number) o.get("decode_floor_fraction")).doubleValue() * 10) / 10.0 : null;
                    rec.put("parallel_capacity", parallelCapacity(props.model(), props.endpoint(), props.apiKey() == null ? "" : props.apiKey(), o, fl,
                            rec.get("tokens_per_line") instanceof Number n ? n.doubleValue() : 31.0));
                }
                if (rec != null) Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rec));
            } catch (Exception ignore) { rec = null; }
        }
        if (rec == null) {
            if (Boolean.TRUE.equals(setup.facts().get("docker_up")))
                return Map.of("error", "Docker Desktop is up: a probe now would measure a contended machine (decode ~4x slower, memory shared with the VM); quit Docker, or pin context-window in the config");
            Map<String, Object> capInfo = prepareCap(props.model(), props.endpoint(), props.apiKey() == null ? "" : props.apiKey(), modelDirs, o);
            rec = probe(props.model(), props.endpoint(), props.apiKey() == null ? "" : props.apiKey(), o);
            rec.put("setup", setup.facts());
            rec.put("setup_key", setup.key());
            rec.put("cap", capInfo);
            if (Boolean.TRUE.equals(rec.get("ok")) && (int) o.get("max_parallel_probe") > 0) {
                Double fl = rec.get("decode_tps_short") instanceof Number n ? Math.round(n.doubleValue() * ((Number) o.get("decode_floor_fraction")).doubleValue() * 10) / 10.0 : null;
                rec.put("parallel_capacity", parallelCapacity(props.model(), props.endpoint(), props.apiKey() == null ? "" : props.apiKey(), o, fl,
                        rec.get("tokens_per_line") instanceof Number n ? n.doubleValue() : 31.0));
            }
            if (Boolean.TRUE.equals(rec.get("ok")) && !"probe_limit".equals(rec.get("binding"))) {
                Map<String, Object> d0 = derive(rec, props, taskWallSec);
                rec.put("cap_after", finalizeCap(props.model(), props.endpoint(), props.apiKey() == null ? "" : props.apiKey(), (Integer) d0.get("memory_window"), o));
            }
            if (Boolean.TRUE.equals(rec.get("ok")) && Boolean.TRUE.equals(rec.get("cacheable")))
                Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rec));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probe", rec);
        out.put("derived", Boolean.TRUE.equals(rec.get("ok")) ? derive(rec, props, taskWallSec) : null);
        out.put("cache_path", path.toString());
        return out;
    }

    static String sha256(Path p) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))).substring(0, 16);
    }
    static String sha256(String s) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes())).substring(0, 16); }
        catch (Exception e) { return null; }
    }
}
