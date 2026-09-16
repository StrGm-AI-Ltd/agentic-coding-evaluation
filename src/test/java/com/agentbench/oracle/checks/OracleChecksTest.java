package com.agentbench.oracle.checks;

import com.agentbench.oracle.CheckId;
import com.agentbench.oracle.CheckResult;
import com.agentbench.oracle.CheckStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Ports of the check suites against fixture workspaces: money as double is caught (M1), the
 *  BigDecimal import is required (M2), equals on money is caught (M3), a replay that forgets to
 *  subtract sells is caught (M4), and the structure checks see real services. */
class OracleChecksTest {

    private static Map<CheckId, CheckResult> runStructure(Path ws) {
        return StructureChecks.run(ws).stream().collect(Collectors.toMap(CheckResult::id, r -> r));
    }

    private static Map<CheckId, CheckResult> runMoney(Path ws) {
        return MoneySafetyChecks.run(ws).stream().collect(Collectors.toMap(CheckResult::id, r -> r));
    }

    @Test
    void moneyAsDoubleFailsM1AndM2PassesWithBigDecimal() throws Exception {
        Path ws = Files.createTempDirectory("ws");
        Files.createDirectories(ws.resolve("src/main/java/app"));
        Files.writeString(ws.resolve("src/main/java/app/Bad.java"), """
                package app;
                public class Bad {
                    private double price;            // M1: money as double
                    public double getTotal() { return price * 2; }
                }
                """);
        Files.writeString(ws.resolve("src/main/java/app/Good.java"), """
                package app;
                import java.math.BigDecimal;
                public class Good {
                    private final BigDecimal amount = BigDecimal.ZERO;   // the compliant twin
                    public BigDecimal getBalance() { return amount; }
                }
                """);
        Map<CheckId, CheckResult> r = runMoney(ws);
        assertEquals(CheckStatus.FAIL, r.get(CheckId.M1).status());
        assertTrue(r.get(CheckId.M1).detail().contains("Bad.java:price"));
        assertEquals(CheckStatus.PASS, r.get(CheckId.M2).status());

        Path clean = Files.createTempDirectory("ws2");
        Files.createDirectories(clean.resolve("src/main/java/app"));
        Files.writeString(clean.resolve("src/main/java/app/Good.java"), """
                package app;
                import java.math.BigDecimal;
                public class Good { private final BigDecimal balance = BigDecimal.ZERO; }
                """);
        assertEquals(CheckStatus.PASS, runMoney(clean).get(CheckId.M1).status());
    }

    @Test
    void equalsOnMoneyFailsM3() throws Exception {
        Path ws = Files.createTempDirectory("ws");
        Files.createDirectories(ws.resolve("src/main/java/app"));
        Files.writeString(ws.resolve("src/main/java/app/Trap.java"), """
                package app;
                import java.math.BigDecimal;
                import java.util.Objects;
                public class Trap {
                    boolean same(BigDecimal a, BigDecimal other) { return a.price() /*not real*/ }
                    BigDecimal price() { return null; }
                    boolean eq(BigDecimal x) { return Objects.equals(price(), x); }   // the scale trap
                }
                """);
        assertEquals(CheckStatus.FAIL, runMoney(ws).get(CheckId.M3).status());
    }

    @Test
    void aReplayWithoutSubtractionFailsM4AndASubtractingOnePasses() throws Exception {
        Path ws = Files.createTempDirectory("ws");
        Files.createDirectories(ws.resolve("src/main/java/app"));
        Files.writeString(ws.resolve("src/main/java/app/Ledger.java"), """
                package app;
                import java.math.BigDecimal;
                import java.time.Instant;
                public class Ledger {
                    public BigDecimal holdingsAt(Instant asOf) {          // a replay that forgets the sells
                        return BigDecimal.TEN.add(BigDecimal.ONE);
                    }
                }
                """);
        assertEquals(CheckStatus.FAIL, runMoney(ws).get(CheckId.M4).status());

        Path ok = Files.createTempDirectory("ws2");
        Files.createDirectories(ok.resolve("src/main/java/app"));
        Files.writeString(ok.resolve("src/main/java/app/Ledger.java"), """
                package app;
                import java.math.BigDecimal;
                import java.time.Instant;
                public class Ledger {
                    public BigDecimal holdingsAt(Instant asOf) {
                        BigDecimal h = BigDecimal.ZERO;
                        if (side.equals("BUY")) h = h.add(qty); else h = h.subtract(qty);   // sells subtract
                        return h;
                    }
                }
                """);
        assertEquals(CheckStatus.PASS, runMoney(ok).get(CheckId.M4).status());
    }

    @Test
    void noSourcesMeansNotAttemptedNotFail() throws Exception {
        Map<CheckId, CheckResult> r = runMoney(Files.createTempDirectory("empty"));
        for (CheckId c : List.of(CheckId.M1, CheckId.M2, CheckId.M3, CheckId.M4))
            assertEquals(CheckStatus.NOT_ATTEMPTED, r.get(c).status());   // the feature simply is not there
    }

    @Test
    void structureChecksScoreARealWorkspace() throws Exception {
        Path ws = Files.createTempDirectory("ws");
        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("docs/TASK_DEFINITION.md"), "x".repeat(300));
        Files.writeString(ws.resolve("docs/IMPLEMENTATION_PLAN.md"), "x".repeat(300));
        Files.writeString(ws.resolve("docs/PROGRESS.md"), "x".repeat(300));
        Files.writeString(ws.resolve("compose.yaml"), "services: {}\n");
        Files.writeString(ws.resolve("settings.gradle"), "rootProject.name = 'bench'\n");   // makes the ROOT a Gradle root: the wrapper lives here
        Files.createDirectories(ws.resolve("gradle/wrapper"));
        Files.writeString(ws.resolve("gradlew"), "#!/bin/sh\n" + "x".repeat(2000));
        Files.writeString(ws.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=x\n");
        for (String svc : List.of("orders", "accounts", "quotes")) {
            Path d = ws.resolve(svc);
            Files.createDirectories(d.resolve("src/main/java"));
            Files.writeString(d.resolve("build.gradle"), "plugins { id 'org.springframework.boot' }\n");
        }
        Files.writeString(ws.resolve("openapi.yaml"), "openapi: 3.0.1\npaths:\n  /orders:\n");
        Files.createDirectories(ws.resolve("db/migration"));
        Files.writeString(ws.resolve("db/migration/V1__init.sql"), "CREATE TABLE t(id int);\n");

        Map<CheckId, CheckResult> r = runStructure(ws);
        for (CheckId c : List.of(CheckId.S1, CheckId.S2, CheckId.S3, CheckId.S4, CheckId.S5, CheckId.S6, CheckId.S7, CheckId.S9))
            assertEquals(CheckStatus.PASS, r.get(c).status(), c + ": " + r.get(c).detail());
        assertEquals(CheckStatus.FAIL, r.get(CheckId.S8).status());   // no package.json with react
    }

    @Test
    void aOneLineBuildGradleStubIsNotAService() throws Exception {
        Path ws = Files.createTempDirectory("ws");
        for (String svc : List.of("a", "b", "c")) {
            Path d = ws.resolve(svc);
            Files.createDirectories(d);   // build file but NO src/main: not a real service
            Files.writeString(d.resolve("build.gradle"), "plugins { id 'org.springframework.boot' }\n");
        }
        assertEquals(CheckStatus.FAIL, runStructure(ws).get(CheckId.S5).status());
    }
}
