package com.strgmai.ace.service.config;

/** The operational settings {@code application.yml} sets, for tests that build components without Spring. */
public final class TestOperations {
    private TestOperations() {
    }

    public static OperationsProperties defaults() {
        return new OperationsProperties(1800, 2000, 2400, 30, 60, 20_000, 1800, 300, 1800);
    }
}
