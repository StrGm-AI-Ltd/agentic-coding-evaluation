package com.strgmai.ace.service.oracle;

/** Port of oracle/registry.py CHECKS: id -> (category, weight, description). Single source of truth:
 *  every run must produce a record for every id here; a missing record is a FAIL, never an absence. */
public enum CheckId {
    S1("structure", 1, "docs/TASK_DEFINITION.md exists"),
    S2("structure", 1, "docs/IMPLEMENTATION_PLAN.md exists"),
    S3("structure", 1, "docs/PROGRESS.md exists"),
    S4("structure", 1, "docker compose file present"),
    S5("structure", 1, ">=3 real Gradle services (settings+plugins)"),
    S6("structure", 1, "real Gradle wrapper committed"),
    S7("structure", 1, "OpenAPI spec present and parses"),
    S8("structure", 1, "React in frontend dependencies"),
    S9("structure", 1, "DB migrations present"),

    P1("planning", 2, "task definition has required sections"),
    P2("planning", 2, "plan subtasks have id/goal/deps/criterion"),
    P3("planning", 2, "no source code written during phase 0/1"),

    M1("lint", 1, "money never float/double (Java/Kotlin/SQL)"),
    M2("lint", 1, "BigDecimal imported where money is handled"),
    M3("lint", 1, "no equals() on money (scale trap)"),
    M4("lint", 1, "point-in-time replay subtracts sells"),

    B1("build", 3, "compiles in pinned JDK container"),
    B2("build", 3, ">0 tests executed and passing (XML-verified)"),
    B3("build", 3, "agent suite FAILS on a seeded mutation"),

    C1("runtime", 3, ">=3 non-db services report healthy"),
    C2("runtime", 2, "declared readiness endpoint returns 200"),

    F1("functional", 5, "buy then equal sell restores holdings"),
    F2("functional", 5, "point-in-time query between buy and sell"),
    F3("functional", 5, "insufficient balance rejected with 422"),
    F4("functional", 5, "illegal order transition rejected with 409"),
    F5("functional", 5, "decimal amounts round-trip exactly"),
    F6("functional", 4, "responses conform to the OpenAPI contract"),
    F7("bespoke", 5, "deposits round HALF_EVEN: 0.005->0.00, 0.015->0.02"),
    F8("bespoke", 5, "asOf boundary is EXCLUSIVE and asOfApplied echoed"),
    F9("bespoke", 5, "Idempotency-Key repeat -> same orderId, 200, no re-exec");

    public final String category;
    public final int weight;
    public final String description;
    CheckId(String category, int weight, String description) { this.category = category; this.weight = weight; this.description = description; }

    /** the ids that make up the PRIMARY (product) score — port of registry.FUNCTIONAL.
     *  One immutable instance: functional() is called per record in Scorer's stream filter, so the
     *  set is built once, and an unmodifiable view keeps the "source of truth" from being mutated. */
    private static final java.util.Set<CheckId> FUNCTIONAL =
            java.util.Collections.unmodifiableSet(java.util.EnumSet.of(F1, F2, F3, F4, F5, F6, F7, F8, F9));

    public static java.util.Set<CheckId> functional() {
        return FUNCTIONAL;
    }
    public static int weightOf(CheckId id) { return id.weight; }
    public static String categoryOf(CheckId id) { return id.category; }
}
