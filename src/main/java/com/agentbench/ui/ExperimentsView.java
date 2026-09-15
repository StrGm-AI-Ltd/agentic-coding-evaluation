package com.agentbench.ui;

import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;

import java.util.List;

/** Experiments list — the UI twin of GET /api/experiments. */
@Route(value = "experiments", layout = MainLayout.class)
public class ExperimentsView extends VerticalLayout {

    private final transient ServiceClient client;

    public ExperimentsView(ServiceClient client) {
        this.client = client;
        setPadding(true);

        Grid<Api.Experiment> grid = new Grid<>(Api.Experiment.class, false);
        grid.addColumn(Api.Experiment::id).setHeader("#").setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        grid.addColumn(Api.Experiment::name).setHeader("name").setAutoWidth(true);
        grid.addColumn(Api.Experiment::tag).setHeader("tag").setAutoWidth(true);
        grid.addColumn(Api.Experiment::template).setHeader("template").setAutoWidth(true);
        grid.addColumn(exp -> "k=" + exp.k()).setHeader("k").setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(exp -> Badges.status(exp.status())))
                .setHeader("status").setAutoWidth(true);
        grid.addColumn(exp -> Fmt.when(exp.created_at())).setHeader("created").setAutoWidth(true);
        grid.addItemClickListener(e -> e.getSource().getUI()
                .ifPresent(ui -> ui.navigate("experiments/" + e.getItem().id())));

        Span error = new Span();
        error.getStyle().set("color", "var(--lumo-error-color)");

        Button newExperiment = new Button("New experiment", com.vaadin.flow.component.icon.VaadinIcon.PLUS.create(),
                e -> getUI().ifPresent(ui -> ui.navigate("experiments/new")));

        add(new H2("Experiments"), new com.vaadin.flow.component.orderedlayout.HorizontalLayout(newExperiment, error), grid);
        setSizeFull();
        expand(grid);

        addAttachListener(e -> {
            try {
                grid.setItems(client.experiments());
                error.setText("");
            } catch (Exception ex) {
                grid.setItems(List.of());
                error.setText(client.errorText(ex));
            }
        });
    }
}
