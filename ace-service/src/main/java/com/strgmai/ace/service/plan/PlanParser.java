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

    // #287, found live: the bare "S" alternative collided character-for-character with this
    // project's OWN oracle check-id convention (S1-S9, plus P1-P3/M1-M4/B1-B3/C1-C2/F1-F9) - never
    // documented anywhere as a supported task-id form (this class's own javadoc only mentions
    // "T1 / ST-01 / Task 3"), yet agent-authored plans are EXPECTED to reference check ids verbatim
    // in their own "Oracle:"/"Checks:" fields. A dependencies field written as "none (verified by
    // S3)" matched this and recorded a phantom dependency on T3 despite explicitly saying "none";
    // the same regex also drives splitBlocks()'s own heading detection, so a heading like
    // "### S6 — notes" could be misdetected as introducing a new task T6. Dropped outright rather
    // than narrowed (e.g. "S only for 3+ digit ids") - it was never a real, supported form to begin
    // with, so there is nothing legitimate left to preserve.
    static final Pattern ID_RE = Pattern.compile("\\b(?:T|ST|Task|Subtask)[- ]?0*(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern FIELD_HEADING = Pattern.compile("^\\s{0,3}#{2,6}\\s*(goal|objective|services?|components?|dependencies|depends on|after|requires|acceptance(?: criteri\\w+)?|criteri\\w+|verified by|verification|done when)\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern CHECK_ID = Pattern.compile("\\b([SPMBCF]\\d{1,2})\\b");
    // #257, found live: "**Goal:** text" (the bold span wrapping the label AND its colon together,
    // the common way a model actually writes a bold field label) closes its "**" AFTER the colon,
    // not before it - every pattern here only ever allowed the optional stars BEFORE the colon, so
    // that trailing "**" leaked straight into the captured value as a prefix (e.g. goal becoming
    // "** Give `trading-service`..."). A second optional \*{0,3}\s* right after the colon consumes
    // it either way, matching both bold styles identically.
    static final Map<String, Pattern> FIELD_RE = Map.of(
            "goal", Pattern.compile("^\\s*(?:[-*]\\s*)?\\*{0,3}\\s*(goal|objective|deliverable|what)\\s*\\*{0,3}\\s*[:—-]\\s*\\*{0,3}\\s*(.+)", Pattern.CASE_INSENSITIVE),
            "services", Pattern.compile("^\\s*(?:[-*]\\s*)?\\*{0,3}\\s*(services?|components?|modules?|affected)\\s*\\*{0,3}\\s*[:—-]\\s*\\*{0,3}\\s*(.+)", Pattern.CASE_INSENSITIVE),
            "deps", Pattern.compile("^\\s*(?:[-*]\\s*)?\\*{0,3}\\s*(dependencies|depends? on|deps|after|requires|prereq(?:uisite)?s?|blocked by)\\s*\\*{0,3}\\s*(?:[:—-]\\s*)?\\*{0,3}\\s*(.+)", Pattern.CASE_INSENSITIVE),
            "acceptance", Pattern.compile("^\\s*(?:[-*]\\s*)?\\*{0,3}\\s*(acceptance(?: criteri(?:on|a))?|criteri(?:on|a)|verified by|verification|done when|test)\\s*\\*{0,3}\\s*[:—-]\\s*\\*{0,3}\\s*(.+)", Pattern.CASE_INSENSITIVE),
            "checks", Pattern.compile("^\\s*(?:[-*]\\s*)?\\*{0,3}\\s*(oracle|checks|scored by)\\s*\\*{0,3}\\s*[:—-]\\s*\\*{0,3}\\s*(.+)", Pattern.CASE_INSENSITIVE));
    static final Pattern SEGMENT = Pattern.compile("(?=\\b(?:Goal|Objective|Deliverable|Services?|Components?|Modules?|Dependencies|Depends on|Deps|After|Requires|Prereq\\w*|Blocked by|Acceptance(?: criteri\\w+)?|Criteri\\w+|Verified by|Verification|Done when|Oracle|Checks|Scored by)\\s*[:—-])", Pattern.CASE_INSENSITIVE);
    static final Pattern LIST_HEAD = Pattern.compile("^\\s*(?:[-*]|\\d+[.)])\\s+\\*{0,3}\\s*(?:T|ST|Task|Subtask)[- ]?\\d", Pattern.CASE_INSENSITIVE);
    static final Pattern NEW_LIST_ITEM = Pattern.compile("^\\s*(?:[-*]|\\d+[.)])\\s+");

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
                    continue;
                }
                // #265, found live: an inline labelled field ("- **Goal:** text...") that word-wraps
                // across multiple physical markdown lines - the segment/FIELD_RE matching below only
                // ever looks at ONE line at a time, so a goal this long was silently cut off right
                // before "exactly per §4.1: NUMERIC(18,2) money, ..." - the one clause telling the
                // agent exactly what to build, lost with no trace anywhere downstream. A continuation
                // line is anything that isn't itself a new list item/heading/field label - merge it
                // into this line before the matching below ever sees either one.
                //
                // Deliberately NOT applied to "deps"/"checks": found live on the real plan - T15's
                // own Dependencies line wraps onto "...real wiring is integration-verified in
                // T22/T23)." - a parenthetical ASIDE, not more dependencies. parseDeps()/the checks
                // parser scan their whole value for ANY Txx/[SPMBCF]\d+-looking token, so merging
                // prose like that in turns a mention into a phantom dependency - introduced a real
                // cycle (T15->T22->T21->T19->T16->T15) that doesn't exist in the actual plan. A
                // short, explicit id list is exactly the kind of field that is SAFE to leave as its
                // first line only; goal/services/acceptance are free prose, where the opposite risk
                // (silently losing the one sentence that matters) is the one that bit T6 for real.
                final String line = lines.get(i);
                final boolean isMergeableField = FIELD_RE.entrySet().stream()
                        .filter(e -> !e.getKey().equals("deps") && !e.getKey().equals("checks"))
                        .anyMatch(e -> e.getValue().matcher(line).find());
                if (isMergeableField) {
                    final var merged = new StringBuilder(line.strip());
                    int j = i + 1;
                    while (j < lines.size() && isFieldContinuation(lines.get(j))) { merged.append(' ').append(lines.get(j).strip()); j++; }
                    for (int k = i + 1; k < j; k++) lines.set(k, "");
                    if (j > i + 1) lines.set(i, merged.toString());
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
                            // #322 live: a task's own free-prose Acceptance criterion can contain
                            // "...passes after — ...", which this same deps pattern's bare "after"
                            // alternative also matches as a SECOND "deps" segment - previously
                            // assigned unconditionally, so that bogus later match (parsing to no
                            // ids) silently overwrote an already-correct, fully-populated
                            // Dependencies field with an empty list. deps/checks now follow the
                            // same first-match-wins guard goal/services/acceptance already use
                            // below - once legitimately resolved (possibly to an intentional empty
                            // list, hence the null check rather than isBlank/isEmpty), a later
                            // coincidental match is ignored instead of clobbering it.
                            case "deps" -> { if (t.deps == null) t.deps = parseDeps(val); }
                            case "checks" -> { if (t.checks == null) {
                                var ids = new TreeSet<>(Comparator.<String>comparingInt(c -> Integer.parseInt(c.substring(1))));
                                for (var cm : CHECK_ID.matcher(val.toUpperCase()).results().toList()) ids.add(cm.group(1));
                                t.checks = ids.isEmpty() ? null : List.copyOf(ids);
                            } }
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

    /** a line is a CONTINUATION of the previous inline field (not a new one, and not the end of the
     *  field) when it is non-blank and isn't itself a new list item, a heading, or a recognisable
     *  field label of its own - guards against ever swallowing the NEXT field's own line, even one
     *  written without a leading bullet marker. */
    private static boolean isFieldContinuation(final String line) {
        if (line.strip().isEmpty()) return false;
        if (NEW_LIST_ITEM.matcher(line).find()) return false;
        if (line.matches("\\s{0,3}#{1,6}\\s.*")) return false;
        return FIELD_RE.values().stream().noneMatch(p -> p.matcher(line).find());
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
