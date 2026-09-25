package com.strgmai.ace.ui;

import com.github.appreciated.apexcharts.ApexChartsBuilder;
import com.github.appreciated.apexcharts.config.builder.ChartBuilder;
import com.github.appreciated.apexcharts.config.builder.SeriesBuilder;
import com.github.appreciated.apexcharts.config.builder.TooltipBuilder;
import com.github.appreciated.apexcharts.config.builder.XAxisBuilder;
import com.github.appreciated.apexcharts.config.builder.YAxisBuilder;
import com.github.appreciated.apexcharts.config.chart.Type;
import com.github.appreciated.apexcharts.config.yaxis.builder.TitleBuilder;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Run detail — the UI twin of GET /api/runs/{id}: scores, badges, checks, plan tasks,
 * per-step scores, provenance, re-score, and the embedded file browser. */
@Route(value = "runs/:runId", layout = MainLayout.class)
public class RunDetailView extends VerticalLayout implements BeforeEnterObserver {
    private static final Logger log = LoggerFactory.getLogger(RunDetailView.class);

    private static final List<String> PROVENANCE_KEYS = List.of(
            "model", "quantization", "harness", "harness_version", "harness_sha",
            "oracle_sha", "omlx_version", "java_version");

    private final ServiceClient client;
    private String runId;

    public RunDetailView(final ServiceClient client) {
        this.client = client;
        setPadding(true);
    }

    @Override
    public void beforeEnter(final BeforeEnterEvent event) {
        runId = event.getRouteParameters().get("runId").orElse(null);
        render();
    }

    private void render() {
        removeAll();
        if (runId == null) {
            add(new H3("Run"), Panels.error("No run id in the URL."));
            return;
        }
        final Api.Run run;   // assigned exactly once below; a legal blank final
        try {
            run = client.run(runId);
        } catch (final Exception e) {
            if (isNotImported(e)) {
                addNotImportedPanel();
            } else {
                log.warn("could not load run {}: {}", runId, e.toString());
                add(new H3("Run " + runId), Panels.error(client.errorText(e)));
            }
            return;
        }
        render(run);
    }

    private void render(final Api.Run run) {
        add(new RouterLink("← Runs", RunsView.class));

        final var title = new com.vaadin.flow.component.html.H2(run.run_id());
        title.getStyle().set("margin", "4px 0").set("font-size", "1.6em");
        add(title);

        final var meta = new Span(metaLine(run));
        meta.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(meta);

        final var badges = new HorizontalLayout();
        badges.setPadding(false);
        badges.setSpacing(true);
        if (Boolean.FALSE.equals(run.valid())) {
            badges.add(Badges.text("INVALID", Badges.ERROR));
        }
        if (Boolean.TRUE.equals(run.contended())) {
            badges.add(Badges.text("CONTENDED", Badges.WARNING));
        }
        if (run.weighted_score_pct() == null) {
            badges.add(Badges.text("PARTIAL", Badges.WARNING));
        }
        if (!run.poolable()) {
            badges.add(Badges.text("schema " + run.schema_version(), Badges.CONTRAST));
        }
        if (badges.getComponentCount() > 0) {
            badges.getStyle().set("margin", "6px 0");
            add(badges);
        }

        final var scores = new HorizontalLayout();
        scores.setPadding(false);
        scores.setSpacing(true);
        final var functionalDetail = Fmt.points(run.functional_points_got(), run.functional_denominator())
                + (run.functional_ids() != null && !run.functional_ids().isEmpty()
                        ? ": " + String.join(", ", run.functional_ids())
                        : "");
        scores.add(score("functional", Fmt.pct(run.functional_score_pct()), functionalDetail));
        if (run.weighted_score_pct() != null) {
            scores.add(score("composite", Fmt.pct(run.weighted_score_pct()),
                    Fmt.points(run.points_got(), run.denominator())));
        } else {
            scores.add(score("partial", Fmt.pct(run.partial_score_pct()),
                    "skipped/infra checks: never a full score"));
        }
        scores.getStyle().set("margin", "8px 0");
        add(scores);

        if (Boolean.FALSE.equals(run.valid()) && run.validity_reasons() != null && !run.validity_reasons().isEmpty()) {
            final var reasons = run.validity_reasons().stream().map(ListItem::new).toList();
            add(Panels.callout("var(--lumo-error-color)", "var(--lumo-error-color-10pct)",
                    new Span("recorded, never ranked"), new UnorderedList(reasons.toArray(new ListItem[0]))));
        }
        if (Boolean.TRUE.equals(run.contended()) && run.manifest() != null) {
            final var contention = run.manifest().has("contention")
                    ? run.manifest().get("contention").toString() : "";
            add(Panels.warn("CONTENDED: excluded from leaderboards. " + contention));
        }

        if (!run.poolable()) {
            final var rescore = new Button("Queue re-score", e -> {
                try {
                    final var job = client.rescore(runId);
                    Notification.show("Queued re-score job #" + job.id(), 3000, Notification.Position.BOTTOM_END);
                    getUI().ifPresent(ui -> ui.navigate("jobs"));
                } catch (final Exception ex) {
                    log.warn("could not queue re-score for run {}: {}", runId, ex.toString());
                    Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
                }
            });
            rescore.getStyle().set("margin-top", "8px");
            add(rescore);
            add(new Span("Re-scoring with the current oracle makes this run poolable (needs its workspace.bundle)."));
        }

        addChecks(run.checks());
        addPlanTasks(run.metrics());
        addStepScores(run.metrics());
        addSpeedByContextChart(run.metrics());
        addProvenance(run);

        add(new FilesBrowser(client, runId, run.results_dir()));
    }

    /** A 404 on the run id — the job has not produced a scored, imported result yet. */
    static boolean isNotImported(final Exception e) {
        return e instanceof RestClientResponseException responseException
                && responseException.getStatusCode().value() == 404;
    }

    /** What the not-imported panel should say, from the run's own job row. */
    enum NotImportedKind {
        IN_FLIGHT, ENDED_WITHOUT_SCORE, BLOCKED, UNKNOWN
    }

    static NotImportedKind notImportedKind(final Api.Job job) {
        if (job == null) {
            return NotImportedKind.UNKNOWN;
        }
        if ("blocked".equals(job.status())) {
            return NotImportedKind.BLOCKED;
        }
        return JobStatuses.isTerminal(job.status()) ? NotImportedKind.ENDED_WITHOUT_SCORE
                : NotImportedKind.IN_FLIGHT;
    }

    /** The run's job row (the runs table has none until import). */
    static Api.Job jobForRun(final List<Api.Job> jobs, final String runId) {
        return jobs.stream().filter(job -> runId.equals(job.run_id())).findFirst().orElse(null);
    }

    /**
     * The self-service path: not-imported is expected while a run is in flight, so the
     * panel routes to the job's live page instead of leaving a dead end.
     */
    private void addNotImportedPanel() {
        add(new H3("Run " + runId));

        Api.Job job = null;   // reassigned below - cannot be final; null initializer rules out var
        try {
            job = jobForRun(client.jobs(), runId);
        } catch (final Exception e) {
            // the panel falls back to the generic rescan path, but loses the more specific
            // in-flight/blocked/ended messaging it would otherwise show
            log.warn("could not load jobs to find the one behind run {}: {}", runId, e.toString());
        }
        final var runJob = job;

        switch (notImportedKind(runJob)) {
            case IN_FLIGHT -> {
                add(Panels.warn("This run is still in progress — its results appear here automatically "
                        + "when the job finishes with a score. The job's live details (steps, sessions, "
                        + "requests, log tail) are on its job page."));
                add(new Button("Open the job's live page",
                        e -> getUI().ifPresent(ui -> ui.navigate("jobs/" + runJob.id()))));
            }
            case BLOCKED -> {
                add(Panels.warn("This run's job is blocked — requeue it from the queue page; the "
                        + "run appears here once it finishes with a score."));
                add(new Button("Open the job",
                        e -> getUI().ifPresent(ui -> ui.navigate("jobs/" + runJob.id()))));
            }
            case ENDED_WITHOUT_SCORE -> {
                add(Panels.warn("This run's job ended without a scored result — such runs are "
                        + "never imported."));
                add(new Button("Open the job",
                        e -> getUI().ifPresent(ui -> ui.navigate("jobs/" + runJob.id()))));
            }
            default -> {
                add(Panels.warn("This run is not imported yet. A run appears here once its job finishes "
                        + "with a scored result (oracle.json on disk); cancelled or unfinished runs never import."));
                add(new Button("Rescan results/", e -> {
                    try {
                        client.importAll();
                        render();
                    } catch (final Exception ex) {
                        log.warn("could not rescan results/ for run {}: {}", runId, ex.toString());
                        Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
                    }
                }));
            }
        }
    }

    private void addChecks(final List<Api.Check> checks) {
        add(Panels.sectionTitle("Checks"));
        final var grid = new Grid<>(Api.Check.class, false);
        grid.addColumn(Api.Check::check_id).setHeader("id").setAutoWidth(true);
        grid.addColumn(Api.Check::category).setHeader("category").setAutoWidth(true);
        grid.addColumn(c -> Fmt.num(c.weight())).setHeader("weight").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(c -> Badges.status(c.status())))
                .setHeader("status").setAutoWidth(true)
                .setSortable(true).setComparator(Fmt.nullsLast(Api.Check::status));
        grid.addColumn(Api.Check::description).setHeader("check").setAutoWidth(true);
        grid.addColumn(Api.Check::detail).setHeader("detail").setFlexGrow(1);
        grid.setItems(checks == null ? List.of() : checks);
        grid.setAllRowsVisible(true);
        // the detail/check columns truncate long text in a fixed-width cell - a row click opens
        // everything unclipped, since a check's detail can run well past what a cell can show
        grid.addItemClickListener(e -> openCheckDialog(e.getItem()));
        add(grid);
    }

    /** Full, unclipped detail for one check row (id, category, weight, status, description, detail). */
    private void openCheckDialog(final Api.Check c) {
        final var dialog = new Dialog();
        dialog.setHeaderTitle(c.check_id());
        dialog.setWidth("min(600px, 90vw)");

        final var body = new VerticalLayout();
        body.setPadding(false);
        body.setSpacing(false);
        body.add(new HorizontalLayout(Badges.status(c.status()),
                new Span("category: " + (c.category() == null ? "–" : c.category())),
                new Span("weight: " + Fmt.num(c.weight()))));
        body.add(Panels.sectionTitle("check"));
        body.add(Panels.mono(c.description()));
        body.add(Panels.sectionTitle("detail"));
        body.add(Panels.mono(c.detail()));
        dialog.add(body);

        final var close = new Button("Close", ev -> dialog.close());
        dialog.getFooter().add(close);
        dialog.open();
    }

    record PerTaskRow(String tid, String reported, String doneVerified, Long requests,
            Long tokens, Long maxPrompt, Double wall, String filesChanged, String overBudget) {
    }

    /** The metrics.per_task table rows (T-7) — pure, tested against real-shape fixtures. */
    static List<PerTaskRow> perTaskRows(final JsonNode metrics) {
        final var perTask = metrics == null ? null : metrics.get("per_task");
        if (perTask == null || !perTask.isObject() || perTask.isEmpty()) {
            return List.of();
        }
        // returned as List<PerTaskRow>; empty-diamond under var would infer <Object>
        final List<PerTaskRow> rows = new ArrayList<>();
        perTask.propertyNames().stream().sorted().forEach(tid -> {
            final var t = perTask.get(tid);
            rows.add(new PerTaskRow(
                    tid,
                    Fmt.textOr(t.path("reported"), "–"),
                    Fmt.textOr(t.path("done_verified"), "–"),
                    longOrNull(t.path("requests")),
                    longOrNull(t.path("completion_tokens")),
                    longOrNull(t.path("max_prompt")),
                    doubleOrNull(t.path("task_wall_sec")),
                    t.path("files_changed").isNumber() ? String.valueOf(t.path("files_changed").intValue()) : "–",
                    t.path("over_budget").asBoolean(false) ? "yes" : ""));
        });
        return rows;
    }

    /** The metrics.per_task table from the Jinja2 run page (V-4). */
    private void addPlanTasks(final JsonNode metrics) {
        final var rows = perTaskRows(metrics);
        if (rows.isEmpty()) {
            return;
        }
        add(Panels.sectionTitle("Plan tasks"));
        final var grid = new Grid<>(PerTaskRow.class, false);
        grid.addColumn(PerTaskRow::tid).setHeader("task").setAutoWidth(true);
        grid.addColumn(PerTaskRow::reported).setHeader("reported").setAutoWidth(true);
        grid.addColumn(PerTaskRow::doneVerified).setHeader("verified").setAutoWidth(true);
        grid.addColumn(r -> Fmt.count(r.requests())).setHeader("requests").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(r -> Fmt.count(r.tokens())).setHeader("out tokens").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(r -> Fmt.count(r.maxPrompt())).setHeader("max prompt").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(r -> Fmt.duration(r.wall())).setHeader("wall").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(PerTaskRow::filesChanged).setHeader("files changed").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(PerTaskRow::overBudget).setHeader("over budget").setAutoWidth(true);
        grid.setItems(rows);
        grid.setAllRowsVisible(true);
        add(grid);
    }

    record StepRow(String sid, Double scorePct, boolean measured) {
    }

    /** The metrics.steps table rows (T-7) — pure, tested against real-shape fixtures. */
    static List<StepRow> stepRows(final JsonNode metrics) {
        final var steps = metrics == null ? null : metrics.get("steps");
        if (steps == null || !steps.isObject() || steps.isEmpty()) {
            return List.of();
        }
        // returned as List<StepRow>; empty-diamond under var would infer <Object>
        final List<StepRow> rows = new ArrayList<>();
        steps.propertyNames().stream().sorted().forEach(sid -> {
            final var s = steps.get(sid);
            rows.add(new StepRow(sid, doubleOrNull(s.path("score_pct")), s.path("measured").asBoolean(true)));
        });
        return rows;
    }

    /** The metrics.steps table from the Jinja2 run page (V-4). */
    private void addStepScores(final JsonNode metrics) {
        final var rows = stepRows(metrics);
        if (rows.isEmpty()) {
            return;
        }
        add(Panels.sectionTitle("Per-step scores"));
        final var grid = new Grid<>(StepRow.class, false);
        grid.addColumn(StepRow::sid).setHeader("step").setAutoWidth(true);
        grid.addColumn(r -> Fmt.pct(r.scorePct())).setHeader("score %").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(r -> r.measured() ? "" : "not measured").setHeader("").setAutoWidth(true);
        grid.setItems(rows);
        grid.setAllRowsVisible(true);
        add(grid);
    }

    record SpeedBucketRow(String label, Double avgPrefillTokPerSec, Double avgDecodeTokPerSec, long requests) {
    }

    /** The metrics.speed_by_context rows (T-7 pattern) — pure, tested against real-shape fixtures.
     * JournalFacts buckets every chat completion in the run by its prompt_tokens (context-size
     * proxy) and averages oMLX's own reported prefill/decode tok/s per bucket. requests: how many
     * requests informed that average — bucket width now adapts to the run's own context range (up
     * to 12 buckets), so some points can rest on very few requests; surfaced in the chart's tooltip
     * so a thin, less-trustworthy point is distinguishable from a well-supported one. */
    static List<SpeedBucketRow> speedByContextRows(final JsonNode metrics) {
        final var buckets = metrics == null ? null : metrics.get("speed_by_context");
        if (buckets == null || !buckets.isArray() || buckets.isEmpty()) {
            return List.of();
        }
        final List<SpeedBucketRow> rows = new ArrayList<>();
        for (final var b : buckets) {
            rows.add(new SpeedBucketRow(bucketLabel(b.path("context_hi").asLong(0)),
                    doubleOrNull(b.path("avg_prefill_tok_per_sec")), doubleOrNull(b.path("avg_decode_tok_per_sec")),
                    b.path("requests").asLong(0)));
        }
        return rows;
    }

    /** The bucket's right edge in K tokens, unitless - a range like "2K-3K" repeats visual clutter
     * over 50+ points; the axis title/tooltip already say "context size", so the bare number reads
     * as "up to this many K tokens" without the redundant range and unit on every tick. */
    static String bucketLabel(final long hi) {
        return String.valueOf(hi / 1024);
    }

    /** Speed vs context size: how prefill/decode speed trends as the context window fills up,
     * across the run's own requests (not just their average). */
    private void addSpeedByContextChart(final JsonNode metrics) {
        final var rows = speedByContextRows(metrics);
        if (rows.isEmpty()) {
            return;
        }
        add(Panels.sectionTitle("Speed vs context size"));
        // prefill runs 10-100x faster than decode - on one shared axis the decode line reads as flat
        // zero, so each series gets its own y-axis (decode's opposite the plot, matching its series)
        final var chart = ApexChartsBuilder.get()
                .withChart(ChartBuilder.get().withType(Type.LINE).build())
                .withXaxis(XAxisBuilder.get()
                        .withCategories(rows.stream().map(SpeedBucketRow::label).toArray(String[]::new))
                        // labels are bare numbers (no "K", no range) - the title is the only place the unit lives
                        .withTitle(com.github.appreciated.apexcharts.config.xaxis.builder.TitleBuilder.get()
                                .withText("context (K tokens)").build())
                        .build())
                .withYaxis(
                        YAxisBuilder.get().withTitle(TitleBuilder.get().withText("prefill tok/s").build()).build(),
                        YAxisBuilder.get().withTitle(TitleBuilder.get().withText("decode tok/s").build()).withOpposite(true).build())
                .withTooltip(TooltipBuilder.get().withCustom(speedByContextTooltipJs(rows)).build())
                .withSeries(
                        SeriesBuilder.get().withName("prefill tok/s")
                                .withData(rows.stream().map(SpeedBucketRow::avgPrefillTokPerSec).toArray(Double[]::new)).build(),
                        SeriesBuilder.get().withName("decode tok/s")
                                .withData(rows.stream().map(SpeedBucketRow::avgDecodeTokPerSec).toArray(Double[]::new)).build())
                .build();
        chart.setHeight("300px");
        add(chart);
    }

    /** Bucket width now adapts to the run's own context range (up to 12 buckets), so some points can
     * rest on very few requests — worth showing, not just the averages, so a thin point reads as less
     * trustworthy than a well-supported one. ApexCharts' custom tooltip is a raw JS function string
     * (no per-point Java callback hook exists in this wrapper); labels and per-bucket request counts
     * are baked in as JS array literals, indexed by dataPointIndex — struct.w.globals.categories is
     * NOT used here: it threw "Cannot read properties of undefined" against this addon's bundled
     * ApexCharts build (verified live), so only the documented struct.series/struct.dataPointIndex
     * fields are relied on; series names are the two literal names this chart's own withSeries(...)
     * calls use, not read from the chart at all. */
    static String speedByContextTooltipJs(final List<SpeedBucketRow> rows) {
        final var labels = rows.stream().map(r -> "\"" + r.label().replace("\"", "\\\"") + "\"").collect(java.util.stream.Collectors.joining(","));
        final var reqCounts = rows.stream().map(r -> String.valueOf(r.requests())).collect(java.util.stream.Collectors.joining(","));
        return """
                function(struct) {
                  var labels = [%s];
                  var reqCounts = [%s];
                  var seriesNames = ["prefill tok/s", "decode tok/s"];
                  var i = struct.dataPointIndex;
                  var html = '<div style="padding:8px 12px">' +
                    '<div style="font-weight:600;margin-bottom:4px">' + labels[i] + '</div>';
                  for (var s = 0; s < struct.series.length; s++) {
                    html += '<div>' + seriesNames[s] + ': ' + struct.series[s][i] + '</div>';
                  }
                  html += '<div style="color:var(--lumo-secondary-text-color);margin-top:4px">n=' + reqCounts[i] + ' requests</div></div>';
                  return html;
                }
                """.formatted(labels, reqCounts);
    }

    /** The provenance block from the Jinja2 run page (V-4) — values may be objects
     * (e.g. quantization is an 11-property map in real manifests), so never asText() them. */
    private void addProvenance(final Api.Run run) {
        add(Panels.sectionTitle("Provenance"));
        final var provenance = new VerticalLayout();
        provenance.setPadding(false);
        provenance.setSpacing(false);
        for (final var line : provenanceLines(run.manifest(), run.results_dir())) {
            final var span = new Span(line);
            span.getStyle().set("font-variant-numeric", "tabular-nums");
            provenance.add(span);
        }
        if (provenance.getComponentCount() == 0) {
            provenance.add(new Span("no provenance recorded"));
        }
        add(provenance);
    }

    /**
     * Pure line extraction for the provenance block: every real value goes through
     * Fmt.textOr (scalars) or Fmt.json (containers) — both Jackson 3-safe. Keys are
     * taken from the same list the Jinja2 page uses; unknown manifest keys are ignored.
     */
    static List<String> provenanceLines(final JsonNode manifest, final String resultsDir) {
        // returned as List<String>; empty-diamond under var would infer <Object>
        final List<String> lines = new ArrayList<>();
        final var prov = manifest == null ? null : manifest.get("provenance");
        if (prov != null && prov.isObject()) {
            for (final var key : PROVENANCE_KEYS) {
                final var value = prov.get(key);
                if (value == null || value.isNull() || value.isMissingNode()) {
                    continue;
                }
                lines.add(key + ": " + (value.isContainer()
                        ? Fmt.json(value) : Fmt.textOr(value, "")));
            }
        }
        if (manifest != null && manifest.hasNonNull("usable_context")) {
            final var usableContext = manifest.get("usable_context");
            lines.add("usable_context: " + (usableContext.isNumber()
                    ? Fmt.count(usableContext.longValue()) : Fmt.textOr(usableContext, "–")));
        }
        if (resultsDir != null && !resultsDir.isBlank()) {
            lines.add("results: " + resultsDir);
        }
        return lines;
    }

    private static Long longOrNull(final JsonNode node) {
        return node.isNumber() ? node.longValue() : null;
    }

    private static Double doubleOrNull(final JsonNode node) {
        return node.isNumber() ? node.doubleValue() : null;
    }

    String metaLine(final Api.Run run) {
        // returned via String.join, which needs Iterable<? extends CharSequence> - empty-diamond
        // under var would infer List<Object> and fail to compile there
        final List<String> parts = new ArrayList<>();
        if (run.task() != null) parts.add(run.task());
        if (run.mode() != null) parts.add(run.mode());
        if (run.model() != null) parts.add(run.model());
        if (run.harness() != null) parts.add("harness " + run.harness());
        parts.add("schema " + run.schema_version());
        parts.add("wall " + Fmt.duration(run.wall_sec()));
        parts.add("tokens " + Fmt.count(run.completion_tokens()));
        final var leaderboard = run.metrics() == null ? null : run.metrics().path("leaderboard");
        if (leaderboard != null && leaderboard.path("avg_latency_sec").isNumber())
            parts.add("avg latency " + Fmt.seconds(leaderboard.path("avg_latency_sec").doubleValue()));
        if (leaderboard != null && leaderboard.path("avg_first_byte_ms").isNumber())
            parts.add("avg TTFT " + Fmt.millis(leaderboard.path("avg_first_byte_ms").longValue()));
        return String.join(" · ", parts);
    }

    private Span score(final String label, final String pct, final String detail) {
        final var span = new Span();
        final var value = new Span(pct);
        value.getStyle().set("font-size", "1.3em").set("font-weight", "600");
        final var labelSpan = new Span(label + " ");
        labelSpan.getStyle().set("color", "var(--lumo-secondary-text-color)");
        final var detailSpan = new Span(detail);
        detailSpan.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.85em");
        span.add(labelSpan, value, new Span(" "), detailSpan);
        return span;
    }
}
