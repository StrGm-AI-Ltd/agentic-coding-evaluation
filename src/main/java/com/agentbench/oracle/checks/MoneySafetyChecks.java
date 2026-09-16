package com.agentbench.oracle.checks;

import com.agentbench.oracle.CheckId;
import com.agentbench.oracle.CheckResult;
import com.agentbench.oracle.CheckStatus;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Port of oracle/checks/02_money_safety.py (M1-M4). Regex lint over source — a cheap early signal,
 *  NOT the correctness oracle (the black-box suite is). Hardened ports kept: multi-line declarations,
 *  Kotlin, SQL column types, test-source-set skipping only, comment stripping, Objects.equals/getter
 *  .equals forms, per-METHOD point-in-time definitions. */
public final class MoneySafetyChecks {
    private MoneySafetyChecks() {}

    static final String MONEY = "(?:price|amount|balance|cash|total|cost|fee|fees|qty|quantity|notional|premium|margin)";
    static final Pattern DECL = Pattern.compile("\\b(double|float|Double|Float)\\s*(\\[\\s*\\])?\\s+(\\w*" + MONEY + "\\w*)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern KT = Pattern.compile("\\b(\\w*" + MONEY + "\\w*)\\s*:\\s*(Double|Float)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern SQL_BAD = Pattern.compile("\\b(\\w*" + MONEY + "\\w*)\\s+(FLOAT8|FLOAT|REAL|DOUBLE\\s+PRECISION)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern BD_IMPORT = Pattern.compile("(?m)^\\s*import\\s+java\\.math\\.BigDecimal");
    static final Pattern EQUALS = Pattern.compile("(?:\\b\\w*" + MONEY + "\\w*\\s*\\.equals\\(|Objects\\.equals\\(\\s*\\w*" + MONEY + "|get" + MONEY + "\\w*\\(\\)\\s*\\.equals\\()", Pattern.CASE_INSENSITIVE);
    static final Pattern PIT_DEF = Pattern.compile("(?:\\b(?:public|private|protected|static|final|suspend|override|fun)\\b[^;{}=]*?\\s|^\\s*fun\\s+)(holdings?At|positions?At|balanceAt|asOf|pointInTime|snapshotAt|replay\\w*)\\s*\\(", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    static final Pattern PIT_PARAM = Pattern.compile("(?:\\b(?:public|private|protected|static|final|suspend|override|fun)\\b[^;{}=]*?\\s|^\\s*fun\\s+)(\\w+)\\s*\\([^)]*\\b(?:Instant|OffsetDateTime|ZonedDateTime|LocalDateTime)\\b[^)]*\\)", Pattern.MULTILINE);
    static final Pattern SUBTRACTS = Pattern.compile("(\\.subtract\\(|-=|\\.negate\\(\\)|\\* ?-1|\\bminus\\b|\\bsign(um)?\\b|-\\s*\\w*(qty|quantity|amount)\\b|" +
            "\\bSELL\\b[^;]{0,80}?(-|subtract|negate)|CASE\\s+WHEN[^;]*SELL[^;]*-)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    static final Pattern SQL_PIT = Pattern.compile("CASE\\s+WHEN[^;]*SELL[^;]*(-\\s*\\w*(qty|quantity)|\\* ?-1)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static List<CheckResult> run(Path ws) {
        List<Path> java = sources(ws, ".java", ".kt"), sql = sources(ws, ".sql");
        List<CheckResult> out = new ArrayList<>();
        if (java.isEmpty() && sql.isEmpty()) {
            for (CheckId c : List.of(CheckId.M1, CheckId.M2, CheckId.M3, CheckId.M4))
                out.add(CheckResult.notAttempted(c, "no Java/Kotlin/SQL sources - task not attempted"));
            return out;
        }

        // ---- M1: money never float/double, token-level over comment-stripped source (multi-line safe)
        List<String> bad = new ArrayList<>();
        for (Path p : java) {
            String s = stripComments(read(p));
            for (var m : DECL.matcher(s).results().toList()) bad.add(rel(p, ws) + ":" + m.group(3));
            for (var m : KT.matcher(s).results().toList()) bad.add(rel(p, ws) + ":" + m.group(1));
        }
        for (Path p : sql)
            for (var m : SQL_BAD.matcher(read(p)).results().toList()) bad.add(rel(p, ws) + ":" + m.group(1) + " " + m.group(2));
        out.add(new CheckResult(CheckId.M1, bad.isEmpty() ? CheckStatus.PASS : CheckStatus.FAIL,
                bad.isEmpty() ? java.size() + " src + " + sql.size() + " sql scanned" : String.join("; ", bad.subList(0, Math.min(6, bad.size())))));

        // ---- M2: BigDecimal actually IMPORTED where money is handled (not merely mentioned in a comment)
        List<Path> moneyFiles = new ArrayList<>(), withBd = new ArrayList<>();
        for (Path p : java) {
            String src = read(p);
            if (Pattern.compile("\\b" + MONEY + "\\b", Pattern.CASE_INSENSITIVE).matcher(stripComments(src)).find()) {
                moneyFiles.add(p);
                if (BD_IMPORT.matcher(src).find()) withBd.add(p);
            }
        }
        if (moneyFiles.isEmpty()) out.add(CheckResult.notAttempted(CheckId.M2, "no source handles money-named fields"));
        else out.add(new CheckResult(CheckId.M2, withBd.size() >= Math.max(1, moneyFiles.size() / 2) ? CheckStatus.PASS : CheckStatus.FAIL,
                withBd.size() + "/" + moneyFiles.size() + " money-handling files import BigDecimal"));

        // ---- M3: equals() on money (scale trap)
        List<String> eq = new ArrayList<>();
        if (withBd.isEmpty()) out.add(CheckResult.notAttempted(CheckId.M3, "no BigDecimal money code to inspect"));
        else {
            for (Path p : withBd) {
                Matcher m = EQUALS.matcher(stripComments(read(p)));
                int[] n = {0};
                while (m.find() && n[0] < 5) { eq.add(rel(p, ws) + ":" + m.group(0)); n[0]++; }
            }
            out.add(new CheckResult(CheckId.M3, eq.isEmpty() ? CheckStatus.PASS : CheckStatus.FAIL,
                    eq.isEmpty() ? withBd.size() + " files clean" : String.join("; ", eq.subList(0, Math.min(5, eq.size())))));
        }

        // ---- M4: point-in-time replay subtracts sells, per METHOD DEFINITION; NOT_ATTEMPTED if absent
        List<String> findings = new ArrayList<>();
        int[] pitMethods = {0};
        for (Path p : java) {
            String s = stripComments(read(p));
            Map<Integer, MatchResult> hits = new TreeMap<>();
            for (var m : PIT_DEF.matcher(s).results().toList()) hits.putIfAbsent(m.start(), m);
            if (Pattern.compile("holding|position|portfolio|ledger", Pattern.CASE_INSENSITIVE).matcher(p.getFileName().toString()).find())
                for (var m : PIT_PARAM.matcher(s).results().toList()) hits.putIfAbsent(m.start(), m);
            for (MatchResult m : hits.values()) {
                pitMethods[0]++;
                String body = methodBody(s, m.start());
                if (!SUBTRACTS.matcher(body).find()) findings.add(rel(p, ws) + ":" + m.group(1) + " (no subtraction in replay)");
            }
        }
        for (Path p : sql)
            if (SQL_PIT.matcher(read(p)).find()) pitMethods[0]++;
        for (Path p : java)
            if (Pattern.compile("@Query|createQuery|nativeQuery").matcher(read(p)).find() && SQL_PIT.matcher(read(p)).find()) pitMethods[0]++;
        if (pitMethods[0] == 0) out.add(CheckResult.notAttempted(CheckId.M4, "no point-in-time method found (holdingsAt/asOf/... definition)"));
        else out.add(new CheckResult(CheckId.M4, findings.isEmpty() ? CheckStatus.PASS : CheckStatus.FAIL,
                findings.isEmpty() ? pitMethods[0] + " pit method(s) subtract sells" : String.join("; ", findings.subList(0, Math.min(4, findings.size())))));
        return out;
    }

    static String methodBody(String s, int start) {
        String body = s.substring(start, Math.min(s.length(), start + 4000));
        int depth = 0; boolean opened = false;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '{') { depth++; opened = true; }
            else if (ch == '}' && --depth == 0 && opened) return body.substring(0, i);
        }
        return body;
    }

    static List<Path> sources(Path ws, String... exts) {
        List<Path> out = new ArrayList<>();
        for (Path p : StructureChecks.glob(ws, "**/*")) {
            String n = p.toString();
            if (!Arrays.stream(exts).anyMatch(n::endsWith)) continue;
            if (n.contains("/src/test/") || n.contains("/node_modules/") || n.contains("/build/") || n.contains("/.git/") || n.contains("/.gradle")) continue;
            out.add(p);
        }
        return out;
    }

    static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }
    static String read(Path p) { try { return Files.readString(p); } catch (Exception e) { return ""; } }
    static String rel(Path p, Path ws) { return ws.relativize(p).toString(); }
}
