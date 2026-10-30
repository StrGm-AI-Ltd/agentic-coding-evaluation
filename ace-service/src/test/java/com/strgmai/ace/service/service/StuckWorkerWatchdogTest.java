package com.strgmai.ace.service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #593: unit tests for StuckWorkerWatchdog's two pure helpers. check() itself (the Runtime.halt()
 *  call) is deliberately NOT driven here - unlike every other side-effecting entry point in this
 *  codebase, a test accidentally triggering it would kill the test JVM itself, not just fail an
 *  assertion. isStuck()/lastWriteTime() carry the actual decision logic and are fully covered;
 *  check()'s own wiring is a few lines of glue, verified by code review and live production
 *  behavior instead - the same split already established for JobLiveSection.shouldRebuildChart(). */
class StuckWorkerWatchdogTest {

    @Test
    void isStuck_trueOnlyPastTheThreshold() {
        final var now = Instant.parse("2026-10-30T12:00:00Z");
        assertFalse(StuckWorkerWatchdog.isStuck(now, now.minus(Duration.ofMinutes(29)), Duration.ofMinutes(30)),
                "a legitimate gap just under the threshold must not trip the watchdog");
        assertTrue(StuckWorkerWatchdog.isStuck(now, now.minus(Duration.ofMinutes(31)), Duration.ofMinutes(30)));
        assertFalse(StuckWorkerWatchdog.isStuck(now, now, Duration.ofMinutes(30)), "just updated - never stuck");
    }

    @Test
    void lastWriteTime_readsARealFilesMtime(@TempDir final Path dir) throws IOException {
        final var file = dir.resolve("interactions.jsonl");
        final var before = Instant.now().minusSeconds(5);
        Files.writeString(file, "{}\n");

        final var mtime = StuckWorkerWatchdog.lastWriteTime(file);

        assertTrue(mtime.isPresent());
        assertTrue(mtime.get().isAfter(before), "mtime must reflect the write that just happened");
    }

    @Test
    void lastWriteTime_emptyWhenTheFileDoesNotExistYet(@TempDir final Path dir) {
        assertEquals(java.util.Optional.empty(), StuckWorkerWatchdog.lastWriteTime(dir.resolve("interactions.jsonl")),
                "a job's very first request hasn't landed yet - the caller falls back to its start time");
    }
}
