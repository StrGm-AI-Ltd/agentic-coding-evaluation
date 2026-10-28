package com.strgmai.ace.service.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strgmai.ace.service.docker.DockerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** #65: the reference/default `LocalModelBackend` - oMLX, on this one machine. Every method here is
 *  a verbatim relocation of logic that used to live inline in `ContextProbe`; none of it is
 *  redesigned, only relocated and given a constructor-injected root directory instead of hardcoding
 *  `System.getProperty("user.home")` into static fields (the latter is what made ContextProbe's own
 *  backend operations untestable - see ContextProbeTest's own comment on this). */
public final class OmlxBackend implements LocalModelBackend {
    private static final Logger log = LoggerFactory.getLogger(OmlxBackend.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path logFile;
    private final Path bin;
    private final Path modelSettings;
    private final Path omlxSettings;

    /** `omlxHome` is normally `~/.omlx` in production (see the `LocalModelBackend` bean in
     *  AceServiceApplication); tests pass a temp directory so these methods exercise real file I/O
     *  without touching the operator's actual oMLX install. */
    public OmlxBackend(final Path omlxHome) {
        this.logFile = omlxHome.resolve("logs/server.log");
        this.bin = omlxHome.resolve("bin/omlx");
        this.modelSettings = omlxHome.resolve("model_settings.json");
        this.omlxSettings = omlxHome.resolve("settings.json");
    }

    @Override
    public String providerKey() { return "omlx"; }

    @Override
    public boolean isAvailable() { return Files.exists(bin); }

    /** `omlx restart` with the orphan trap handled: the old server must be gone and exactly one new
     *  one must answer healthy. Moved verbatim from ContextProbe.restartServer()/pids() - including
     *  its asymmetric interrupt handling: an interrupt while waiting for THIS restart to come back
     *  healthy returns silently (a cancelled job must not get stuck here); an interrupt anywhere in
     *  the orphan-reconciliation fallback is wrapped into IllegalStateException like any other
     *  failure there, same as the original. Only the health check itself is now supplied by the
     *  caller instead of this class owning an HttpClient. */
    @Override
    public void restart(final BooleanSupplier isHealthy) {
        if (!Files.exists(bin)) throw new IllegalStateException("~/.omlx/bin/omlx not present: cannot restart the server");
        final List<Long> old = pids();
        try { new ProcessBuilder(bin.toString(), "restart").start().waitFor(180, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
        final long t0 = System.currentTimeMillis();
        boolean healthy = false;
        while (System.currentTimeMillis() - t0 < 150_000) {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            if (isHealthy.getAsBoolean()) { healthy = true; break; }
        }
        final List<Long> now = pids();
        final List<Long> survivors = old.stream().filter(now::contains).toList();
        try {
            if (!survivors.isEmpty() && now.size() > survivors.size()) {   // the app started a new server but the old one still holds resources
                for (long p : survivors) ProcessHandle.of(p).ifPresent(ProcessHandle::destroy);
            } else if (!survivors.isEmpty() && !healthy) {                   // nothing replaced the old server: kill it and start fresh
                for (long p : survivors) ProcessHandle.of(p).ifPresent(ProcessHandle::destroy);
                Thread.sleep(3000);
                new ProcessBuilder(bin.toString(), "start").start().waitFor(180, TimeUnit.SECONDS);
                final long t = System.currentTimeMillis();
                while (System.currentTimeMillis() - t < 150_000) {
                    Thread.sleep(5000);
                    if (isHealthy.getAsBoolean()) { healthy = true; break; }
                }
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
        if (!healthy) throw new IllegalStateException("oMLX did not come back healthy after the restart; check `pgrep -x omlx-server` for an orphan");
    }

    private static List<Long> pids() {
        final DockerService.Sh out = DockerService.sh(8, "pgrep", "-x", "omlx-server");
        return Arrays.stream(out.out().split("\n")).filter(s -> !s.isBlank()).map(Long::parseLong).toList();
    }

    @Override
    public List<String> modelDirs() throws IOException {
        if (!Files.exists(omlxSettings)) return List.of();
        final JsonNode md = JSON.readTree(Files.readString(omlxSettings)).path("model").path("model_dirs");
        if (!md.isArray()) return List.of();
        final List<String> dirs = new ArrayList<>();
        md.forEach(n -> dirs.add(n.asText()));
        return dirs;
    }

    @Override
    public Object setModelCap(final String model, final int value) throws IOException {
        final ObjectNode d = Files.exists(modelSettings) ? (ObjectNode) JSON.readTree(Files.readString(modelSettings)) : JSON.createObjectNode();
        if (!d.has("models") || !d.get("models").isObject()) d.putObject("models");
        final ObjectNode entry = d.with("models").with(model);
        final Object prev = entry.has("max_context_window") ? entry.get("max_context_window").asInt() : null;
        entry.put("max_context_window", value);
        Files.writeString(modelSettings, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(d));
        return prev;
    }

    @Override
    public void clearModelCap(final String model) throws IOException {
        if (!Files.exists(modelSettings)) return;
        final ObjectNode d = (ObjectNode) JSON.readTree(Files.readString(modelSettings));
        if (d.has("models") && d.get("models").isObject() && d.get("models").has(model)) {
            ((ObjectNode) d.get("models").get(model)).remove("max_context_window");
            Files.writeString(modelSettings, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(d));
        }
    }

    /** Memory-guard lines the server logged since `since`: max usage and the ceiling it was sized
     *  against. Reads the live log and any rotated file of the same day (R5 C-22). */
    @Override
    public Optional<Map<String, Object>> memoryGuardFacts(final String since) {
        final Pattern re = Pattern.compile("usage ([\\d.]+)GB vs sizing target ([\\d.]+)GB, dynamic ceiling ([\\d.]+)");
        final List<Double> usage = new ArrayList<>(), target = new ArrayList<>(), ceiling = new ArrayList<>();
        final List<Path> files = new ArrayList<>(List.of(logFile));
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(logFile.getParent(), logFile.getFileName() + ".*")) {
            final var all = new ArrayList<Path>(); ds.forEach(all::add);
            files.addAll(all.subList(Math.max(0, all.size() - 2), all.size()));
        } catch (Exception e) { log.debug("could not list rotated oMLX log files: {}", e.toString()); }
        for (Path f : files) {
            try {
                for (String line : Files.readAllLines(f)) {
                    if (line.length() >= 19 && line.substring(0, Math.min(19, line.length())).compareTo(since) < 0) continue;
                    final Matcher m = re.matcher(line);
                    if (m.find()) { usage.add(Double.parseDouble(m.group(1))); target.add(Double.parseDouble(m.group(2))); ceiling.add(Double.parseDouble(m.group(3))); }
                }
            } catch (IOException e) { log.debug("could not read oMLX log {}: {}", f, e.toString()); }
        }
        if (usage.isEmpty()) return Optional.empty();
        return Optional.of(Map.of("max_usage_gb", Collections.max(usage), "sizing_target_gb", target.get(target.size() - 1),
                "dynamic_ceiling_gb", ceiling.get(ceiling.size() - 1), "samples", usage.size()));
    }

    @Override
    public Map<String, Object> settingsFingerprint(final String model) throws IOException {
        final Map<String, Object> facts = new LinkedHashMap<>();
        if (Files.exists(modelSettings)) {
            final JsonNode ms = JSON.readTree(Files.readString(modelSettings)).path("models").path(model);
            final Map<String, Object> keep = new LinkedHashMap<>();
            if (ms.isObject()) ms.fields().forEachRemaining(e -> { if (!e.getKey().equals("max_context_window")) keep.put(e.getKey(), e.getValue()); });
            facts.put("model_settings", keep);
        } else facts.put("model_settings", null);
        facts.put("memory_settings", Files.exists(omlxSettings) ? JSON.readTree(Files.readString(omlxSettings)).path("memory") : null);
        facts.put("omlx_version", Files.exists(bin) ? DockerService.sh(20, bin.toString(), "version").out().strip() : null);
        return facts;
    }

    /** Lines matched by oMLX's own "yyyy-MM-dd HH:mm:ss,SSS" prefix; an unprefixed line
     *  (request-log continuation, a bare uvicorn line) inherits the in/out-of-range verdict of the
     *  last dated line before it, so it stays grouped with the entry it belongs to. Moved verbatim
     *  from RunBench.captureOmlxLog(). */
    @Override
    public List<String> logLinesBetween(final Path file, final Instant start, final Instant end) throws IOException {
        final ZoneId zone = ZoneId.systemDefault();
        final LocalDateTime startLocal = LocalDateTime.ofInstant(start, zone);
        final LocalDateTime endLocal = LocalDateTime.ofInstant(end, zone);
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
        final List<String> slice = new ArrayList<>();
        boolean inRange = false;
        for (String line : Files.readAllLines(file)) {
            if (line.length() >= 23) {
                try {
                    final LocalDateTime ts = LocalDateTime.parse(line.substring(0, 23), fmt);
                    inRange = !ts.isBefore(startLocal) && !ts.isAfter(endLocal);
                } catch (Exception notADatedLine) { /* keep the previous verdict */ }
            }
            if (inRange) slice.add(line);
        }
        return slice;
    }
}
