package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompareViewPoolableTest {

    @Test
    void onlyPoolableRunsOffered() {
        final var ids = CompareView.poolableRunIds(List.of(
                ApiFixtures.run("b-run", true),
                ApiFixtures.run("a-run", true),
                ApiFixtures.run("c-run", false),   // not poolable: stats.py would 404 it
                ApiFixtures.run("d-run", false)));
        assertEquals(List.of("a-run", "b-run"), ids, "sorted, distinct, poolable only");
    }

    @Test
    void emptyListStaysEmpty() {
        assertEquals(List.of(), CompareView.poolableRunIds(List.of()));
    }
}
