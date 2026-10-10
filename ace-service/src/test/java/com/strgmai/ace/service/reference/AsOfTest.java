package com.strgmai.ace.service.reference;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** #280, found live: TradingController and ReferenceServer used to parse `asOf` differently -
 *  TradingController unconditionally appended "Z" whenever the string didn't already end in one,
 *  malforming a well-formed explicit-offset instant (e.g. "...+02:00" -> "...+02:00Z", rejected);
 *  ReferenceServer called Instant.parse directly, with no coercion. Both now share this. */
class AsOfTest {

    @Test
    void anExplicitNonZOffsetIsPassedThroughUnchangedNotMalformedWithATrailingZ() {
        assertEquals(Instant.parse("2024-01-15T08:30:00Z"), AsOf.parse("2024-01-15T10:30:00+02:00"));
    }

    @Test
    void aBareZSuffixedInstantParsesNormally() {
        assertEquals(Instant.parse("2024-01-15T10:30:00Z"), AsOf.parse("2024-01-15T10:30:00Z"));
    }

    @Test
    void aSpaceSeparatedFormWithNoTimezoneIsCoercedToUtc() {
        assertEquals(Instant.parse("2024-01-15T10:30:00Z"), AsOf.parse("2024-01-15 10:30:00"));
    }

    @Test
    void aSpaceSeparatedFormWithAnExplicitOffsetKeepsThatOffsetNotUtc() {
        assertEquals(Instant.parse("2024-01-15T08:30:00Z"), AsOf.parse("2024-01-15 10:30:00+02:00"));
    }

    @Test
    void genuinelyMalformedInputThrowsDateTimeParseException() {
        assertThrows(DateTimeParseException.class, () -> AsOf.parse("not a timestamp"));
    }
}
