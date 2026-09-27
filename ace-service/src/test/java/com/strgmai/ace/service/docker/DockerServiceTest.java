package com.strgmai.ace.service.docker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Found live 2026-09-27: dockerUp() relaunched exactly once, at the timeout's halfway mark, no
 *  matter how far into a crash the backend was, and the idle monitor could kill Docker mid-operation
 *  because dockerCliBusy() had no caller anywhere. Both decision policies are pulled out of their
 *  real subprocess-spawning callers so they're unit-testable in isolation. */
class DockerServiceTest {

    @Test
    void shouldRelaunchWhenTheBackendProcessIsGoneEntirely() {
        // crashed mid-boot: relaunch immediately, regardless of how much of the interval remains
        assertTrue(DockerService.shouldRelaunch(false, 1_000L, 100_000L));
    }

    @Test
    void shouldNotRelaunchWhileStillBootingWithinTheInterval() {
        assertFalse(DockerService.shouldRelaunch(true, 1_000L, 100_000L));
    }

    @Test
    void shouldRelaunchWhenTheIntervalHasElapsedEvenIfTheBackendIsAlive() {
        // still alive, but has taken too long to answer - relaunch rather than wait out a hang
        assertTrue(DockerService.shouldRelaunch(true, 100_000L, 100_000L));
        assertTrue(DockerService.shouldRelaunch(true, 200_000L, 100_000L));
    }

    @Test
    void shouldCloseWindowWhenTheBackendIsAlreadyDownRegardlessOfBusyOrIdle() {
        assertTrue(DockerService.shouldCloseWindow(false, 0, 600, true));
        assertTrue(DockerService.shouldCloseWindow(false, 9999, 600, false));
    }

    @Test
    void shouldNotCloseWindowBeforeTheIdleThresholdElapses() {
        assertFalse(DockerService.shouldCloseWindow(true, 100, 600, false));
    }

    /** the actual bug: idle time alone used to be enough to kill Docker, even mid `docker compose
     *  up`/`docker build` - the shim only logs at invocation time, so a long-running command with no
     *  fresh log entry looked identical to genuine idleness. */
    @Test
    void shouldNotCloseWindowPastTheIdleThresholdWhileADockerCliOperationIsActivelyRunning() {
        assertFalse(DockerService.shouldCloseWindow(true, 9999, 600, true));
    }

    @Test
    void shouldCloseWindowPastTheIdleThresholdWhenNothingIsActuallyRunning() {
        assertTrue(DockerService.shouldCloseWindow(true, 9999, 600, false));
    }

    /** the settings-file edit (found live 2026-09-28): the same file Docker Desktop's own
     *  Settings > Resources > Memory slider writes to, taking the file explicitly so this is
     *  testable against a temp file instead of the host's real Docker Desktop configuration. */
    @Test
    void ensureMemoryCapWritesTheNewValueAndReturnsTrueWhenItChanges(@TempDir final Path tmp) throws Exception {
        final Path settings = tmp.resolve("settings-store.json");
        Files.writeString(settings, "{\"cpus\": 4, \"memoryMiB\": 8192, \"diskSizeMiB\": 122880}");

        assertTrue(DockerService.ensureMemoryCap(settings, 4096));

        final String written = Files.readString(settings);
        assertTrue(written.contains("\"memoryMiB\" : 4096") || written.contains("\"memoryMiB\":4096"), written);
        assertTrue(written.contains("122880"), "every other setting must survive untouched: " + written);
    }

    @Test
    void ensureMemoryCapIsANoOpWhenAlreadyAtTheDesiredValue(@TempDir final Path tmp) throws Exception {
        final Path settings = tmp.resolve("settings-store.json");
        Files.writeString(settings, "{\"cpus\": 4, \"memoryMiB\": 4096}");

        assertFalse(DockerService.ensureMemoryCap(settings, 4096));
        assertTrue(Files.readString(settings).contains("4096"));
    }

    @Test
    void ensureMemoryCapReturnsFalseRatherThanThrowWhenTheSettingsFileIsMissing(@TempDir final Path tmp) {
        assertFalse(DockerService.ensureMemoryCap(tmp.resolve("does-not-exist.json"), 4096));
    }
}
