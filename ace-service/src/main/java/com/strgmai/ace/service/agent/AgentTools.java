package com.strgmai.ace.service.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of agent_loop.py's tools: read/write/edit/bash with EXACT-match edits, ranged reads and
 *  mechanical output hygiene (ANSI stripped, head+tail caps) so context is spent on what the model
 *  needs, not on what a command happened to print. */
public final class AgentTools {
    public static final int READ_MAX_LINES = 250, READ_MAX_CHARS = 12000, BASH_MAX_CHARS = 8000, BASH_HEAD = 60, BASH_TAIL = 60;
    private static final Pattern ANSI = Pattern.compile("\\x1b\\[[0-9;?]*[A-Za-z]");

    /** #423: the plain head+tail cap below used to drop the omitted MIDDLE unconditionally - exactly
     *  where a failing assertion or stack trace in a long test/build log usually sits. Matches a
     *  failure/exception marker, an assertion-diff line (JUnit's own "expected: <...> but was: <...>"),
     *  or a stack-trace frame - deterministic, no model, no extra latency, same "mechanical output
     *  hygiene" spirit as the head/tail cap itself.
     *
     *  #417 follow-up review: measured against this harness's own 907 real session journals
     *  (139k lines of real `bash` output), the original pattern was both too narrow and too broad.
     *  Too narrow: `\bException\b`/`\bError\b` can never match INSIDE a concrete class name
     *  (`\b` requires a word boundary, and there is none between "Null" and "Exception" in
     *  "NullPointerException") - every concrete exception class, every "Caused by:" line, and
     *  every module-prefixed stack frame (Java 9+'s "at app//com.example.Foo.bar(...)" /
     *  "at java.base/java.lang.Thread.run(...)" - "/" was never in the frame alternative's own
     *  character class) went unmatched. Too broad: the bare, case-insensitive words "error"/
     *  "failed" matched 75% of ALL hits in this harness's own logs - mostly a package segment
     *  (`io.trading.platform.common.error`), a JSON field name (`"error"`), or a log message
     *  about "N error(s)", none of them an actual failure. Case-SENSITIVE now: FAILED/ERROR/
     *  FAILURE: match only their typical ALL-CAPS log-marker form (Gradle's own convention), and
     *  a concrete exception/error class must start with a capital letter - "NullPointerException"
     *  matches, "the error below" does not. Scoped to this harness's own actual log shapes
     *  (Gradle/JUnit/Kotlin); not chasing every ecosystem's own conventions. */
    private static final Pattern ERROR_LINE = Pattern.compile(
            "\\bFAILED\\b|\\bERROR\\b|\\bFAILURE:|Caused by:|[A-Z][\\w$]*(?:Exception|Error)\\b|expected:.*but was:|^\\s*at [\\w./$]+\\(");
    private static final int MAX_ERROR_LINES_KEPT = 20;

    public record Outcome(String output, boolean isError) {}

    public static Path resolve(final String cwd, final String path) {
        final Path p = Paths.get(path == null ? "" : path);
        return (p.isAbsolute() ? p : Paths.get(cwd).resolve(p)).normalize();
    }

    /** confines read/write/edit to the task workspace (#91): resolve() honors an absolute path or a
     *  ".."-climbing relative path verbatim, which otherwise lets the model touch any file the JVM can
     *  reach (e.g. write(path="/Users/.../.ssh/authorized_keys")). This closes the direct path-string
     *  vector; it does not (and cannot, on its own) stop a symlink the model created inside the
     *  workspace via the still-unconfined `bash` tool from pointing back out - full sandboxing of the
     *  agent's shell access is a separate, larger decision tracked in #91. */
    static boolean withinWorkspace(final String cwd, final Path resolved) {
        return resolved.normalize().startsWith(Paths.get(cwd).normalize());
    }

    public static Outcome read(final String cwd, final Map<String, Object> a) {
        final Path p = resolve(cwd, String.valueOf(a.getOrDefault("path", "")));
        if (!withinWorkspace(cwd, p)) return new Outcome("error: path escapes the task workspace: " + a.get("path"), true);
        if (!Files.isRegularFile(p)) return new Outcome("error: no such file: " + a.get("path"), true);
        List<String> lines;
        try { lines = Files.readAllLines(p, StandardCharsets.UTF_8); }
        catch (IOException e) { return new Outcome("error: " + e, true); }
        // clamp the lower bound: a 0/negative start_line would index lines.get(-1) and throw
        final int s = Math.max(1, a.get("start_line") instanceof Number n ? n.intValue() : 1);
        if (!lines.isEmpty() && s > lines.size())
            return new Outcome("error: start_line " + s + " is past the end of the file (" + lines.size() + " lines)", true);
        int e = a.get("end_line") instanceof Number n ? Math.min(n.intValue(), lines.size()) : lines.size();
        if (e - s + 1 > READ_MAX_LINES && !a.containsKey("end_line")) e = s + READ_MAX_LINES - 1;
        final var out = new StringBuilder();
        for (int i = s; i <= e && i <= lines.size(); i++) out.append(String.format("%5d: %s%n", i, lines.get(i - 1)));
        String text = out.toString();
        if (text.length() > READ_MAX_CHARS) text = text.substring(0, READ_MAX_CHARS) + "\n… [truncated at " + READ_MAX_CHARS + " chars; read a narrower range]";
        if (e < lines.size()) text += "\n… [" + (lines.size() - e) + " more lines; the file has " + lines.size() + "]";
        return new Outcome(text.isEmpty() ? "(empty file)" : text, false);
    }

    public static Outcome write(final String cwd, final Map<String, Object> a) {
        final String content = a.get("content") == null ? null : String.valueOf(a.get("content"));
        if (content == null) return new Outcome("error: content is required", true);
        final Path p = resolve(cwd, String.valueOf(a.getOrDefault("path", "")));
        if (!withinWorkspace(cwd, p)) return new Outcome("error: path escapes the task workspace: " + a.get("path"), true);
        try {
            Files.createDirectories(p.getParent() == null ? Paths.get(".") : p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return new Outcome("wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + a.get("path"), false);
        } catch (IOException e) { return new Outcome("error: " + e, true); }
    }

    /** exact-match replace: the match must be unique (or replace_all); include context to make it unique */
    public static Outcome edit(final String cwd, final Map<String, Object> a) {
        final Path p = resolve(cwd, String.valueOf(a.getOrDefault("path", "")));
        if (!withinWorkspace(cwd, p)) return new Outcome("error: path escapes the task workspace: " + a.get("path"), true);
        if (!Files.isRegularFile(p)) return new Outcome("error: no such file: " + a.get("path"), true);
        String src;
        try { src = Files.readString(p, StandardCharsets.UTF_8); } catch (IOException e) { return new Outcome("error: " + e, true); }
        // explicit null check: getOrDefault returns null when the key is present with a JSON null,
        // and String.valueOf(null) would search for the literal text "null"
        final String old = a.get("old_string") == null ? "" : String.valueOf(a.get("old_string"));
        final String neu = a.get("new_string") == null ? "" : String.valueOf(a.get("new_string"));
        if (old.isEmpty()) return new Outcome("error: old_string is empty", true);
        final int n = countOccurrences(src, old);
        final boolean all = Boolean.TRUE.equals(a.get("replace_all")) || "true".equals(String.valueOf(a.get("replace_all")));
        if (n == 0) return new Outcome("error: old_string not found (the match must be exact, including whitespace)", true);
        if (n > 1 && !all) return new Outcome("error: old_string occurs " + n + " times; add context to make it unique or set replace_all", true);
        final String out = all ? src.replace(old, neu) : src.replaceFirst(Pattern.quote(old), Matcher.quoteReplacement(neu));
        try { Files.writeString(p, out, StandardCharsets.UTF_8); } catch (IOException e) { return new Outcome("error: " + e, true); }
        return new Outcome("edited " + a.get("path") + " (" + (all ? n : 1) + " replacement)", false);
    }

    static int countOccurrences(final String s, final String sub) {
        if (sub.isEmpty()) return 0;   // indexOf("", i) is always >= 0, which would loop forever
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    /** #423's own error-line extraction defaults to enabled - today's own existing behavior
     *  before it had an off switch. Kept as the 3-arg form so every existing caller (this class's
     *  own tests) keeps compiling unchanged. */
    public static Outcome bash(final String cwd, final Map<String, Object> a, final Map<String, String> env) {
        return bash(cwd, a, env, true);
    }

    /** `errorLineExtractionEnabled=false` falls back to the plain head/tail reconstruction alone
     *  (no scan of the omitted middle for FAILED/Exception/assertion-diff/stack-frame lines) -
     *  #417's original, pre-#423 behavior. */
    public static Outcome bash(final String cwd, final Map<String, Object> a, final Map<String, String> env, final boolean errorLineExtractionEnabled) {
        final String cmd = String.valueOf(a.getOrDefault("command", ""));
        int to = 600;
        if (a.get("timeout_sec") instanceof Number n) to = Math.min(1800, Math.max(5, n.intValue()));
        if (cmd.isBlank()) return new Outcome("error: empty command", true);
        String out; int rc;
        try {
            Map<String, String> full = new LinkedHashMap<>(env);   // drain concurrently: >64KB output would deadlock a read-after-wait
            full.put("CI", "1"); full.put("NO_COLOR", "1"); full.put("TERM", "dumb");
            // #243: zsh -lc is a LOGIN shell: it sources /etc/zprofile, which on macOS
            // unconditionally runs path_helper and rebuilds $PATH with system dirs first -
            // silently demoting whatever this scrubbed env's own PATH put first (the docker
            // shim; see RunBenchSupport.scrubbedEnv) to the very end. Found live 2026-10-04: the
            // shim's own invocation log was never written across an entire run - every `docker`
            // call the agent made resolved straight past it to the real CLI, bypassing
            // Docker-on-demand entirely. Re-exporting PATH as the command's own first statement
            // undoes that rewrite without losing whatever else -l provides. A blank/absent PATH
            // (e.g. a bare unit-test env) is left alone, so path_helper's own system defaults
            // still apply.
            final String path = full.get("PATH");
            final String guarded = path == null || path.isBlank() ? cmd : "export PATH=" + shellQuote(path) + "; " + cmd;
            final var r = com.strgmai.ace.service.docker.DockerService.proc(to, java.nio.file.Path.of(cwd), full, "zsh", "-lc", guarded);
            rc = r.rc();
            out = r.out() + (r.err().isBlank() ? "" : "\n[stderr]\n" + r.err());
            out = ANSI.matcher(out).replaceAll("");
            out = tidyWhitespace(out);
            if (rc == 124 && out.contains("[timeout")) return new Outcome("[timeout after " + to + "s]\n[exit 124]", true);
        } catch (Exception e) {
            return new Outcome("error: " + e, true);
        }
        final List<String> lines = Arrays.asList(out.split("\n", -1));
        // #417 follow-up review: this used to be `out.length() > BASH_MAX_CHARS || lines.size() > ...`
        // - the OR meant a handful of very long lines (few lines, but over the char cap) triggered
        // the line-based head/tail reconstruction below even though midStart>=midEnd (no real gap
        // to speak of), producing head+tail that fully OVERLAPPED and DUPLICATED the entire input,
        // with "0 lines omitted" and no marker at all once the subsequent char-cap then halved it.
        // Reconstructing by LINE only makes sense when there's a genuine line-count excess; the
        // char-level cap below is now unconditional and independent, so a few oversized lines are
        // still bounded without this duplication.
        if (lines.size() > BASH_HEAD + BASH_TAIL + 10) {
            final int midStart = BASH_HEAD;
            final int midEnd = lines.size() - BASH_TAIL;
            // #423: the head/tail reconstruction itself is #417's own original, unconditional
            // behavior - only the error-relevant-lines scan of the otherwise-fully-dark omitted
            // middle is this strategy's own, toggleable addition on top of it.
            String errorBlock = "";
            if (errorLineExtractionEnabled) {
                final List<String> matched = lines.subList(midStart, midEnd).stream()
                        .filter(l -> ERROR_LINE.matcher(l).find()).toList();
                // #417 follow-up review: `.limit(MAX_ERROR_LINES_KEPT)` on the stream above kept the
                // OLDEST matches - backwards, since early build/dependency noise (a Gradle plugin is
                // literally named "error-prone") fills the quota before the real failure near the END
                // of the log, closest to the tail, is ever reached.
                final List<String> errorLines = matched.size() <= MAX_ERROR_LINES_KEPT ? matched
                        : matched.subList(matched.size() - MAX_ERROR_LINES_KEPT, matched.size());
                // #417 follow-up review: placed right before the TAIL, not right after the head - if
                // the char-level cap below still has to fire, these lines now sit far closer to what
                // that cap actually keeps (measured: ~3.4% of already-capped outputs lost this block
                // entirely under the old head-adjacent placement).
                errorBlock = errorLines.isEmpty() ? "" : "… error-relevant lines kept verbatim from the omitted range:\n" + String.join("\n", errorLines) + "\n";
            }
            out = String.join("\n", lines.subList(0, midStart))
                    + "\n… [" + (midEnd - midStart) + " lines omitted; filter the command's output next time] …\n"
                    + errorBlock
                    + String.join("\n", lines.subList(midEnd, lines.size()));
        }
        if (out.length() > BASH_MAX_CHARS) out = out.substring(0, BASH_MAX_CHARS / 2) + "\n… [truncated] …\n" + out.substring(out.length() - BASH_MAX_CHARS / 2);
        return new Outcome((out.isBlank() ? "(no output)" : out.strip()) + "\n[exit " + rc + "]", rc != 0);
    }

    /** POSIX single-quoting: safe against anything the value contains, including spaces and `$`. */
    static String shellQuote(final String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** #325 (comment 2): a bash command's own output, not a scoped-down version of it like read()'s
     *  line-numbered rendering - two cheap, lossless cleanups found live to be reliably pure noise.
     *  Trailing per-line whitespace is dropped unconditionally (never meaningful). A run of 3+ blank
     *  lines is capped at 2 - long enough to cost real tokens, and still unambiguously "a gap was
     *  here" without the full original count. Leading/internal whitespace (indentation, column
     *  alignment in `docker ps`/`kubectl get pods`-style output, a `tree` listing's own depth) is
     *  deliberately left alone: a short, common run is usually one token already under this
     *  model's own BPE, so stripping it saves nothing while risking silently destroying structure -
     *  worse than truncation, which at least announces itself. */
    static String tidyWhitespace(final String s) {
        final String[] lines = s.split("\n", -1);
        final List<String> out = new ArrayList<>(lines.length);
        int blankRun = 0;
        for (final String raw : lines) {
            int end = raw.length();
            while (end > 0 && (raw.charAt(end - 1) == ' ' || raw.charAt(end - 1) == '\t')) end--;
            final String line = raw.substring(0, end);
            if (line.isEmpty()) {
                blankRun++;
                if (blankRun <= 2) out.add(line);
            } else {
                blankRun = 0;
                out.add(line);
            }
        }
        return String.join("\n", out);
    }

    public static Duration bashTimeout(int requested) { return Duration.ofSeconds(Math.min(1800, Math.max(5, requested))); }
}
