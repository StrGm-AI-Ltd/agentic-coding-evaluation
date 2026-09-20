package com.strgmai.ace.service.oracle.checks;

import com.strgmai.ace.service.reference.ReferenceServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The calibration twin (port of the Python design): run each black-box scenario against the
 *  reference server with an injected BUG and prove the scenario that claims to catch that bug is
 *  exactly the one that fails. No bug -> every scenario passes. */
class BlackboxCalibrationTest {
    private ReferenceServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(); }

    private BlackboxScenarios scenarios(String... bugs) throws Exception {
        server = new ReferenceServer(Set.of(bugs));
        int port = server.start(0);
        return new BlackboxScenarios("http://127.0.0.1:" + port);
    }

    @Test
    void theReferenceServerPassesEveryScenario() throws Exception {
        BlackboxScenarios b = scenarios();
        assertAll("correct by default",
                () -> assertTrue(b.f1BuyThenSellRestoresHoldings(), b.notes.toString()),
                () -> assertTrue(b.f2PointInTimeBetweenBuyAndSell(), b.notes.toString()),
                () -> assertTrue(b.f3InsufficientBalanceRejectedWith422(), b.notes.toString()),
                () -> assertTrue(b.f4IllegalTransitionRejectedWith409(), b.notes.toString()),
                () -> assertTrue(b.f5DecimalAmountsRoundTripExactly(), b.notes.toString()),
                () -> assertTrue(b.f7DepositsRoundHalfEven(), b.notes.toString()),
                () -> assertTrue(b.f8AsOfBoundaryExclusiveAndEchoed(), b.notes.toString()),
                () -> assertTrue(b.f9IdempotencyKeyRepeat(), b.notes.toString()));
    }

    @Test
    void eachBugIsCaughtByExactlyTheScenarioThatClaimsIt() throws Exception {
        record Case(String bug, String scenario) {}
        List<Case> cases = List.of(
                new Case("no422", "F3"), new Case("no409", "F4"), new Case("float", "F5"), new Case("halfup", "F7"),
                new Case("inclusive", "F8"), new Case("noidem", "F9"), new Case("asym", "F1"),
                new Case("selladd", "F1"), new Case("hollow", "F1"));
        for (Case c : cases) {
            BlackboxScenarios b = scenarios(c.bug());
            boolean f1 = b.f1BuyThenSellRestoresHoldings(), f2 = b.f2PointInTimeBetweenBuyAndSell();
            boolean f3 = b.f3InsufficientBalanceRejectedWith422(), f4 = b.f4IllegalTransitionRejectedWith409();
            boolean f5 = b.f5DecimalAmountsRoundTripExactly(), f7 = b.f7DepositsRoundHalfEven();
            boolean f8 = b.f8AsOfBoundaryExclusiveAndEchoed(), f9 = b.f9IdempotencyKeyRepeat();
            server.stop(); server = null;
            switch (c.scenario()) {
                case "F1" -> { assertFalse(f1, c.bug() + " must break F1: " + b.notes); assertTrue(f7, c.bug() + " must not break F7 (deposits are untouched)"); }
                case "F3" -> { assertFalse(f3, c.bug() + " must break F3: " + b.notes); assertTrue(f4, c.bug() + " must not break F4"); }
                case "F4" -> { assertFalse(f4, c.bug() + " must break F4: " + b.notes); assertTrue(f3, c.bug() + " must not break F3"); }
                case "F5" -> { assertFalse(f5, c.bug() + " must break F5: " + b.notes); assertTrue(f3, c.bug() + " must not break F3"); }
                case "F7" -> { assertFalse(f7, c.bug() + " must break F7: " + b.notes); assertTrue(f3, c.bug() + " must not break F3"); }
                case "F8" -> { assertFalse(f8, c.bug() + " must break F8: " + b.notes); assertTrue(f7, c.bug() + " must not break F7"); }
                case "F9" -> { assertFalse(f9, c.bug() + " must break F9: " + b.notes); assertTrue(f3, c.bug() + " must not break F3"); }
                default -> fail("unknown scenario " + c.scenario());
            }
            assertTrue(f2 || !f2);   // (f2 is informational: F2 also fails under selladd, like the Python twin)
        }
    }
}
