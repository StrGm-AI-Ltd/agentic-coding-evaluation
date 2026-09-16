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

import java.util.Comparator;
import java.util.List;

/** Runs list with server-side filters — the UI twin of GET /api/runs. */
@Route(value = "runs", layout = MainLayout.class)
public class RunsView extends VerticalLayout {

    private final ServiceClient client;

    private final Grid<Api.Run> grid = new Grid<>(Api.Run.class, false);
    private final ComboBox<String> task = new ComboBox<>("task");
    private final ComboBox<String> model = new ComboBox<>("model");
    private final ComboBox<String> mode = new ComboBox<>("mode");
    private final Select<String> valid = new Select<>();
    private final Select<String> poolable = new Select<>();
    private final Span error = new Span();
    private final Span emptyState = new Span();
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
                Notification.show("Imported " + result.imported().size() + " new runs, skipped "
                        + result.skipped().size(), 4000, Notification.Position.BOTTOM_END);
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
        emptyState.getStyle().set("color", "var(--lumo-secondary-text-color)");
        emptyState.setVisible(false);

        grid.addColumn(new ComponentRenderer<>(run -> Links.runLink(run.run_id())))
                .setHeader("run").setAutoWidth(true).setFlexGrow(0).setKey("run")
                .setSortable(true).setComparator(Comparator.comparing(Api.Run::run_id));
        grid.addColumn(Api.Run::task).setHeader("task").setAutoWidth(true);
        grid.addColumn(Api.Run::model).setHeader("model").setAutoWidth(true);
        grid.addColumn(Api.Run::mode).setHeader("mode").setAutoWidth(true);
        grid.addColumn(functionalCell()).setHeader("functional %").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true)
                .setSortable(true)
                .setComparator(Fmt.nullsLast(Api.Run::functional_score_pct));
        grid.addColumn(this::compositeCell).setHeader("composite %").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true)
                .setComparator(Fmt.nullsLast(RunsView::effectiveScore));
        grid.addColumn(new ComponentRenderer<>(this::validBadge)).setHeader("valid").setAutoWidth(true)
                .setSortable(true)
                .setComparator(Fmt.nullsLast(Api.Run::valid));
        grid.addColumn(r -> Fmt.duration(r.wall_sec())).setHeader("wall").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true)
                .setComparator(Fmt.nullsLast(Api.Run::wall_sec));
        grid.addColumn(r -> Fmt.count(r.completion_tokens())).setHeader("tokens").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true)
                .setComparator(Fmt.nullsLast(Api.Run::completion_tokens));
        grid.addColumn(r -> Fmt.when(r.started())).setHeader("started").setAutoWidth(true)
                .setComparator(Fmt.comparingTime(Api.Run::started));
        grid.addItemClickListener(e -> {
            if ("run".equals(e.getColumn() == null ? null : e.getColumn().getKey())) {
                return; // the run link already navigates — no double fetch
            }
            e.getSource().getUI().ifPresent(ui -> ui.navigate("runs/" + e.getItem().run_id()));
        });

        add(new H2("Runs"), filters, error, emptyState, grid);
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
            emptyState.setVisible(false);
            return;
        }

        // V-6: suggestions always come from the full unfiltered list (like the service's
        // filter_options), never from the current filtered result set.
        if (!optionsLoaded) {
            List<Api.Run> all = noFiltersSet() ? runs : client.runs(null, null, null, null, null);
            task.setItems(Links.distinctRuns(all, Api.Run::task));
            model.setItems(Links.distinctRuns(all, Api.Run::model));
            mode.setItems(Links.distinctRuns(all, Api.Run::mode));
            optionsLoaded = true;
        }
        grid.setItems(runs);
        emptyState.setText(noFiltersSet()
                ? "No runs yet — press “Rescan results/” after the first run finishes."
                : "No runs match the filters.");
        emptyState.setVisible(runs.isEmpty());
    }

    private boolean noFiltersSet() {
        return isBlank(task.getValue()) && isBlank(model.getValue()) && isBlank(mode.getValue())
                && isBlank(valid.getValue()) && isBlank(poolable.getValue());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** The sortable value behind the composite % cell: weighted when present, else partial. */
    static Double effectiveScore(Api.Run run) {
        return run.weighted_score_pct() != null ? run.weighted_score_pct() : run.partial_score_pct();
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
