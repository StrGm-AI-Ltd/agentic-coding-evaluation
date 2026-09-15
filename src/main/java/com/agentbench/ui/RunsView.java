package com.agentbench.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;

import java.util.List;
import java.util.Objects;

/** Runs list with server-side filters — the UI twin of GET /api/runs. */
@Route(value = "runs", layout = MainLayout.class)
public class RunsView extends VerticalLayout {

    private final transient ServiceClient client;

    private final Grid<Api.Run> grid = new Grid<>(Api.Run.class, false);
    private final ComboBox<String> task = new ComboBox<>("task");
    private final ComboBox<String> model = new ComboBox<>("model");
    private final ComboBox<String> mode = new ComboBox<>("mode");
    private final Select<String> valid = new Select<>();
    private final Select<String> poolable = new Select<>();
    private final Span error = new Span();
    private boolean optionsLoaded;

    public RunsView(ServiceClient client) {
        this.client = client;
        setPadding(true);

        for (ComboBox<String> box : List.of(task, model, mode)) {
            box.setPlaceholder("any");
            box.setClearButtonVisible(true);
            box.addValueChangeListener(e -> load());
        }
        for (Select<String> select : List.of(valid, poolable)) {
            select.setItems("", "true", "false");
            select.setItemLabelGenerator(value -> value.isBlank() ? "any" : value);
            select.setValue("");
            select.addValueChangeListener(e -> load());
        }
        valid.setLabel("valid");
        poolable.setLabel("poolable");

        Button rescan = new Button("Rescan results/", VaadinIcon.UPLOAD.create(), e -> {
            try {
                Api.ImportResult result = client.importAll();
                Notification.show("Imported " + result.imported() + " new runs, skipped "
                        + result.skipped(), 4000, Notification.Position.BOTTOM_END);
                optionsLoaded = false;
                load();
            } catch (Exception ex) {
                notifyError(client.errorText(ex));
            }
        });

        Button refresh = new Button(VaadinIcon.REFRESH.create(), e -> load());
        refresh.getElement().setAttribute("title", "Reload the list");

        HorizontalLayout filters = new HorizontalLayout(task, model, mode, valid, poolable, rescan, refresh);
        filters.setDefaultVerticalComponentAlignment(Alignment.END);
        filters.getStyle().set("flex-wrap", "wrap");

        error.getStyle().set("color", "var(--lumo-error-color)");

        grid.addColumn(new ComponentRenderer<>(run -> runLink(run.run_id())))
                .setHeader("run").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(Api.Run::task).setHeader("task").setAutoWidth(true);
        grid.addColumn(Api.Run::model).setHeader("model").setAutoWidth(true);
        grid.addColumn(Api.Run::mode).setHeader("mode").setAutoWidth(true);
        grid.addColumn(functionalCell()).setHeader("functional %").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(this::compositeCell).setHeader("composite %").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(this::validBadge)).setHeader("valid").setAutoWidth(true);        grid.addColumn(r -> Fmt.duration(r.wall_sec())).setHeader("wall").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(r -> Fmt.count(r.completion_tokens())).setHeader("tokens").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(r -> Fmt.when(r.started())).setHeader("started").setAutoWidth(true);
        grid.addItemClickListener(e -> e.getSource().getUI()
                .ifPresent(ui -> ui.navigate("runs/" + e.getItem().run_id())));

        add(new H2("Runs"), filters, error, grid);
        setSizeFull();
        expand(grid);

        addAttachListener(e -> load());
    }

    private void load() {
        List<Api.Run> runs;
        try {
            runs = client.runs(task.getValue(), model.getValue(), mode.getValue(),
                    valid.getValue(), poolable.getValue());
            error.setText("");
        } catch (Exception e) {
            grid.setItems(List.of());
            error.setText(client.errorText(e));
            return;
        }

        if (!optionsLoaded) {
            task.setItems(distinct(runs, Api.Run::task));
            model.setItems(distinct(runs, Api.Run::model));
            mode.setItems(distinct(runs, Api.Run::mode));
            optionsLoaded = true;
        }
        grid.setItems(runs);
    }

    static RouterLink runLink(String runId) {
        RouterLink link = new RouterLink();
        link.add(runId);
        link.setRoute(RunDetailView.class, new com.vaadin.flow.router.RouteParameters("runId", runId));
        return link;
    }

    static RouterLink jobLink(String jobId) {
        RouterLink link = new RouterLink();
        link.add(jobId);
        link.setRoute(JobDetailView.class, new com.vaadin.flow.router.RouteParameters("jobId", jobId));
        return link;
    }

    static RouterLink experimentLink(Long experimentId) {
        RouterLink link = new RouterLink();
        link.add(String.valueOf(experimentId));
        link.setRoute(ExperimentDetailView.class,
                new com.vaadin.flow.router.RouteParameters("experimentId", String.valueOf(experimentId)));
        return link;
    }

    private void notifyError(String text) {
        Notification notification = Notification.show(text, 6000, Notification.Position.BOTTOM_END);
        notification.addThemeName("error");
    }

    private String compositeCell(Api.Run run) {
        if (run.weighted_score_pct() != null) {
            return Fmt.pct(run.weighted_score_pct());
        }
        return run.partial_score_pct() != null ? "partial " + Fmt.pct(run.partial_score_pct()) : "–";
    }

    private com.vaadin.flow.component.badge.Badge validBadge(Api.Run run) {
        if (run.valid() == null) {
            return Badges.text("unknown", Badges.CONTRAST);
        }
        return run.valid() ? Badges.text("valid", Badges.SUCCESS) : Badges.text("INVALID", Badges.ERROR);
    }

    static List<String> distinct(List<Api.Run> runs, java.util.function.Function<Api.Run, String> getter) {
        return runs.stream().map(getter).filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static ComponentRenderer<Span, Api.Run> functionalCell() {
        return new ComponentRenderer<>(run -> {
            Span span = new Span(Fmt.pct(run.functional_score_pct()));
            if (run.functional_ids() != null && !run.functional_ids().isEmpty()) {
                span.getElement().setAttribute("title", String.join(", ", run.functional_ids()));
            }
            return span;
        });
    }
}
