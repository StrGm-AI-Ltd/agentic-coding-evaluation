package com.strgmai.ace.service.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #277: the "poolable" predicate - "this run's schema matches the current build and it actually
 *  has a full weighted score" - used to be two independently hand-written boolean expressions
 *  (ImporterService's own over a plain Map, StatsService's own over a JsonNode). Shared here;
 *  schemaVersion is `Object` on purpose, so both an Integer (from a Map) and an int (autoboxed,
 *  from a JsonNode's asInt()) work identically via Objects.equals(). */
class BenchPropertiesTest {

    @Test
    void poolableWhenSchemaMatchesAndAWeightedScoreIsPresent() {
        assertTrue(BenchProperties.poolable(BenchProperties.RESULT_SCHEMA, true));
        assertTrue(BenchProperties.poolable((Object) Integer.valueOf(BenchProperties.RESULT_SCHEMA), true));   // the int-autoboxed-from-JsonNode shape
    }

    @Test
    void notPoolableWhenTheSchemaVersionDoesNotMatch() {
        assertFalse(BenchProperties.poolable(BenchProperties.RESULT_SCHEMA - 1, true));
        assertFalse(BenchProperties.poolable(null, true));   // a run with no schema_version field at all
    }

    @Test
    void notPoolableWithoutAWeightedScoreEvenWhenTheSchemaMatches() {
        assertFalse(BenchProperties.poolable(BenchProperties.RESULT_SCHEMA, false));
    }
}
