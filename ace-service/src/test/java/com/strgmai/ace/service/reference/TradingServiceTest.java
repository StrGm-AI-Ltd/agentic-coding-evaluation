package com.strgmai.ace.service.reference;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The reference trading server's contract behaviour, BigDecimal-first (explicit scale 2,
 *  RoundingMode.HALF_EVEN, compareTo comparisons — never equals). */
class TradingServiceTest {
    private final TradingService svc = new TradingService();

    private String account() { return (String) svc.createAccount("USD").get("accountId"); }

    @Test
    void depositsRoundHalfEvenTheBespokeF7Rule() {
        String a = account(), b = account();
        svc.deposit(a, "0.005");
        svc.deposit(b, "0.015");
        assertEquals("0.00", svc.account(a).get("availableBalance"));   // HALF_EVEN: 0.005 -> 0.00
        assertEquals("0.02", svc.account(b).get("availableBalance"));   // HALF_EVEN: 0.015 -> 0.02
    }

    @Test
    void decimalAmountsRoundTripExactly() {
        final String a = account();
        svc.deposit(a, "10.00");
        for (int i = 0; i < 3; i++)
            assertEquals(201, svc.order(a, "AAPL", "BUY", "1", "0.10", null).status());
        assertEquals("9.70", svc.account(a).get("availableBalance"));
    }

    @Test
    void insufficientBalanceIs422() {
        final String a = account();
        svc.deposit(a, "5.00");
        assertEquals(422, svc.order(a, "AAPL", "BUY", "10", "10.00", null).status());
    }

    @Test
    void illegalTransitionIs409() {
        final String a = account();
        svc.deposit(a, "1000.00");
        final TradingService.OrderOutcome o = svc.order(a, "AAPL", "BUY", "1", "10.00", null);
        assertEquals(201, o.status());
        assertEquals(409, svc.cancel((String) o.body().get("orderId")).status());
    }

    @Test
    void theAsOfBoundaryIsExclusiveAndEchoed() throws InterruptedException {
        final String a = account();
        svc.deposit(a, "1000.00");
        final TradingService.OrderOutcome buy = svc.order(a, "TSLA", "BUY", "2", "250.00", null);
        final java.time.Instant buyAt = java.time.Instant.parse((String) buy.body().get("executedAt"));
        // read the buy's real executedAt and spin until strictly after it: the exclusive boundary
        // (and the assertion below) is only meaningful then; a bare sleep(5) could land on the
        // same millisecond on a GC-starved CI runner
        String between;
        do { Thread.sleep(5); between = TradingService.now().toString(); }
        while (!java.time.Instant.parse(between).isAfter(buyAt));
        svc.order(a, "TSLA", "SELL", "2", "250.00", null);
        final var h = svc.holdings(a, java.time.Instant.parse(between));
        assertEquals("2", ((java.util.Map<?, ?>) h.get("holdings")).get("TSLA"));
        assertNotNull(h.get("asOfApplied"));                                        // always echoed
    }

    @Test
    void idempotencyKeyRepeatReturnsTheSameOrderWithoutReExecuting() {
        final String a = account();
        svc.deposit(a, "1000.00");
        final TradingService.OrderOutcome first = svc.order(a, "AAPL", "BUY", "1", "10.00", "idem-1");
        final TradingService.OrderOutcome repeat = svc.order(a, "AAPL", "BUY", "1", "10.00", "idem-1");
        assertEquals(200, repeat.status());
        assertEquals(first.body().get("orderId"), repeat.body().get("orderId"));
        assertEquals("1", ((java.util.Map<?, ?>) svc.holdings(a, null).get("holdings")).get("AAPL"));   // executed once
    }

    @Test
    void buyThenEqualSellRestoresHoldings() {
        final String a = account();
        svc.deposit(a, "1000.00");
        assertEquals(201, svc.order(a, "AAPL", "BUY", "10", "10.00", null).status());
        assertEquals(201, svc.order(a, "AAPL", "SELL", "10", "10.00", null).status());
        assertTrue(((java.util.Map<?, ?>) svc.holdings(a, null).get("holdings")).isEmpty());
    }

    // ---- the BUGS calibration modes: each must break exactly the rule it claims to
    @Test
    void theHalfupBugBreaksHalfEvenRounding() {
        final var buggy = new TradingService(Set.of("halfup"));
        final String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "0.005");
        assertEquals("0.01", buggy.account(a).get("availableBalance"));   // HALF_UP: the F7 catch
    }

    @Test
    void theNo409BugAllowsCancellingAFilledOrder() {
        final var buggy = new TradingService(Set.of("no409"));
        final String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "1000.00");
        final String oid = (String) buggy.order(a, "AAPL", "BUY", "1", "10.00", null).body().get("orderId");
        assertEquals(200, buggy.cancel(oid).status());                    // the F4 catch
    }

    @Test
    void theNo422BugAcceptsInsufficientFunds() {
        final var buggy = new TradingService(Set.of("no422"));
        final String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "5.00");
        assertEquals(201, buggy.order(a, "AAPL", "BUY", "10", "10.00", null).status());   // the F3 catch
    }

    @Test
    void theAsymBugUndercreditsTheSell() {
        final var buggy = new TradingService(Set.of("asym"));
        final String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "100.00");
        buggy.order(a, "AAPL", "BUY", "1", "10.00", null);
        buggy.order(a, "AAPL", "SELL", "1", "10.00", null);
        assertEquals(0, new BigDecimal("99.90").compareTo(new BigDecimal((String) buggy.account(a).get("availableBalance"))));   // 100 - 10 + 0.99x10: the F1 catch
    }

    @Test
    void theNoidemBugExecutesTwice() {
        final var buggy = new TradingService(Set.of("noidem"));
        final String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "1000.00");
        buggy.order(a, "AAPL", "BUY", "1", "10.00", "idem-1");
        buggy.order(a, "AAPL", "BUY", "1", "10.00", "idem-1");
        assertEquals("2", ((java.util.Map<?, ?>) buggy.holdings(a, null).get("holdings")).get("AAPL"));   // the F9 catch
    }

    @Test
    void theInclusiveBugSeesEventsAtTheBoundary() {
        final var buggy = new TradingService(Set.of("inclusive"));
        final String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "1000.00");
        final String executedAt = (String) buggy.order(a, "TSLA", "BUY", "2", "250.00", null).body().get("executedAt");
        final var h = buggy.holdings(a, java.time.Instant.parse(executedAt));
        assertEquals("2", ((java.util.Map<?, ?>) h.get("holdings")).get("TSLA"));   // AT the boundary: kept only under the bug
    }

    @Test
    void theSelladdBugAddsSellsToHoldings() {
        final var buggy = new TradingService(Set.of("selladd"));
        final String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "1000.00");
        buggy.order(a, "AAPL", "BUY", "10", "10.00", null);
        buggy.order(a, "AAPL", "SELL", "10", "10.00", null);
        assertEquals("20", ((java.util.Map<?, ?>) buggy.holdings(a, null).get("holdings")).get("AAPL"));   // the F1/F2 catch
    }

    @Test
    void theFloatBugBreaksTheDecimalRoundTripRepresentation() {
        final var buggy = new TradingService(Set.of("float"));
        final String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "10.00");
        buggy.order(a, "AAPL", "BUY", "1", "0.10", null);
        assertEquals("9.9", buggy.account(a).get("availableBalance"));   // a float repr, not "9.90": the F5 catch
    }
}
