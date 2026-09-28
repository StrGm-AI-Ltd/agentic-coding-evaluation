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

    /** Found live 2026-09-28: opening Docker Desktop's REAL settings file from a launchd-submitted
     *  (non-interactive) process hung INDEFINITELY - a macOS privacy/TCC gate on Group Containers
     *  access apparently blocks native file I/O waiting on a UI prompt a headless job never sees.
     *  Every --manage-docker run hung before ever creating a journal (confirmed via a live thread
     *  dump: stuck in FileInputStream.open0 inside ensureMemoryCap). A FIFO reproduces the same
     *  "open() for read blocks forever until a writer connects" shape without needing the real
     *  macOS permission gate - opening it for reading blocks exactly like the stuck native call did. */
    @Test
    void applyMemoryCapGivesUpAroundItsOwnTimeoutInsteadOfHangingWhenTheSettingsFileReadBlocks(@TempDir final Path tmp) throws Exception {
        final Path fifo = tmp.resolve("blocking-settings.json");
        assertEquals(0, new ProcessBuilder("mkfifo", fifo.toString()).inheritIO().start().waitFor(),
                "mkfifo must succeed to set up this test's blocking read");

        final long t0 = System.currentTimeMillis();
        DockerService.applyMemoryCap(fifo, 4096, 2);   // a short timeout - must not wait the real 15s default
        final long elapsedMs = System.currentTimeMillis() - t0;

        assertTrue(elapsedMs < 10_000, "applyMemoryCap must give up around its own timeout, not hang on a blocked read: " + elapsedMs + "ms");
    }

    /** Found live 2026-09-28: PARALLEL_PLAN runs multiple task sessions concurrently, each with its
     *  own independent DockerWindowMonitor - one task's idle window could kill Docker Desktop
     *  mid-operation for a sibling task whose own window was still open. shouldTearDownOnWindowClose
     *  is the pure decision windowClosed() makes, testable without the shared static counter. */
    @Test
    void shouldTearDownOnlyWhenThisWasTheLastOpenWindowAndDockerIsStillUp() {
        assertTrue(DockerService.shouldTearDownOnWindowClose(true, 0));
        assertTrue(DockerService.shouldTearDownOnWindowClose(true, -1));   // an unbalanced count still means "no one else"
    }

    @Test
    void shouldNotTearDownWhileASiblingSessionsWindowIsStillOpen() {
        assertFalse(DockerService.shouldTearDownOnWindowClose(true, 1));
        assertFalse(DockerService.shouldTearDownOnWindowClose(true, 2));
    }

    @Test
    void shouldNotTearDownWhenDockerIsAlreadyDownRegardlessOfTheCount() {
        assertFalse(DockerService.shouldTearDownOnWindowClose(false, 0));
        assertFalse(DockerService.shouldTearDownOnWindowClose(false, 5));
    }

    /** windowOpened()/windowClosed() themselves, verified via the shared counter - stillUp=false on
     *  every close so shouldTearDownOnWindowClose() never fires and this never touches the real
     *  Docker Desktop on the machine running the test. */
    @Test
    void windowOpenedAndClosedTrackTheSharedCountCorrectly() {
        final int before = DockerService.openWindowCount();
        DockerService.windowOpened();
        DockerService.windowOpened();
        assertEquals(before + 2, DockerService.openWindowCount(), "two sibling sessions' windows must both be counted");
        DockerService.windowClosed(false, 1);
        assertEquals(before + 1, DockerService.openWindowCount(), "one closing must not affect the other still-open window");
        DockerService.windowClosed(false, 1);
        assertEquals(before, DockerService.openWindowCount());
    }

    /** isDockerCommand() (found live 2026-09-28): dockerCliBusy() used to match only "docker compose"/
     *  "docker build"/"docker-compose" as a COMMAND-LINE substring, so a plain `docker run`,
     *  `docker exec`, `docker pull`, etc. was invisible to it - the idle monitor could still kill
     *  Docker mid-operation for every other subcommand. Now matches the EXECUTABLE instead, so any
     *  real `docker` invocation counts, regardless of subcommand. Tested as a pure function rather
     *  than against a real spawned process: ProcessHandle.Info.command()/commandLine() were found
     *  live 2026-09-28 to return empty even for the TEST JVM's own direct children on this macOS
     *  setup, making a real-process test unreliable/environment-dependent. */
    @Test
    void isDockerCommandMatchesTheRealCliRegardlessOfSubcommand() {
        assertTrue(DockerService.isDockerCommand("/Applications/Docker.app/Contents/Resources/bin/docker"));
        assertTrue(DockerService.isDockerCommand("/usr/local/bin/docker"));
        assertTrue(DockerService.isDockerCommand("docker"));
    }

    @Test
    void isDockerCommandRejectsUnrelatedOrNullCommands() {
        assertFalse(DockerService.isDockerCommand("/bin/sh"));
        assertFalse(DockerService.isDockerCommand("/usr/local/bin/docker-credential-desktop"));   // a real, unrelated docker* helper binary
        assertFalse(DockerService.isDockerCommand(null));   // ProcessHandle.Info.command() is frequently empty (permission-restricted)
    }
}
