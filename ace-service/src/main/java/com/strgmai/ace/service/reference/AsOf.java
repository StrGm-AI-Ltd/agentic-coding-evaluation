package com.strgmai.ace.service.reference;

import java.time.Instant;
import java.util.regex.Pattern;

/** #280, found live: TradingController and ReferenceServer both implement the SAME frozen contract
 *  for the black-box suite's calibration, but used to parse `asOf` differently - TradingController
 *  coerced the input (space->'T', and unconditionally appended a literal "Z" whenever the string
 *  did not already end in one) before calling Instant.parse; ReferenceServer called Instant.parse
 *  directly on the raw string. A well-formed, explicit-offset instant that does NOT end in "Z"
 *  (e.g. "2024-01-15T10:30:00+02:00") would be malformed by TradingController's own coercion into
 *  "...+02:00Z" and rejected, while ReferenceServer parsed the identical input successfully - the
 *  two "same contract" servers did not, in fact, agree on what a valid asOf looks like. Shared
 *  here so both classes parse identically; each keeps its own surrounding exception handling
 *  (this throws the same DateTimeParseException Instant.parse itself would, nothing new). */
final class AsOf {
    private AsOf() {}

    private static final Pattern HAS_TIMEZONE = Pattern.compile("(Z|[+-]\\d{2}:?\\d{2})$");

    static Instant parse(final String raw) {
        final String s = raw.replace(' ', 'T');
        return Instant.parse(HAS_TIMEZONE.matcher(s).find() ? s : s + "Z");
    }
}
