package com.agentbench.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Experiments list — the UI twin of GET /api/experiments (+ /api/jobs for the live status). */
@Route(value = "experiments", layout = MainLayout.class)
public class ExperimentsView extends VerticalLayout {

    private final ServiceClient client;

    private final Grid<Api.Experiment> grid = new Grid<>(Api.Experiment.class, false);
    private final Map<Long, List<String>> jobStatusesByExperiment = new HashMap<>();
    private final Span error = new Span();
    private final Span emptyState = new Span();

    public ExperimentsView(ServiceClient client) {
        this.client = client;
        setPadding(true);

        Button newExperiment = new Button("New experiment", com.vaadin.flow.component.icon.VaadinIcon.PLUS.create(),
                e -> getUI().ifPresent(ui -> ui.navigate("experiments/new")));
        error.getStyle().set("color", "var(--lumo-error-color)");
        emptyState.getStyle().set("color", "var(--lumo-secondary-text-color)");
        emptyState.setVisible(false);

        grid.addColumn(Api.Experiment::id).setHeader("#").setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        grid.addColumn(Api.Experiment::name).setHeader("name").setAutoWidth(true);
        grid.addColumn(Api.Experiment::tag).setHeader("tag").setAutoWidth(true);
        grid.addColumn(Api.Experiment::template).setHeader("template").setAutoWidth(true);
        grid.addColumn(exp -> "k=" + exp.k()).setHeader("k").setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(this::statusBadge)).setHeader("status").setAutoWidth(true)
                .setSortable(true).setComparator(Fmt.nullsLast(Api.Experiment::status));
        grid.addColumn(exp -> Fmt.when(exp.created_at())).setHeader("created").setAutoWidth(true)
                .setComparator(Fmt.comparingTime(Api.Experiment::created_at));
        grid.addItemClickListener(e -> e.getSource().getUI()
                .ifPresent(ui -> ui.navigate("experiments/" + e.getItem().id())));

        add(new H2("Experiments"),
                new com.vaadin.flow.component.orderedlayout.HorizontalLayout(newExperiment, error),
                emptyState, grid);
        setSizeFull();
        expand(grid);

        addAttachListener(e -> load());
    }

    /**
     * The table status stays 'queued' mid-flight (the service never sets 'running' on
     * experiments) — derive it from the jobs, with the raw value as the tooltip.
     */
    private com.vaadin.flow.component.badge.Badge statusBadge(Api.Experiment experiment) {
        String effective = ExperimentStatuses.effective(experiment.status(),
                jobStatusesByExperiment.get(experiment.id()));
        com.vaadin.flow.component.badge.Badge badge = Badges.status(effective);
        if (!effective.equals(experiment.status())) {
            badge.getElement().setAttribute("title",
                    "table status: " + experiment.status() + " (derived from its jobs)");
        }
        return badge;
    }

    private void load() {
        try {
            List<Api.Experiment> experiments = client.experiments();
            jobStatusesByExperiment.clear();
            try {
                for (Api.Job job : client.jobs()) {
                    if (job.experiment_id() != null) {
                        jobStatusesByExperiment
                                .computeIfAbsent(job.experiment_id(), id -> new ArrayList<>())
                                .add(job.status());
                    }
                }
            } catch (Exception ignored) {
                // without the jobs list, the table status is shown as-is
            }
            grid.setItems(experiments);
            error.setText("");
            emptyState.setText("No experiments yet — queue one with “New experiment”.");
            emptyState.setVisible(experiments.isEmpty());
        } catch (Exception ex) {
            grid.setItems(List.of());
            error.setText(client.errorText(ex));
            emptyState.setVisible(false);
        }
    }
}
