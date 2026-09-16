package com.agentbench.reference;

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
        String a = account();
        svc.deposit(a, "10.00");
        for (int i = 0; i < 3; i++)
            assertEquals(201, svc.order(a, "AAPL", "BUY", "1", "0.10", null).status());
        assertEquals("9.70", svc.account(a).get("availableBalance"));
    }

    @Test
    void insufficientBalanceIs422() {
        String a = account();
        svc.deposit(a, "5.00");
        assertEquals(422, svc.order(a, "AAPL", "BUY", "10", "10.00", null).status());
    }

    @Test
    void illegalTransitionIs409() {
        String a = account();
        svc.deposit(a, "1000.00");
        TradingService.OrderOutcome o = svc.order(a, "AAPL", "BUY", "1", "10.00", null);
        assertEquals(201, o.status());
        assertEquals(409, svc.cancel((String) o.body().get("orderId")).status());
    }

    @Test
    void theAsOfBoundaryIsExclusiveAndEchoed() throws InterruptedException {
        String a = account();
        svc.deposit(a, "1000.00");
        svc.order(a, "TSLA", "BUY", "2", "250.00", null);
        Thread.sleep(5);                                   // strictly after the buy's millisecond: the boundary is exclusive
        String between = TradingService.now().toString();
        Thread.sleep(5);
        svc.order(a, "TSLA", "SELL", "2", "250.00", null);
        var h = svc.holdings(a, java.time.Instant.parse(between));
        assertEquals("2", ((java.util.Map<?, ?>) h.get("holdings")).get("TSLA"));
        assertNotNull(h.get("asOfApplied"));                                        // always echoed
    }

    @Test
    void idempotencyKeyRepeatReturnsTheSameOrderWithoutReExecuting() {
        String a = account();
        svc.deposit(a, "1000.00");
        TradingService.OrderOutcome first = svc.order(a, "AAPL", "BUY", "1", "10.00", "idem-1");
        TradingService.OrderOutcome repeat = svc.order(a, "AAPL", "BUY", "1", "10.00", "idem-1");
        assertEquals(200, repeat.status());
        assertEquals(first.body().get("orderId"), repeat.body().get("orderId"));
        assertEquals("1", ((java.util.Map<?, ?>) svc.holdings(a, null).get("holdings")).get("AAPL"));   // executed once
    }

    @Test
    void buyThenEqualSellRestoresHoldings() {
        String a = account();
        svc.deposit(a, "1000.00");
        assertEquals(201, svc.order(a, "AAPL", "BUY", "10", "10.00", null).status());
        assertEquals(201, svc.order(a, "AAPL", "SELL", "10", "10.00", null).status());
        assertTrue(((java.util.Map<?, ?>) svc.holdings(a, null).get("holdings")).isEmpty());
    }

    // ---- the BUGS calibration modes: each must break exactly the rule it claims to
    @Test
    void theHalfupBugBreaksHalfEvenRounding() {
        TradingService buggy = new TradingService(Set.of("halfup"));
        String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "0.005");
        assertEquals("0.01", buggy.account(a).get("availableBalance"));   // HALF_UP: the F7 catch
    }

    @Test
    void theNo409BugAllowsCancellingAFilledOrder() {
        TradingService buggy = new TradingService(Set.of("no409"));
        String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "1000.00");
        String oid = (String) buggy.order(a, "AAPL", "BUY", "1", "10.00", null).body().get("orderId");
        assertEquals(200, buggy.cancel(oid).status());                    // the F4 catch
    }

    @Test
    void theNo422BugAcceptsInsufficientFunds() {
        TradingService buggy = new TradingService(Set.of("no422"));
        String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "5.00");
        assertEquals(201, buggy.order(a, "AAPL", "BUY", "10", "10.00", null).status());   // the F3 catch
    }

    @Test
    void theAsymBugUndercreditsTheSell() {
        TradingService buggy = new TradingService(Set.of("asym"));
        String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "100.00");
        buggy.order(a, "AAPL", "BUY", "1", "10.00", null);
        buggy.order(a, "AAPL", "SELL", "1", "10.00", null);
        assertEquals(0, new BigDecimal("99.90").compareTo(new BigDecimal((String) buggy.account(a).get("availableBalance"))));   // 100 - 10 + 0.99x10: the F1 catch
    }

    @Test
    void theNoidemBugExecutesTwice() {
        TradingService buggy = new TradingService(Set.of("noidem"));
        String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "1000.00");
        buggy.order(a, "AAPL", "BUY", "1", "10.00", "idem-1");
        buggy.order(a, "AAPL", "BUY", "1", "10.00", "idem-1");
        assertEquals("2", ((java.util.Map<?, ?>) buggy.holdings(a, null).get("holdings")).get("AAPL"));   // the F9 catch
    }

    @Test
    void theInclusiveBugSeesEventsAtTheBoundary() {
        TradingService buggy = new TradingService(Set.of("inclusive"));
        String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "1000.00");
        String executedAt = (String) buggy.order(a, "TSLA", "BUY", "2", "250.00", null).body().get("executedAt");
        var h = buggy.holdings(a, java.time.Instant.parse(executedAt));
        assertEquals("2", ((java.util.Map<?, ?>) h.get("holdings")).get("TSLA"));   // AT the boundary: kept only under the bug
    }

    @Test
    void theSelladdBugAddsSellsToHoldings() {
        TradingService buggy = new TradingService(Set.of("selladd"));
        String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "1000.00");
        buggy.order(a, "AAPL", "BUY", "10", "10.00", null);
        buggy.order(a, "AAPL", "SELL", "10", "10.00", null);
        assertEquals("20", ((java.util.Map<?, ?>) buggy.holdings(a, null).get("holdings")).get("AAPL"));   // the F1/F2 catch
    }

    @Test
    void theFloatBugBreaksTheDecimalRoundTripRepresentation() {
        TradingService buggy = new TradingService(Set.of("float"));
        String a = (String) buggy.createAccount("USD").get("accountId");
        buggy.deposit(a, "10.00");
        buggy.order(a, "AAPL", "BUY", "1", "0.10", null);
        assertEquals("9.9", buggy.account(a).get("availableBalance"));   // a float repr, not "9.90": the F5 catch
    }
}
