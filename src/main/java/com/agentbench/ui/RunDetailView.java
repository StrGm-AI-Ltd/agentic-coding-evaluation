package com.agentbench.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
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
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Run detail — the UI twin of GET /api/runs/{id}: scores, badges, checks, plan tasks,
 * per-step scores, provenance, re-score, and the embedded file browser. */
@Route(value = "runs/:runId", layout = MainLayout.class)
public class RunDetailView extends VerticalLayout implements BeforeEnterObserver {

    private static final List<String> PROVENANCE_KEYS = List.of(
            "model", "quantization", "harness", "harness_version", "harness_sha",
            "oracle_sha", "omlx_version", "java_version");

    private final ServiceClient client;
    private String runId;

    public RunDetailView(ServiceClient client) {
        this.client = client;
        setPadding(true);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        runId = event.getRouteParameters().get("runId").orElse(null);
        render();
    }

    private void render() {
        removeAll();
        if (runId == null) {
            add(new H3("Run"), Panels.error("No run id in the URL."));
            return;
        }
        Api.Run run;
        try {
            run = client.run(runId);
        } catch (Exception e) {
            if (isNotImported(e)) {
                addNotImportedPanel();
            } else {
                add(new H3("Run " + runId), Panels.error(client.errorText(e)));
            }
            return;
        }
        render(run);
    }

    private void render(Api.Run run) {
        add(new RouterLink("← Runs", RunsView.class));

        H1 title = new H1(run.run_id());
        title.getStyle().set("margin", "4px 0").set("font-size", "1.6em");
        add(title);

        Span meta = new Span(metaLine(run));
        meta.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(meta);

        HorizontalLayout badges = new HorizontalLayout();
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

        HorizontalLayout scores = new HorizontalLayout();
        scores.setPadding(false);
        scores.setSpacing(true);
        String functionalDetail = Fmt.points(run.functional_points_got(), run.functional_denominator())
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
            List<ListItem> reasons = run.validity_reasons().stream().map(ListItem::new).toList();
            add(Panels.callout("var(--lumo-error-color)", "var(--lumo-error-color-10pct)",
                    new Span("recorded, never ranked"), new UnorderedList(reasons.toArray(new ListItem[0]))));
        }
        if (Boolean.TRUE.equals(run.contended()) && run.manifest() != null) {
            String contention = run.manifest().has("contention")
                    ? run.manifest().get("contention").toString() : "";
            add(Panels.warn("CONTENDED: excluded from leaderboards. " + contention));
        }

        if (!run.poolable()) {
            Button rescore = new Button("Queue re-score", e -> {
                try {
                    Api.Job job = client.rescore(runId);
                    Notification.show("Queued re-score job #" + job.id(), 3000, Notification.Position.BOTTOM_END);
                    getUI().ifPresent(ui -> ui.navigate("jobs"));
                } catch (Exception ex) {
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
        addProvenance(run);

        Anchor servicePage = new Anchor(client.baseUrl() + "/runs/" + runId, "open in the service UI");
        servicePage.getElement().setAttribute("target", "_blank");
        servicePage.getElement().setAttribute("rel", "noopener");
        servicePage.getStyle().set("display", "inline-block").set("margin-top", "16px");
        add(servicePage);

        add(new FilesBrowser(client, runId, run.results_dir()));
    }

    /** A 404 on the run id — the job has not produced a scored, imported result yet. */
    static boolean isNotImported(Exception e) {
        return e instanceof RestClientResponseException responseException
                && responseException.getStatusCode().value() == 404;
    }

    /** The self-service path: not-imported is expected, not an error — offer the rescan. */
    private void addNotImportedPanel() {
        add(new H3("Run " + runId));
        add(Panels.warn("This run is not imported yet. A run appears here once its job finishes "
                + "with a scored result (oracle.json on disk); cancelled or unfinished runs never import."));
        add(new Button("Rescan results/", e -> {
            try {
                client.importAll();
                render();
            } catch (Exception ex) {
                Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
            }
        }));
    }

    private void addChecks(List<Api.Check> checks) {
        add(sectionTitle("Checks"));
        Grid<Api.Check> grid = new Grid<>(Api.Check.class, false);
        grid.addColumn(Api.Check::check_id).setHeader("id").setAutoWidth(true);
        grid.addColumn(Api.Check::category).setHeader("category").setAutoWidth(true);
        grid.addColumn(c -> Fmt.num(c.weight())).setHeader("weight").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(c -> Badges.status(c.status())))
                .setHeader("status").setAutoWidth(true);
        grid.addColumn(Api.Check::description).setHeader("check").setAutoWidth(true);
        grid.addColumn(Api.Check::detail).setHeader("detail").setFlexGrow(1);
        grid.setItems(checks == null ? List.of() : checks);
        grid.setAllRowsVisible(true);
        add(grid);
    }

    private record PerTaskRow(String tid, String reported, String doneVerified, Long requests,
            Long tokens, Long maxPrompt, Double wall, String filesChanged, String overBudget) {
    }

    /** The metrics.per_task table from the Jinja2 run page (V-4). */
    private void addPlanTasks(JsonNode metrics) {
        JsonNode perTask = metrics == null ? null : metrics.get("per_task");
        if (perTask == null || !perTask.isObject() || perTask.isEmpty()) {
            return;
        }
        add(sectionTitle("Plan tasks"));
        List<PerTaskRow> rows = new ArrayList<>();
        perTask.propertyNames().stream().sorted().forEach(tid -> {
            JsonNode t = perTask.get(tid);
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
        Grid<PerTaskRow> grid = new Grid<>(PerTaskRow.class, false);
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

    private record StepRow(String sid, Double scorePct, boolean measured) {
    }

    /** The metrics.steps table from the Jinja2 run page (V-4). */
    private void addStepScores(JsonNode metrics) {
        JsonNode steps = metrics == null ? null : metrics.get("steps");
        if (steps == null || !steps.isObject() || steps.isEmpty()) {
            return;
        }
        add(sectionTitle("Per-step scores"));
        List<StepRow> rows = new ArrayList<>();
        steps.propertyNames().stream().sorted().forEach(sid -> {
            JsonNode s = steps.get(sid);
            rows.add(new StepRow(sid, doubleOrNull(s.path("score_pct")), s.path("measured").asBoolean(true)));
        });
        Grid<StepRow> grid = new Grid<>(StepRow.class, false);
        grid.addColumn(StepRow::sid).setHeader("step").setAutoWidth(true);
        grid.addColumn(r -> Fmt.pct(r.scorePct())).setHeader("score %").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(r -> r.measured() ? "" : "not measured").setHeader("").setAutoWidth(true);
        grid.setItems(rows);
        grid.setAllRowsVisible(true);
        add(grid);
    }

    /** The provenance block from the Jinja2 run page (V-4) — values may be objects
     * (e.g. quantization is an 11-property map in real manifests), so never asText() them. */
    private void addProvenance(Api.Run run) {
        add(sectionTitle("Provenance"));
        VerticalLayout provenance = new VerticalLayout();
        provenance.setPadding(false);
        provenance.setSpacing(false);
        for (String line : provenanceLines(run.manifest(), run.results_dir())) {
            Span span = new Span(line);
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
    static List<String> provenanceLines(JsonNode manifest, String resultsDir) {
        List<String> lines = new ArrayList<>();
        JsonNode prov = manifest == null ? null : manifest.get("provenance");
        if (prov != null && prov.isObject()) {
            for (String key : PROVENANCE_KEYS) {
                JsonNode value = prov.get(key);
                if (value == null || value.isNull() || value.isMissingNode()) {
                    continue;
                }
                lines.add(key + ": " + (value.isContainer()
                        ? Fmt.json(value) : Fmt.textOr(value, "")));
            }
        }
        if (manifest != null && manifest.hasNonNull("usable_context")) {
            JsonNode usableContext = manifest.get("usable_context");
            lines.add("usable_context: " + (usableContext.isNumber()
                    ? Fmt.count(usableContext.longValue()) : Fmt.textOr(usableContext, "–")));
        }
        if (resultsDir != null && !resultsDir.isBlank()) {
            lines.add("results: " + resultsDir);
        }
        return lines;
    }

    private static H4 sectionTitle(String title) {
        H4 header = new H4(title);
        header.getStyle().set("margin", "16px 0 4px 0");
        return header;
    }

    private static Long longOrNull(JsonNode node) {
        return node.isNumber() ? node.longValue() : null;
    }

    private static Double doubleOrNull(JsonNode node) {
        return node.isNumber() ? node.doubleValue() : null;
    }

    private String metaLine(Api.Run run) {
        List<String> parts = new ArrayList<>();
        if (run.task() != null) parts.add(run.task());
        if (run.mode() != null) parts.add(run.mode());
        if (run.model() != null) parts.add(run.model());
        if (run.harness() != null) parts.add("harness " + run.harness());
        parts.add("schema " + run.schema_version());
        parts.add("wall " + Fmt.duration(run.wall_sec()));
        parts.add("tokens " + Fmt.count(run.completion_tokens()));
        return String.join(" · ", parts);
    }

    private Span score(String label, String pct, String detail) {
        Span span = new Span();
        Span value = new Span(pct);
        value.getStyle().set("font-size", "1.3em").set("font-weight", "600");
        Span labelSpan = new Span(label + " ");
        labelSpan.getStyle().set("color", "var(--lumo-secondary-text-color)");
        Span detailSpan = new Span(detail);
        detailSpan.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.85em");
        span.add(labelSpan, value, new Span(" "), detailSpan);
        return span;
    }
}
