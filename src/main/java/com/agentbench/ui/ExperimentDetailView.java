package com.agentbench.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Experiment detail — the UI twin of GET /api/experiments/{id}: params and its arm × repeat jobs. */
@Route(value = "experiments/:experimentId", layout = MainLayout.class)
public class ExperimentDetailView extends VerticalLayout implements BeforeEnterObserver {

    private final ServiceClient client;
    private long experimentId = -1;

    public ExperimentDetailView(ServiceClient client) {
        this.client = client;
        setPadding(true);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String raw = event.getRouteParameters().get("experimentId").orElse(null);
        try {
            experimentId = raw == null ? -1 : Long.parseLong(raw);
        } catch (NumberFormatException e) {
            experimentId = -1;
        }
        render();
    }

    private void render() {
        removeAll();
        if (experimentId < 0) {
            add(new H3("Experiment"), Panels.error("No valid experiment id in the URL."));
            return;
        }
        Api.Experiment experiment;
        try {
            experiment = client.experiment(experimentId);
        } catch (Exception e) {
            add(new H3("Experiment #" + experimentId), Panels.error(client.errorText(e)));
            return;
        }

        add(new RouterLink("← Experiments", ExperimentsView.class));

        H1 title = new H1(experiment.name());
        title.getStyle().set("margin", "4px 0").set("font-size", "1.6em");
        add(title);

        HorizontalLayout statusLine = new HorizontalLayout(Badges.status(experiment.status()));
        statusLine.setPadding(false);
        statusLine.setSpacing(true);
        statusLine.getStyle().set("margin", "4px 0");
        statusLine.add(new Span(metaLine(experiment)));
        add(statusLine);

        add(new Span("Parameters"));
        add(Panels.mono(Fmt.json(experiment.params())));

        List<Api.ExperimentJob> jobs = experiment.jobs() == null ? List.of() : experiment.jobs();
        Map<Long, String> blockedReasons = blockedReasons(client, jobs);
        long blockedCount = jobs.stream().filter(j -> "blocked".equals(j.status())).count();
        if (blockedCount > 0) {
            VerticalLayout blockedPanel = new VerticalLayout();
            blockedPanel.setPadding(false);
            blockedPanel.setSpacing(false);
            Span blockedLine = new Span(blockedCount + " blocked job" + (blockedCount == 1 ? "" : "s")
                    + (blockedReasons.isEmpty() ? "" : " — " + String.join("\n", new java.util.LinkedHashSet<>(blockedReasons.values()))));
            Button requeueAll = new Button("Requeue all blocked", e -> {
                String failures = requeueAllBlocked(client, jobs);
                if (failures == null) {
                    Notification.show("Requeued " + blockedCount + " blocked job"
                            + (blockedCount == 1 ? "" : "s"), 3000, Notification.Position.BOTTOM_END);
                } else {
                    Notification.show(failures, 8000, Notification.Position.BOTTOM_END);
                }
                render();
            });
            blockedPanel.add(Panels.callout("var(--lumo-warning-color)", "var(--lumo-warning-color-10pct)",
                            blockedLine, requeueAll));
            add(blockedPanel);
        }

        H3 jobsTitle = new H3("Jobs (" + jobs.size() + ")");
        jobsTitle.getStyle().set("margin", "16px 0 4px 0");
        add(jobsTitle);

        Grid<Api.ExperimentJob> grid = new Grid<>(Api.ExperimentJob.class, false);
        grid.addColumn(Api.ExperimentJob::id).setHeader("#").setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        grid.addColumn(job -> job.arm() == null ? "–" : job.arm()).setHeader("arm").setAutoWidth(true);
        grid.addColumn(job -> job.repeat() == null ? "–" : "r" + job.repeat())
                .setHeader("repeat").setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(job -> Links.runLink(job.run_id())))
                .setHeader("run").setAutoWidth(true)
                .setSortable(true).setComparator(Comparator.comparing(Api.ExperimentJob::run_id));
        grid.addColumn(new ComponentRenderer<>(job -> statusBadge(job, blockedReasons)))
                .setHeader("status").setAutoWidth(true)
                .setSortable(true).setComparator(Comparator.comparing(Api.ExperimentJob::status));
        grid.addColumn(Api.ExperimentJob::result_line).setHeader("result").setFlexGrow(1);
        grid.setItems(jobs);
        grid.setAllRowsVisible(true);
        add(grid);
    }

    private static com.vaadin.flow.component.badge.Badge statusBadge(Api.ExperimentJob job,
            Map<Long, String> blockedReasons) {
        com.vaadin.flow.component.badge.Badge badge = Badges.status(job.status());
        String reason = blockedReasons.get(job.id());
        if (reason != null) {
            badge.getElement().setAttribute("title", reason);
        }
        return badge;
    }

    /** Fetches the blocked reason per blocked job (the experiments API does not carry it). */
    static Map<Long, String> blockedReasons(ServiceClient client, List<Api.ExperimentJob> jobs) {
        Map<Long, String> reasons = new java.util.LinkedHashMap<>();
        for (Api.ExperimentJob job : jobs) {
            if (!"blocked".equals(job.status())) {
                continue;
            }
            try {
                Api.Job full = client.job(job.id());
                if (full != null && full.blocked_reason() != null) {
                    reasons.put(job.id(), full.blocked_reason());
                }
            } catch (Exception ignored) {
                // the tooltip is simply absent for that job
            }
        }
        return reasons;
    }

    /** Requeues every blocked job; returns null on full success, or a description of the failures. */
    static String requeueAllBlocked(ServiceClient client, List<Api.ExperimentJob> jobs) {
        List<String> failures = new ArrayList<>();
        for (Api.ExperimentJob job : jobs) {
            if (!"blocked".equals(job.status())) {
                continue;
            }
            try {
                client.requeue(job.id());
            } catch (Exception e) {
                failures.add("job #" + job.id() + ": " + client.errorText(e));
            }
        }
        return failures.isEmpty() ? null : String.join("\n", failures);
    }

    private String metaLine(Api.Experiment experiment) {
        List<String> parts = new ArrayList<>();
        parts.add("#" + experiment.id());
        parts.add("tag " + experiment.tag());
        parts.add("template " + experiment.template());
        if (experiment.k() != null) parts.add("k=" + experiment.k());
        parts.add("created " + Fmt.when(experiment.created_at()));
        if (experiment.pinned_runner_sha() != null) parts.add("runner " + experiment.pinned_runner_sha());
        if (experiment.pinned_oracle_sha() != null) parts.add("oracle " + experiment.pinned_oracle_sha());
        return String.join(" · ", parts);
    }
}
