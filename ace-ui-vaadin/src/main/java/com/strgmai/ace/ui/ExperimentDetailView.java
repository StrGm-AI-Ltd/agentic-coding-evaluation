package com.strgmai.ace.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Experiment detail — the UI twin of GET /api/experiments/{id}: params and its arm × repeat jobs. */
@Route(value = "experiments/:experimentId", layout = MainLayout.class)
public class ExperimentDetailView extends VerticalLayout implements BeforeEnterObserver {
    private static final Logger log = LoggerFactory.getLogger(ExperimentDetailView.class);

    private final ServiceClient client;
    private String experimentId;

    public ExperimentDetailView(final ServiceClient client) {
        this.client = client;
        setPadding(true);
    }

    @Override
    public void beforeEnter(final BeforeEnterEvent event) {
        final var raw = event.getRouteParameters().get("experimentId").orElse(null);
        experimentId = (raw == null || raw.isBlank()) ? null : raw;
        render();
    }

    private void render() {
        removeAll();
        if (experimentId == null) {
            add(new com.vaadin.flow.component.html.H2("Experiment"), Panels.error("No valid experiment id in the URL."));
            return;
        }
        final Api.Experiment experiment;
        try {
            experiment = client.experiment(experimentId);
        } catch (final Exception e) {
            log.warn("could not load experiment {}: {}", experimentId, e.toString());
            add(new com.vaadin.flow.component.html.H2("Experiment #" + experimentId), Panels.error(client.errorText(e)));
            return;
        }

        add(new RouterLink("← Experiments", ExperimentsView.class));

        final var title = new com.vaadin.flow.component.html.H2(experiment.name());
        title.getStyle().set("margin", "4px 0").set("font-size", "1.6em");
        add(title);

        // ternary combines List.of() (unconstrained) with List<Api.ExperimentJob>: without a var's
        // target type this infers to something other than List<Api.ExperimentJob> - keep explicit
        final List<Api.ExperimentJob> jobs = experiment.jobs() == null ? List.of() : experiment.jobs();
        final var effectiveStatus = ExperimentStatuses.effective(experiment.status(),
                jobs.stream().map(Api.ExperimentJob::status).toList());
        final var statusLine = new HorizontalLayout(Badges.status(effectiveStatus));
        statusLine.setPadding(false);
        statusLine.setSpacing(true);
        statusLine.getStyle().set("margin", "4px 0");
        if (!java.util.Objects.equals(effectiveStatus, experiment.status())) {
            final var raw = new Span("(table status: " + experiment.status() + " — derived from its jobs)");
            raw.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.85em");
            statusLine.add(raw);
        }
        statusLine.add(new Span(metaLine(experiment)));
        add(statusLine);

        add(new Span("Parameters"));
        add(Panels.mono(Fmt.json(experiment.params())));

        final var blockedReasons = blockedReasons(client, jobs);
        final var blockedCount = jobs.stream().filter(j -> "blocked".equals(j.status())).count();
        if (blockedCount > 0) {
            final var blockedPanel = new VerticalLayout();
            blockedPanel.setPadding(false);
            blockedPanel.setSpacing(false);
            final var blockedLine = new Span(blockedCount + " blocked job" + (blockedCount == 1 ? "" : "s")
                    + (blockedReasons.isEmpty() ? "" : " — " + String.join("\n", new java.util.LinkedHashSet<>(blockedReasons.values()))));
            // browsers collapse \n in inline text; pre-line renders each reason on its own line
            blockedLine.getStyle().set("white-space", "pre-line");
            final var requeueAll = new Button("Requeue all blocked", e -> {
                final var failures = requeueAllBlocked(client, jobs);
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

        add(Panels.sectionTitle("Jobs (" + jobs.size() + ")"));

        final var grid = new Grid<>(Api.ExperimentJob.class, false);
        grid.addColumn(Api.ExperimentJob::id).setHeader("#").setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        grid.addColumn(job -> job.arm() == null ? "–" : job.arm()).setHeader("arm").setAutoWidth(true);
        grid.addColumn(job -> job.repeat() == null ? "–" : "r" + job.repeat())
                .setHeader("repeat").setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(job -> Links.runToJobLink(job.run_id(), job.id())))
                .setHeader("run").setAutoWidth(true)
                .setSortable(true).setComparator(Fmt.nullsLast(Api.ExperimentJob::run_id));
        grid.addColumn(new ComponentRenderer<>(job -> statusBadge(job, blockedReasons)))
                .setHeader("status").setAutoWidth(true)
                .setSortable(true).setComparator(Fmt.nullsLast(Api.ExperimentJob::status));
        grid.addColumn(Api.ExperimentJob::result_line).setHeader("result").setFlexGrow(1);
        grid.setItems(jobs);
        grid.setAllRowsVisible(true);
        add(grid);

        addComparison(experiment);
    }

    private static com.vaadin.flow.component.badge.Badge statusBadge(final Api.ExperimentJob job,
            final Map<String, String> blockedReasons) {
        final var badge = Badges.status(job.status());
        final var reason = blockedReasons.get(job.id());
        if (reason != null) {
            badge.getElement().setAttribute("title", reason);
        }
        return badge;
    }

    /**
     * Fetches the blocked reason per blocked job (the experiments API does not carry it).
     * The per-job lookups run in parallel (the shared RestClient is thread-safe) so a large
     * arm × repeat grid does not cost one sequential network round-trip per blocked job.
     */
    static Map<String, String> blockedReasons(final ServiceClient client, final List<Api.ExperimentJob> jobs) {
        // empty-diamond new LinkedHashMap<>() has no target type under var - would infer
        // <Object, Object> and fail to compile against the declared Map<String, String> return
        final Map<String, String> reasons = new java.util.LinkedHashMap<>();
        jobs.parallelStream()
                .filter(job -> "blocked".equals(job.status()))
                .map(job -> {
                    try {
                        final var full = client.job(job.id());
                        if (full != null && full.blocked_reason() != null) {
                            return Map.entry(job.id(), full.blocked_reason());
                        }
                    } catch (final Exception e) {
                        // the tooltip is simply absent for that job
                        log.warn("could not load blocked reason for job {}: {}", job.id(), e.toString());
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .forEach(entry -> reasons.put(entry.getKey(), entry.getValue()));
        return reasons;
    }

    /** Requeues every blocked job; returns null on full success, or a description of the failures. */
    static String requeueAllBlocked(final ServiceClient client, final List<Api.ExperimentJob> jobs) {
        // same empty-diamond-under-var trap as above (String.join needs Iterable<? extends CharSequence>)
        final List<String> failures = new ArrayList<>();
        for (final var job : jobs) {
            if (!"blocked".equals(job.status())) {
                continue;
            }
            try {
                client.requeue(job.id());
            } catch (final Exception e) {
                log.warn("could not requeue blocked job {}: {}", job.id(), e.toString());
                failures.add("job #" + job.id() + ": " + client.errorText(e));
            }
        }
        return failures.isEmpty() ? null : String.join("\n", failures);
    }

    /** One comparison verdict card, as rendered from experiments.comparison (M3). */
    record ComparisonCard(String label, String calloutKind, String calloutText, String printed) {
    }

    /**
     * Extracts the verdict cards from the experiment's comparison JSON — the twin of
     * experiment_detail.html's Comparison section: error/refused callouts, the
     * A − B diff · 90 % CI · p-value · supported/not-supported verdict, or the empty case.
     */
    static List<ComparisonCard> comparisonCards(final JsonNode comparison) {
        // returned as List<ComparisonCard>; empty-diamond under var would infer <Object>
        final List<ComparisonCard> cards = new ArrayList<>();
        if (comparison == null || !comparison.isObject()) {
            return cards;
        }
        comparison.propertyNames().stream().sorted().forEach(label -> {
            final var c = comparison.get(label);
            final var title = label.replace("_vs_", " vs ");
            final var printed = Fmt.textOr(c.path("printed"), null);

            final var error = c.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                cards.add(new ComparisonCard(title, "warn", Fmt.textOr(error, ""), printed));
                return;
            }
            final var refused = c.path("refused");
            if (!refused.isMissingNode() && !refused.isNull()) {
                cards.add(new ComparisonCard(title, "error", "StatsService refused: " + Fmt.textOr(refused, ""), printed));
                return;
            }
            final var cmp = c.path("result").path("compare");
            if (cmp.isObject()) {
                final var diff = cmp.path("diff").asDouble(0);
                final var p = cmp.path("p").asDouble(1);
                final var text = "A − B = " + Fmt.num(diff) + " " + Fmt.textOr(cmp.path("metric"), "")
                        + " points · 90% CI [" + Fmt.num(cmp.path("ci90").path(0).asDouble())
                        + ", " + Fmt.num(cmp.path("ci90").path(1).asDouble()) + "] · p = "
                        + String.format(Locale.ROOT, "%.3f", p) + " · "
                        + (p < 0.10 ? "supported (p < 0.10)" : "not supported at α = 0.10");
                cards.add(new ComparisonCard(title, p < 0.10 ? "good" : "muted", text, printed));
                return;
            }
            cards.add(new ComparisonCard(title, "warn",
                    "No comparison: one side had no comparable runs after exclusions.", printed));
        });
        return cards;
    }

    private void addComparison(final Api.Experiment experiment) {
        add(Panels.sectionTitle("Comparison"));
        if (!"finished".equals(experiment.status())) {
            final var note = new Span("Computed automatically once every job above is terminal.");
            note.getStyle().set("color", "var(--lumo-secondary-text-color)");
            add(note);
            return;
        }
        final var cards = comparisonCards(experiment.comparison());
        if (cards.isEmpty()) {
            final var note = new Span("No comparison recorded.");
            note.getStyle().set("color", "var(--lumo-secondary-text-color)");
            add(note);
            return;
        }
        for (final var card : cards) {
            final var cardLayout = new VerticalLayout();
            cardLayout.setPadding(false);
            cardLayout.setSpacing(true);
            cardLayout.getStyle()
                    .set("border", "1px solid var(--lumo-contrast-20pct)")
                    .set("border-radius", "8px")
                    .set("padding", "12px 16px")
                    .set("margin", "6px 0");
            final var header = new Span(card.label());
            header.getStyle().set("font-weight", "600");
            cardLayout.add(header);
            switch (card.calloutKind()) {
                case "good" -> cardLayout.add(Panels.callout("var(--lumo-success-color)",
                        "var(--lumo-success-color-10pct)", new Span(card.calloutText())));
                case "error" -> cardLayout.add(Panels.error(card.calloutText()));
                case "warn" -> cardLayout.add(Panels.warn(card.calloutText()));
                default -> cardLayout.add(new Span(card.calloutText()));
            }
            if (card.printed() != null && !card.printed().isBlank()) {
                cardLayout.add(new com.vaadin.flow.component.details.Details("StatsService output",
                        Panels.mono(card.printed())));
            }
            add(cardLayout);
        }
    }

    private String metaLine(final Api.Experiment experiment) {
        // returned via String.join, which needs Iterable<? extends CharSequence> - empty-diamond
        // under var would infer List<Object> and fail to compile there
        final List<String> parts = new ArrayList<>();
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
