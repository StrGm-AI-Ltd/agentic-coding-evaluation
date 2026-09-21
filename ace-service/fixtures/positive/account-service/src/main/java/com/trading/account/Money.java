package com.trading.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Money and time conventions of the frozen contract: 2 dp HALF_EVEN, decimal STRINGS, UTC millis. */
public final class Money {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private Money() {}

    public static BigDecimal amount(String raw) {
        try { return new BigDecimal(raw.trim()); } catch (RuntimeException e) { throw new ApiException(400, "invalid decimal: " + raw); }
    }

    public static BigDecimal scale2(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_EVEN); }

    public static String str(BigDecimal v) { return scale2(v).toPlainString(); }

    public static String qty(BigDecimal v) { return v.stripTrailingZeros().scale() <= 0 ? v.toBigInteger().toString() : v.stripTrailingZeros().toPlainString(); }

    public static Instant nowMillis() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }

    public static String iso(Instant t) { return TS.format(t.truncatedTo(ChronoUnit.MILLIS)); }

    public static Instant parseInstant(String s) {
        try { return java.time.OffsetDateTime.parse(s).toInstant(); }
        catch (RuntimeException e) { throw new ApiException(400, "invalid asOf: " + s); }
    }
}
