package com.agentbench.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompareViewPoolableTest {

    private static Api.Run run(String runId, boolean poolable) {
        return new Api.Run(runId, null, null, null, null, null, null, null, poolable,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void onlyPoolableRunsOffered() {
        List<String> ids = CompareView.poolableRunIds(List.of(
                run("b-run", true),
                run("a-run", true),
                run("c-run", false),   // not poolable: stats.py would 404 it
                run("d-run", false)));
        assertEquals(List.of("a-run", "b-run"), ids, "sorted, distinct, poolable only");
    }

    @Test
    void emptyListStaysEmpty() {
        assertEquals(List.of(), CompareView.poolableRunIds(List.of()));
    }
}
