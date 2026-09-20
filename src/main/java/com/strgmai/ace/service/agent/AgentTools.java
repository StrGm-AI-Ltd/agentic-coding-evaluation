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

    public record Outcome(String output, boolean isError) {}

    public static Path resolve(String cwd, String path) {
        Path p = Paths.get(path == null ? "" : path);
        return (p.isAbsolute() ? p : Paths.get(cwd).resolve(p)).normalize();
    }

    public static Outcome read(String cwd, Map<String, Object> a) {
        Path p = resolve(cwd, String.valueOf(a.getOrDefault("path", "")));
        if (!Files.isRegularFile(p)) return new Outcome("error: no such file: " + a.get("path"), true);
        List<String> lines;
        try { lines = Files.readAllLines(p, StandardCharsets.UTF_8); }
        catch (IOException e) { return new Outcome("error: " + e, true); }
        // clamp the lower bound: a 0/negative start_line would index lines.get(-1) and throw
        int s = Math.max(1, a.get("start_line") instanceof Number n ? n.intValue() : 1);
        if (!lines.isEmpty() && s > lines.size())
            return new Outcome("error: start_line " + s + " is past the end of the file (" + lines.size() + " lines)", true);
        int e = a.get("end_line") instanceof Number n ? Math.min(n.intValue(), lines.size()) : lines.size();
        if (e - s + 1 > READ_MAX_LINES && !a.containsKey("end_line")) e = s + READ_MAX_LINES - 1;
        StringBuilder out = new StringBuilder();
        for (int i = s; i <= e && i <= lines.size(); i++) out.append(String.format("%5d: %s%n", i, lines.get(i - 1)));
        String text = out.toString();
        if (text.length() > READ_MAX_CHARS) text = text.substring(0, READ_MAX_CHARS) + "\n… [truncated at " + READ_MAX_CHARS + " chars; read a narrower range]";
        if (e < lines.size()) text += "\n… [" + (lines.size() - e) + " more lines; the file has " + lines.size() + "]";
        return new Outcome(text.isEmpty() ? "(empty file)" : text, false);
    }

    public static Outcome write(String cwd, Map<String, Object> a) {
        String content = a.get("content") == null ? null : String.valueOf(a.get("content"));
        if (content == null) return new Outcome("error: content is required", true);
        Path p = resolve(cwd, String.valueOf(a.getOrDefault("path", "")));
        try {
            Files.createDirectories(p.getParent() == null ? Paths.get(".") : p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return new Outcome("wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + a.get("path"), false);
        } catch (IOException e) { return new Outcome("error: " + e, true); }
    }

    /** exact-match replace: the match must be unique (or replace_all); include context to make it unique */
    public static Outcome edit(String cwd, Map<String, Object> a) {
        Path p = resolve(cwd, String.valueOf(a.getOrDefault("path", "")));
        if (!Files.isRegularFile(p)) return new Outcome("error: no such file: " + a.get("path"), true);
        String src;
        try { src = Files.readString(p, StandardCharsets.UTF_8); } catch (IOException e) { return new Outcome("error: " + e, true); }
        // explicit null check: getOrDefault returns null when the key is present with a JSON null,
        // and String.valueOf(null) would search for the literal text "null"
        String old = a.get("old_string") == null ? "" : String.valueOf(a.get("old_string"));
        String neu = a.get("new_string") == null ? "" : String.valueOf(a.get("new_string"));
        if (old.isEmpty()) return new Outcome("error: old_string is empty", true);
        int n = countOccurrences(src, old);
        boolean all = Boolean.TRUE.equals(a.get("replace_all")) || "true".equals(String.valueOf(a.get("replace_all")));
        if (n == 0) return new Outcome("error: old_string not found (the match must be exact, including whitespace)", true);
        if (n > 1 && !all) return new Outcome("error: old_string occurs " + n + " times; add context to make it unique or set replace_all", true);
        String out = all ? src.replace(old, neu) : src.replaceFirst(Pattern.quote(old), Matcher.quoteReplacement(neu));
        try { Files.writeString(p, out, StandardCharsets.UTF_8); } catch (IOException e) { return new Outcome("error: " + e, true); }
        return new Outcome("edited " + a.get("path") + " (" + (all ? n : 1) + " replacement)", false);
    }

    static int countOccurrences(String s, String sub) {
        if (sub.isEmpty()) return 0;   // indexOf("", i) is always >= 0, which would loop forever
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    public static Outcome bash(String cwd, Map<String, Object> a, Map<String, String> env) {
        String cmd = String.valueOf(a.getOrDefault("command", ""));
        int to = 600;
        if (a.get("timeout_sec") instanceof Number n) to = Math.min(1800, Math.max(5, n.intValue()));
        if (cmd.isBlank()) return new Outcome("error: empty command", true);
        String out; int rc;
        try {
            Map<String, String> full = new LinkedHashMap<>(env);   // drain concurrently: >64KB output would deadlock a read-after-wait
            full.put("CI", "1"); full.put("NO_COLOR", "1"); full.put("TERM", "dumb");
            var r = com.strgmai.ace.service.docker.DockerService.proc(to, java.nio.file.Path.of(cwd), full, "zsh", "-lc", cmd);
            rc = r.rc();
            out = r.out() + (r.err().isBlank() ? "" : "\n[stderr]\n" + r.err());
            out = ANSI.matcher(out).replaceAll("");
            if (rc == 124 && out.contains("[timeout")) return new Outcome("[timeout after " + to + "s]\n[exit 124]", true);
        } catch (Exception e) {
            return new Outcome("error: " + e, true);
        }
        List<String> lines = Arrays.asList(out.split("\n", -1));
        if (out.length() > BASH_MAX_CHARS || lines.size() > BASH_HEAD + BASH_TAIL + 10) {
            out = String.join("\n", lines.subList(0, Math.min(BASH_HEAD, lines.size())))
                    + "\n… [" + Math.max(0, lines.size() - BASH_HEAD - BASH_TAIL) + " lines omitted; filter the command's output next time] …\n"
                    + String.join("\n", lines.subList(Math.max(0, lines.size() - BASH_TAIL), lines.size()));
            if (out.length() > BASH_MAX_CHARS) out = out.substring(0, BASH_MAX_CHARS / 2) + "\n… [truncated] …\n" + out.substring(out.length() - BASH_MAX_CHARS / 2);
        }
        return new Outcome((out.isBlank() ? "(no output)" : out.strip()) + "\n[exit " + rc + "]", rc != 0);
    }

    public static Duration bashTimeout(int requested) { return Duration.ofSeconds(Math.min(1800, Math.max(5, requested))); }
}
