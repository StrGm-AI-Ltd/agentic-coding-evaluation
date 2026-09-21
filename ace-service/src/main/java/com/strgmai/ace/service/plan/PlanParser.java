package com.strgmai.ace.service.plan;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of runner/plan.py: parse an IMPLEMENTATION_PLAN.md into ordered tasks. Deterministic.
 *  A task is a heading/list item/table row carrying an id like T1 / ST-01 / Task 3; fields are
 *  labelled lines or table cells. Order = topological over declared dependencies, ties by id number.
 *  An unparseable plan or a dependency cycle raises PlanError — the harness marks the run INVALID
 *  rather than repairing the plan (plan quality is the plan phase's job). */
public final class PlanParser {
    private PlanParser() {}

    static final Pattern ID_RE = Pattern.compile("\\b(?:T|ST|S|Task|Subtask)[- ]?0*(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern FIELD_HEADING = Pattern.compile("^\\s{0,3}#{2,6}\\s*(goal|objective|services?|components?|dependencies|depends on|after|requires|acceptance(?: criteri\\w+)?|criteri\\w+|verified by|verification|done when)\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern CHECK_ID = Pattern.compile("\\b([SPMBCF]\\d{1,2})\\b");
    static final Map<String, Pattern> FIELD_RE = Map.of(
            "goal", Pattern.compile("^\\s*(?:[-*]\\s*)?\\*{0,3}\\s*(goal|objective|deliverable|what)\\s*\\*{0,3}\\s*[:—-]\\s*(.+)", Pattern.CASE_INSENSITIVE),
            "services", Pattern.compile("^\\s*(?:[-*]\\s*)?\\*{0,3}\\s*(services?|components?|modules?|affected)\\s*\\*{0,3}\\s*[:—-]\\s*(.+)", Pattern.CASE_INSENSITIVE),
            "deps", Pattern.compile("^\\s*(?:[-*]\\s*)?\\*{0,3}\\s*(dependencies|depends? on|deps|after|requires|prereq(?:uisite)?s?|blocked by)\\s*\\*{0,3}\\s*(?:[:—-]\\s*)?(.+)", Pattern.CASE_INSENSITIVE),
            "acceptance", Pattern.compile("^\\s*(?:[-*]\\s*)?\\*{0,3}\\s*(acceptance(?: criteri(?:on|a))?|criteri(?:on|a)|verified by|verification|done when|test)\\s*\\*{0,3}\\s*[:—-]\\s*(.+)", Pattern.CASE_INSENSITIVE),
            "checks", Pattern.compile("^\\s*(?:[-*]\\s*)?\\*{0,3}\\s*(oracle|checks|scored by)\\s*\\*{0,3}\\s*[:—-]\\s*(.+)", Pattern.CASE_INSENSITIVE));
    static final Pattern SEGMENT = Pattern.compile("(?=\\b(?:Goal|Objective|Deliverable|Services?|Components?|Modules?|Dependencies|Depends on|Deps|After|Requires|Prereq\\w*|Blocked by|Acceptance(?: criteri\\w+)?|Criteri\\w+|Verified by|Verification|Done when|Oracle|Checks|Scored by)\\s*[:—-])", Pattern.CASE_INSENSITIVE);
    static final Pattern LIST_HEAD = Pattern.compile("^\\s*(?:[-*]|\\d+[.)])\\s+\\*{0,3}\\s*(?:T|ST|Task|Subtask)[- ]?\\d", Pattern.CASE_INSENSITIVE);

    public static String normId(String n) { return "T" + Integer.parseInt(n); }

    public static List<PlanTask> parseFile(final java.nio.file.Path path) {
        try {
            return parse(java.nio.file.Files.readString(path));
        } catch (java.io.IOException e) {
            throw new PlanError("cannot read plan: " + e.getMessage(), e);   // keep the stack trace of the I/O failure
        }
    }

    public static List<PlanTask> parse(final String text) {
        final List<List<String>> blocks = splitBlocks(text);
        final Map<String, PlanTask> tasks = new LinkedHashMap<>();
        for (List<String> block : blocks) {
            final String head = block.get(0);
            final Matcher m = ID_RE.matcher(head);
            if (!m.find()) continue;
            final String tid = normId(m.group(1));
            final var t = new PlanTask(tid, titleOf(head, tid));
            final boolean table = head.strip().startsWith("|");
            if (table) {
                final List<String> cells = new ArrayList<>();
                for (String c : head.strip().replaceAll("^\\||\\|$", "").split("\\|")) cells.add(c.strip());
                if (cells.size() >= 2) t.goal = cells.get(1);
                if (cells.size() >= 3) t.services = cells.get(2);
                if (cells.size() >= 4) t.deps = parseDeps(cells.get(3));
                if (cells.size() >= 5) t.acceptance = cells.get(4);
            }
            // fields may sit on the head line, on labelled lines, or under field sub-headings (C-8)
            final List<String> lines = new ArrayList<>(block);
            for (int i = 0; i < lines.size(); i++) {
                final Matcher fh = FIELD_HEADING.matcher(lines.get(i));
                if (fh.find()) {
                    final var val = new StringBuilder();
                    for (int j = i + 1; j < lines.size() && !lines.get(j).matches("\\s{0,3}#{1,6}\\s.*"); j++)
                        val.append(lines.get(j).strip()).append(' ');
                    lines.set(i, fh.group(1) + ": " + val.toString().strip());
                }
            }
            final List<String> segs = new ArrayList<>();
            for (String line : lines) for (String seg : SEGMENT.split(line)) if (!seg.strip().isEmpty()) segs.add(seg);
            for (String seg : segs) {
                for (var e : FIELD_RE.entrySet()) {
                    final Matcher mm = e.getValue().matcher(seg);
                    if (mm.find()) {
                        String val = mm.group(2).strip();
                        if (val.endsWith(".")) val = val.substring(0, val.length() - 1);
                        val = val.strip();
                        switch (e.getKey()) {
                            case "deps" -> t.deps = parseDeps(val);
                            case "checks" -> { var ids = new TreeSet<>(Comparator.<String>comparingInt(c -> Integer.parseInt(c.substring(1))));
                                for (var cm : CHECK_ID.matcher(val.toUpperCase()).results().toList()) ids.add(cm.group(1));
                                t.checks = ids.isEmpty() ? null : List.copyOf(ids); }
                            case "goal" -> { if (t.goal == null || t.goal.isBlank()) t.goal = val; }
                            case "services" -> { if (t.services == null || t.services.isBlank()) t.services = val; }
                            case "acceptance" -> { if (t.acceptance == null || t.acceptance.isBlank()) t.acceptance = val; }
                        }
                        break;
                    }
                }
            }
            if (t.goal == null || t.goal.isBlank()) {
                String body = String.join(" ", block.subList(1, block.size()).stream().map(String::strip)
                        .filter(s -> !s.isEmpty() && !s.startsWith("|")).toList());
                t.goal = (body.isEmpty() ? t.title : body.substring(0, Math.min(body.length(), 300)));
            }
            if (t.deps == null) {
                final String body = String.join("\n", block.subList(1, block.size()));
                final Set<String> mentioned = new TreeSet<>(Comparator.comparingInt(x -> Integer.parseInt(x.substring(1))));
                for (var dm : ID_RE.matcher(body).results().toList()) { String x = normId(dm.group(1)); if (!x.equals(tid)) mentioned.add(x); }
                t.deps = (!mentioned.isEmpty() && Pattern.compile("\\b(depend|after|requires|prereq|blocked)", Pattern.CASE_INSENSITIVE).matcher(body).find())
                        ? List.copyOf(mentioned) : List.of();
            }
            t.dedupScore = t.score() + (table ? 0 : 1);   // a detailed section beats an overview row
            final PlanTask prev = tasks.get(tid);
            if (prev == null || t.dedupScore > prev.dedupScore) tasks.put(tid, t);
        }
        if (tasks.isEmpty()) throw new PlanError("no tasks found (expected headings/list items/table rows carrying ids like T1, ST-01, Task 3)");
        for (PlanTask t : tasks.values())
            t.deps = t.deps.stream().filter(d -> tasks.containsKey(d) && !d.equals(t.id)).toList();
        return topological(tasks);
    }

    private static String titleOf(final String head, final String tid) {
        String s = head.replaceFirst("^[\\s#*|\\-.\\d)]+", "").strip().replaceFirst("^\\|", "").strip();
        s = s.replaceFirst("^(?:T|ST|Task|Subtask)[- ]?\\d+\\s*[—:\\-–.]*\\s*", "").strip();
        return (s.isEmpty() ? tid : s).substring(0, Math.min(s.isEmpty() ? tid.length() : s.length(), 120));
    }

    static List<String> parseDeps(final String s) {
        if (Pattern.compile("\\b(none|n/a|-|—|no dependencies|nothing)\\b", Pattern.CASE_INSENSITIVE).matcher(s.strip()).find() && !ID_RE.matcher(s).find())
            return List.of();
        final Set<String> ids = new TreeSet<>(Comparator.comparingInt(x -> Integer.parseInt(x.substring(1))));
        for (var m : ID_RE.matcher(s).results().toList()) ids.add(normId(m.group(1)));
        return List.copyOf(ids);
    }

    /** blocks start at a heading/list item/table row that carries a task id; a non-task, non-field heading ends the task */
    static List<List<String>> splitBlocks(final String text) {
        final List<List<String>> blocks = new ArrayList<>();
        List<String> cur = null;
        for (String line : text.split("\n", -1)) {
            boolean starts = (line.matches("\\s{0,3}#{1,6}\\s.*") && ID_RE.matcher(line).find())
                    || LIST_HEAD.matcher(line).find()
                    || (line.strip().startsWith("|") && line.length() - line.replace("|", "").length() > 1
                        && ID_RE.matcher(line.split("\\|")[1]).find());
            if (starts) { cur = new ArrayList<>(List.of(line)); blocks.add(cur); }
            else if (cur != null) {
                if (line.matches("\\s{0,3}#{1,6}\\s.*") && !ID_RE.matcher(line).find() && !FIELD_HEADING.matcher(line).find()) cur = null;
                else cur.add(line);
            }
        }
        return blocks;
    }

    private static List<PlanTask> topological(final Map<String, PlanTask> tasks) {
        final List<PlanTask> order = new ArrayList<>();
        final Set<String> done = new HashSet<>(), visiting = new HashSet<>();
        for (String tid : tasks.keySet().stream().sorted(Comparator.comparingInt(x -> Integer.parseInt(x.substring(1)))).toList())
            visit(tid, tasks, new ArrayDeque<>(), done, visiting, order);
        return order;
    }

    private static void visit(final String tid, final Map<String, PlanTask> tasks, final Deque<String> chain, final Set<String> done, final Set<String> visiting, final List<PlanTask> order) {
        if (done.contains(tid)) return;
        if (visiting.contains(tid)) { chain.add(tid); throw new PlanError("dependency cycle: " + String.join(" -> ", chain)); }
        visiting.add(tid); chain.add(tid);
        for (String d : tasks.get(tid).deps) visit(d, tasks, chain, done, visiting, order);
        chain.removeLast(); visiting.remove(tid); done.add(tid); order.add(tasks.get(tid));
    }

    /** topological levels: wave k holds the tasks whose dependencies are all in earlier waves */
    public static List<List<PlanTask>> waves(final List<PlanTask> tasks) {
        final Map<String, Integer> level = new HashMap<>();
        for (PlanTask t : tasks) {
            final int max = t.deps.stream().filter(level::containsKey).mapToInt(level::get).max().orElse(-1);
            level.put(t.id, 1 + max);
        }
        final Map<Integer, List<PlanTask>> out = new TreeMap<>();
        for (PlanTask t : tasks) out.computeIfAbsent(level.get(t.id), x -> new ArrayList<>()).add(t);
        return out.values().stream().toList();
    }

    /** port of evaluate_parallel_plan: valid = every task exactly once, never before/beside a dependency;
     *  parallelism_pct = how much of the available parallelism the schedule captures. */
    public static Map<String, Object> evaluateParallelPlan(final Map<String, Object> pp, final List<PlanTask> tasks) {
        final List<String> errors = new ArrayList<>();
        final List<String> ids = tasks.stream().map(t -> t.id).toList();
        final Map<String, Set<String>> deps = new HashMap<>();
        tasks.forEach(t -> deps.put(t.id, new HashSet<>(t.deps == null ? List.of() : t.deps)));
        final int minWaves = waves(tasks).size();
        if (pp == null || !(pp.get("waves") instanceof List<?> ws) || ws.isEmpty())
            return Map.of("valid", false, "errors", List.of("no parallelisation plan"), "n_tasks", ids.size(), "min_waves", minWaves, "parallelism_pct", 0.0);
        final List<List<String>> planWaves = new ArrayList<>();
        ws.forEach(w -> planWaves.add(((List<?>) w).stream().map(String::valueOf).toList()));
        final List<String> flat = planWaves.stream().flatMap(List::stream).toList();
        final List<String> missing = ids.stream().filter(i -> !flat.contains(i)).toList();
        final List<String> unknown = flat.stream().filter(i -> !ids.contains(i)).toList();
        final List<String> dup = flat.stream().filter(i -> Collections.frequency(flat, i) > 1).distinct().toList();
        if (!missing.isEmpty()) errors.add("tasks missing from the schedule: " + missing);
        if (!unknown.isEmpty()) errors.add("unknown task ids: " + unknown);
        if (!dup.isEmpty()) errors.add("tasks scheduled more than once: " + dup);
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < planWaves.size(); i++) {
            for (String t : planWaves.get(i))
                for (String d : deps.getOrDefault(t, Set.of()))
                    if (!seen.contains(d)) errors.add(t + " in wave " + (i + 1) + " before/beside its dependency " + d);
            seen.addAll(planWaves.get(i));   // per-wave, exactly as the Python original: only earlier waves count as seen
        }
        final int n = ids.size(), wPlan = (int) planWaves.stream().filter(w -> !w.isEmpty()).count();
        final double par = n <= minWaves ? 100.0 : Math.max(0, Math.min(100, 100.0 * (n - wPlan) / (n - minWaves)));
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", errors.isEmpty());
        out.put("errors", errors);
        out.put("n_tasks", n);
        out.put("min_waves", minWaves);
        out.put("plan_waves", wPlan);
        out.put("parallelism_pct", Math.round((errors.isEmpty() ? par : 0.0) * 10) / 10.0);
        out.put("max_wave_width", planWaves.stream().mapToInt(List::size).max().orElse(1));
        return out;
    }
}
