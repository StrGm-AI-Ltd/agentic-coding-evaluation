package com.strgmai.ace.service.oracle;

public record CheckResult(CheckId id, CheckStatus status, String detail) {
    public static CheckResult pass(CheckId id, String detail) { return new CheckResult(id, CheckStatus.PASS, detail); }
    public static CheckResult fail(CheckId id, String detail) { return new CheckResult(id, CheckStatus.FAIL, detail); }
    public static CheckResult skipped(CheckId id, String detail) { return new CheckResult(id, CheckStatus.SKIPPED, detail); }
    public static CheckResult notAttempted(CheckId id, String detail) { return new CheckResult(id, CheckStatus.NOT_ATTEMPTED, detail); }
}
