package com.trading.account;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {
    @Test void halfEvenRounding() {
        assertEquals("0.00", Money.str(new BigDecimal("0.005")));
        assertEquals("0.02", Money.str(new BigDecimal("0.015")));
        assertEquals("0.02", Money.str(new BigDecimal("0.025")));
        assertEquals("100.00", Money.str(new BigDecimal("100")));
    }
    @Test void exactDecimalArithmetic() {
        assertEquals("0.30", Money.str(new BigDecimal("0.10").add(new BigDecimal("0.20"))));
    }
    @Test void quantitiesAreCompact() {
        assertEquals("5", Money.qty(new BigDecimal("5.0000")));
        assertEquals("2.5", Money.qty(new BigDecimal("2.5000")));
    }
    @Test void isoMillisUtc() {
        assertEquals("2026-01-31T12:00:00.123Z", Money.iso(java.time.Instant.parse("2026-01-31T12:00:00.123456Z")));
    }
}
