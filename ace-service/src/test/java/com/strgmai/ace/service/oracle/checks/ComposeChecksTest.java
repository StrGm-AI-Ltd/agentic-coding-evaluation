package com.strgmai.ace.service.oracle.checks;

import com.strgmai.ace.service.oracle.CheckId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** #281, found live: each F-check scenario appends its own HTTP exchanges to the shared suite.calls
 *  list as it runs, and the dispatch loop in ComposeChecks.run() walks IDS in its own iteration
 *  order - so IDS's declared order IS the order the scenarios actually run in, not just a display
 *  concern. F6 (f6ContractConformance) grades every observed response so far against the contract,
 *  so it must run AFTER F7/F8/F9 - otherwise it silently grades an incomplete set of exchanges,
 *  never seeing F7's deposit-rounding call, F8's asOf-boundary call, or F9's idempotency-key-repeat
 *  call (the only call that can ever produce a "POST /orders 200" response). */
class ComposeChecksTest {

    @Test
    void f6RunsAfterF7F8AndF9SoItGradesTheirResponsesToo() {
        final List<CheckId> order = new ArrayList<>(ComposeChecks.IDS);
        final int f6 = order.indexOf(CheckId.F6);
        assertTrue(f6 > order.indexOf(CheckId.F7), "F6 must run after F7: " + order);
        assertTrue(f6 > order.indexOf(CheckId.F8), "F6 must run after F8: " + order);
        assertTrue(f6 > order.indexOf(CheckId.F9), "F6 must run after F9: " + order);
    }
}
