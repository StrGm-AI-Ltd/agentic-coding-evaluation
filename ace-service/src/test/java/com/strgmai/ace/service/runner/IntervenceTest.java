package com.strgmai.ace.service.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervenceTest {

    @Test
    void pollReturnsNullWhenNothingWasOffered() {
        final var intervene = new Intervene();
        assertNull(intervene.poll("svc-1", "T1"));
    }

    @Test
    void offeredTextComesBackOnPollWrappedWithTheOperatorMarker() {
        final var intervene = new Intervene();
        intervene.offer("svc-1", "T1", "stop retrying X, do Y instead");
        final var delivered = intervene.poll("svc-1", "T1");
        assertTrue(delivered.contains("[Operator intervention]"), "must be clearly marked for later log review");
        assertTrue(delivered.contains("stop retrying X, do Y instead"), "the operator's own text must survive verbatim");
    }

    @Test
    void pollIsFifoAndConsumesEachMessageOnce() {
        final var intervene = new Intervene();
        intervene.offer("svc-1", "T1", "first");
        intervene.offer("svc-1", "T1", "second");
        assertTrue(intervene.poll("svc-1", "T1").contains("first"));
        assertTrue(intervene.poll("svc-1", "T1").contains("second"));
        assertNull(intervene.poll("svc-1", "T1"), "each message is delivered at most once");
    }

    @Test
    void differentRunOrTaskIdsDoNotCollide() {
        final var intervene = new Intervene();
        intervene.offer("svc-1", "T1", "for run 1 task 1");
        intervene.offer("svc-1", "T2", "for run 1 task 2");
        intervene.offer("svc-2", "T1", "for run 2 task 1");
        assertTrue(intervene.poll("svc-1", "T1").contains("for run 1 task 1"));
        assertTrue(intervene.poll("svc-1", "T2").contains("for run 1 task 2"));
        assertTrue(intervene.poll("svc-2", "T1").contains("for run 2 task 1"));
    }

    @Test
    void clearDiscardsAnythingStillPending() {
        final var intervene = new Intervene();
        intervene.offer("svc-1", "T1", "never delivered");
        intervene.clear("svc-1", "T1");
        assertNull(intervene.poll("svc-1", "T1"));
    }

    @Test
    void clearOnlyAffectsItsOwnKey() {
        final var intervene = new Intervene();
        intervene.offer("svc-1", "T1", "keep me");
        intervene.offer("svc-1", "T2", "clear me");
        intervene.clear("svc-1", "T2");
        assertTrue(intervene.poll("svc-1", "T1").contains("keep me"));
        assertNull(intervene.poll("svc-1", "T2"));
    }
}
