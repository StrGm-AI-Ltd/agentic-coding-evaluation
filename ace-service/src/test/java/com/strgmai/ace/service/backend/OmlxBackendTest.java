package com.strgmai.ace.service.backend;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** #65: OmlxBackend's own file/log operations, each a verbatim relocation of logic that used to be
 *  inline in ContextProbe/RunBench - exercised here against REAL temp-directory files, the test
 *  seam those methods never had before (ContextProbeTest's own comment used to document this
 *  explicitly as a gap). */
class OmlxBackendTest {

    private static OmlxBackend backend() throws Exception { return new OmlxBackend(Files.createTempDirectory("omlx-home")); }

    @Test
    void providerKeyIsOmlx() throws Exception {
        assertEquals("omlx", backend().providerKey());
    }

    @Test
    void speedFieldNamesAreOmlxsOwnNonStandardUsageFields() throws Exception {
        final var names = backend().speedFieldNames();
        assertEquals("prompt_tokens_per_second", names.prefillTpsField());
        assertEquals("generation_tokens_per_second", names.decodeTpsField());
    }

    @Test
    void isAvailableIsFalseWhenNoBinaryIsPresent() throws Exception {
        assertFalse(backend().isAvailable());
    }

    @Test
    void isAvailableIsTrueWhenTheBinaryFileExists() throws Exception {
        final Path home = Files.createTempDirectory("omlx-home");
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin/omlx"));
        assertTrue(new OmlxBackend(home).isAvailable());
    }

    @Test
    void setModelCapWritesAFreshFileAndReturnsNullForAPreviouslyUnsetModel() throws Exception {
        final OmlxBackend b = backend();
        final Object prev = b.setModelCap("my-model", 65536);
        assertNull(prev, "no prior override existed");
    }

    @Test
    void setModelCapReturnsThePreviousValueAndClearModelCapRemovesIt() throws Exception {
        final OmlxBackend b = backend();
        b.setModelCap("my-model", 32768);
        final Object prev = b.setModelCap("my-model", 65536);
        assertEquals(32768, prev);

        b.clearModelCap("my-model");
        // clearing then setting again must see no override (not the stale 65536)
        assertNull(b.setModelCap("my-model", 1000));
    }

    @Test
    void clearModelCapIsANoOpWhenNoSettingsFileExistsYet() throws Exception {
        assertDoesNotThrow(() -> backend().clearModelCap("never-configured-model"));
    }

    @Test
    void modelDirsIsEmptyWhenNoSettingsFileExists() throws Exception {
        assertEquals(List.of(), backend().modelDirs());
    }

    @Test
    void modelDirsReadsTheConfiguredListFromSettingsJson() throws Exception {
        final Path home = Files.createTempDirectory("omlx-home");
        Files.writeString(home.resolve("settings.json"), "{\"model\":{\"model_dirs\":[\"/models/a\",\"/models/b\"]}}");
        assertEquals(List.of("/models/a", "/models/b"), new OmlxBackend(home).modelDirs());
    }

    @Test
    void memoryGuardFactsIsEmptyWhenTheLogFileDoesNotExist() throws Exception {
        assertEquals(Optional.empty(), backend().memoryGuardFacts("2026-01-01 00:00:00"));
    }

    @Test
    void memoryGuardFactsParsesTheThrottleLineFormat() throws Exception {
        final Path home = Files.createTempDirectory("omlx-home");
        Files.createDirectories(home.resolve("logs"));
        Files.write(home.resolve("logs/server.log"), List.of(
                "2026-10-28 00:12:24,620 - omlx.scheduler - INFO - usage 14.96GB vs sizing target 24.66GB, dynamic ceiling 25.42GB"));

        final Optional<Map<String, Object>> facts = new OmlxBackend(home).memoryGuardFacts("2026-10-28 00:00:00");

        assertTrue(facts.isPresent());
        assertEquals(14.96, facts.get().get("max_usage_gb"));
        assertEquals(24.66, facts.get().get("sizing_target_gb"));
        assertEquals(25.42, facts.get().get("dynamic_ceiling_gb"));
        assertEquals(1, facts.get().get("samples"));
    }

    @Test
    void settingsFingerprintReportsNullsWhenNothingIsConfigured() throws Exception {
        final Map<String, Object> f = backend().settingsFingerprint("my-model");
        assertNull(f.get("model_settings"));
        assertNull(f.get("memory_settings"));
        assertNull(f.get("omlx_version"));
    }

    @Test
    void settingsFingerprintExcludesMaxContextWindowFromTheModelSettingsItReports() throws Exception {
        final Path home = Files.createTempDirectory("omlx-home");
        Files.writeString(home.resolve("model_settings.json"),
                "{\"models\":{\"my-model\":{\"max_context_window\":65536,\"quantization\":\"q4\"}}}");

        final Map<String, Object> f = new OmlxBackend(home).settingsFingerprint("my-model");

        @SuppressWarnings("unchecked")
        final Map<String, Object> modelSettings = (Map<String, Object>) f.get("model_settings");
        assertFalse(modelSettings.containsKey("max_context_window"), "the cap is probe-controlled, not part of the fingerprint");
        assertTrue(modelSettings.containsKey("quantization"));
    }

    /** #65: moved from RunBenchTest's own captureOmlxLogKeepsOnlyLinesWithinTheRunsOwnWindow... -
     *  lines matched by oMLX's own "yyyy-MM-dd HH:mm:ss,SSS" prefix; an unprefixed line (a
     *  continuation) inherits the in/out-of-range verdict of the last dated line before it. */
    @Test
    void logLinesBetweenKeepsOnlyLinesInTheWindowPlusTheirContinuations() throws Exception {
        final ZoneId zone = ZoneId.systemDefault();   // oMLX's own log has no zone offset - it's local time
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
        final Instant start = Instant.parse("2026-09-25T07:00:00Z"), end = Instant.parse("2026-09-25T08:00:00Z");
        final String before = fmt.format(LocalDateTime.ofInstant(start.minusSeconds(120), zone));
        final String firstInRange = fmt.format(LocalDateTime.ofInstant(start.plusSeconds(60), zone));
        final String lastInRange = fmt.format(LocalDateTime.ofInstant(end.minusSeconds(60), zone));
        final String after = fmt.format(LocalDateTime.ofInstant(end.plusSeconds(120), zone));

        final Path src = Files.createTempFile("omlx-server", ".log");
        Files.write(src, List.of(
                before + " - omlx.server - INFO - before the run, must be dropped",
                firstInRange + " - omlx.server - INFO - first line inside the run",
                "    a continuation line with no timestamp, belongs to the entry above",
                lastInRange + " - omlx.server - INFO - last line inside the run",
                after + " - omlx.server - INFO - after the run, must be dropped"));

        final List<String> kept = backend().logLinesBetween(src, start, end);

        assertEquals(3, kept.size(), "the two in-range dated lines plus the continuation, nothing outside the window: " + kept);
        assertTrue(kept.get(0).contains("first line inside the run"));
        assertTrue(kept.get(1).contains("continuation line"));
        assertTrue(kept.get(2).contains("last line inside the run"));
    }
}
