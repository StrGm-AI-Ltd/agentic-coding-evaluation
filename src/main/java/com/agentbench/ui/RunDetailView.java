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

import java.util.ArrayList;
import java.util.List;

/** Run detail — the UI twin of GET /api/runs/{id}: scores, badges, checks, re-score. */
@Route(value = "runs/:runId", layout = MainLayout.class)
public class RunDetailView extends VerticalLayout implements BeforeEnterObserver {

    private final transient ServiceClient client;
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
            add(new H3("Run " + runId), Panels.error(client.errorText(e)));
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
            UnorderedList reasons = new UnorderedList();
            run.validity_reasons().forEach(reason -> reasons.add(new ListItem(reason)));
            VerticalLayout callout = new VerticalLayout(new Span("recorded, never ranked"), reasons);
            callout.setPadding(false);
            callout.setSpacing(false);
            callout.getStyle()
                    .set("border-left", "4px solid var(--lumo-error-color)")
                    .set("background", "var(--lumo-error-color-10pct)")
                    .set("padding", "8px 12px")
                    .set("border-radius", "4px")
                    .set("margin", "6px 0");
            add(callout);
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

        H4 checksTitle = new H4("Checks");
        checksTitle.getStyle().set("margin", "16px 0 4px 0");
        add(checksTitle);

        Grid<Api.Check> checks = new Grid<>(Api.Check.class, false);
        checks.addColumn(Api.Check::check_id).setHeader("id").setAutoWidth(true);
        checks.addColumn(Api.Check::category).setHeader("category").setAutoWidth(true);
        checks.addColumn(c -> Fmt.num(c.weight())).setHeader("weight").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        checks.addColumn(new ComponentRenderer<>(c -> Badges.status(c.status())))
                .setHeader("status").setAutoWidth(true);
        checks.addColumn(Api.Check::description).setHeader("check").setAutoWidth(true);
        checks.addColumn(Api.Check::detail).setHeader("detail").setFlexGrow(1);
        checks.setItems(run.checks() == null ? List.of() : run.checks());
        checks.setAllRowsVisible(true);
        add(checks);

        Anchor servicePage = new Anchor(client.baseUrl() + "/runs/" + runId, "open in the service UI");
        servicePage.getElement().setAttribute("target", "_blank");
        servicePage.getElement().setAttribute("rel", "noopener");
        servicePage.getStyle().set("display", "inline-block").set("margin-top", "16px");
        add(servicePage);

        add(new FilesBrowser(client, runId, run.results_dir()));
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
