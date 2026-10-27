package com.strgmai.ace.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.router.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** A/B comparison — the UI twin of POST /api/compare (poolable runs only, like the service). */
@Route(value = "compare", layout = MainLayout.class)
public class CompareView extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(CompareView.class);

    private final ServiceClient client;

    private final MultiSelectComboBox<String> groupA = new MultiSelectComboBox<>("Group A run ids");
    private final MultiSelectComboBox<String> groupB = new MultiSelectComboBox<>("Group B run ids");
    private final Select<String> metric = new Select<>();
    private final Checkbox modelAb = new Checkbox("Compare two models");
    private final Checkbox allowPartial = new Checkbox("Allow partial runs");
    private final Checkbox includeInvalid = new Checkbox("Include invalid runs");
    private final Checkbox allowBudgetMismatch = new Checkbox("Allow budget mismatch");
    private final VerticalLayout result = new VerticalLayout();
    private final Div noPoolableRuns = Panels.warn("no poolable runs yet - run something first");

    // #339: a standalone, post-hoc "match tasks between these two completed runs" tool - a
    // genuinely different interaction from the group-vs-group stats comparator above (pick
    // exactly one run per side, not a group), so it gets its own single-run pickers rather than
    // reusing groupA/groupB. Not restricted to poolable runs - matching tasks by content needs
    // nothing from stats.py's own pooling rules.
    private final ComboBox<String> matchRunA = new ComboBox<>("Run A");
    private final ComboBox<String> matchRunB = new ComboBox<>("Run B");
    private final Grid<Api.TaskMatch> matchGrid = new Grid<>(Api.TaskMatch.class, false);
    private final VerticalLayout matchResult = new VerticalLayout();

    public CompareView(final ServiceClient client) {
        this.client = client;
        setPadding(true);

        add(TabManual.banner("Compare two runs or two models task-by-task.", "User-Manual-Results"));
        add(new H2("Compare — StatsService on two run groups"));

        groupA.setPlaceholder("pick one or more run ids");
        groupB.setPlaceholder("pick one or more run ids");
        groupA.setTooltipText(Tooltips.COMPARE_GROUP_A);
        groupB.setTooltipText(Tooltips.COMPARE_GROUP_B);

        metric.setLabel("Metric");
        metric.setItems("functional", "score", "agent_result");
        metric.setValue("functional");
        metric.setTooltipText(Tooltips.COMPARE_METRIC);
        modelAb.setTooltipText(Tooltips.COMPARE_MODEL_AB);
        allowPartial.setTooltipText(Tooltips.COMPARE_ALLOW_PARTIAL);
        includeInvalid.setTooltipText(Tooltips.COMPARE_INCLUDE_INVALID);
        allowBudgetMismatch.setTooltipText(Tooltips.COMPARE_ALLOW_BUDGET_MISMATCH);

        final var row1 = new HorizontalLayout(groupA, groupB, metric);
        row1.getStyle().set("flex-wrap", "wrap");

        final var options = new VerticalLayout(modelAb, allowPartial, includeInvalid, allowBudgetMismatch);
        options.setPadding(false);
        options.setSpacing(false);

        final var run = new Button("Compare", e -> compare());

        noPoolableRuns.setVisible(false);
        add(row1, noPoolableRuns, options, run, result);

        matchRunA.setPlaceholder("pick a run");
        matchRunB.setPlaceholder("pick a run");
        matchGrid.addColumn(Api.TaskMatch::aId).setHeader("A task").setAutoWidth(true).setFlexGrow(0);
        matchGrid.addColumn(Api.TaskMatch::aTitle).setHeader("A description").setFlexGrow(1);
        matchGrid.addColumn(m -> m.bId() == null ? "no match" : m.bId()).setHeader("B task").setAutoWidth(true).setFlexGrow(0);
        matchGrid.addColumn(m -> m.bTitle() == null ? "" : m.bTitle()).setHeader("B description").setFlexGrow(1);
        matchGrid.setVisible(false);
        matchGrid.setHeight("320px");
        final var matchButton = new Button("Match tasks", e -> matchTasks());
        final var matchRow = new HorizontalLayout(matchRunA, matchRunB, matchButton);
        matchRow.getStyle().set("flex-wrap", "wrap");
        matchResult.setPadding(false);
        add(new H3("Match tasks between two completed runs"), matchRow, matchResult);

        addAttachListener(e -> loadRunIds());
    }

    private void loadRunIds() {
        try {
            final var runs = client.runs(null, null, null, null, null);
            final var ids = poolableRunIds(runs);
            groupA.setItems(ids);
            groupB.setItems(ids);
            noPoolableRuns.setVisible(ids.isEmpty());
            // #339: every imported run, not just poolable ones - matching by content isn't a
            // statistical operation, so stats.py's own pooling rules don't apply here.
            final var allIds = runs.stream().map(Api.Run::run_id).sorted().distinct().toList();
            matchRunA.setItems(allIds);
            matchRunB.setItems(allIds);
        } catch (final Exception e) {
            log.warn("could not load run ids for compare: {}", e.toString());
            result.removeAll();
            result.add(Panels.error(client.errorText(e)));
        }
    }

    private void matchTasks() {
        matchResult.removeAll();
        final var a = matchRunA.getValue();
        final var b = matchRunB.getValue();
        if (a == null || b == null) {
            Notification.show("Pick a run for both A and B.", 3000, Notification.Position.BOTTOM_END);
            return;
        }
        try {
            final var response = client.matchTasks(a, b, new Api.MatchTasksRequest(null));
            matchGrid.setItems(response.matches());
            matchGrid.setVisible(!response.matches().isEmpty());
            if (response.matches().isEmpty()) matchResult.add(Panels.warn("no tasks found on one or both sides"));
            else matchResult.add(matchGrid);
        } catch (final Exception e) {
            log.warn("match-tasks request failed: {}", e.toString());
            matchResult.add(Panels.error(client.errorText(e)));
        }
    }

    /** Only poolable runs can be compared (stats.py pools them; the rest 404 — V-5). */
    static List<String> poolableRunIds(final List<Api.Run> runs) {
        return runs.stream().filter(Api.Run::poolable).map(Api.Run::run_id).sorted().distinct().toList();
    }

    private void compare() {
        result.removeAll();
        final var a = new ArrayList<>(groupA.getValue());
        final var b = new ArrayList<>(groupB.getValue());
        if (a.isEmpty() || b.isEmpty()) {
            Notification.show("Pick at least one run id on each side.", 3000, Notification.Position.BOTTOM_END);
            return;
        }
        final var request = new Api.CompareRequest(a, b, metric.getValue(), modelAb.getValue(),
                allowPartial.getValue(), includeInvalid.getValue(), allowBudgetMismatch.getValue());
        try {
            final var response = client.compare(request);
            if (response.refused() != null) {
                result.add(Panels.warn("StatsService refused: " + response.refused()));
                result.add(new Details("StatsService output", Panels.mono(response.printed())));
                return;
            }
            final var modelWarning = response.result().path("model_warning").asText(null);
            if (modelWarning != null) result.add(Panels.warn(modelWarning));
            result.add(new Details("StatsService output", Panels.mono(response.printed())));
            result.add(new Details("result JSON", Panels.mono(Fmt.json(response.result()))));
        } catch (final Exception e) {
            log.warn("compare request failed: {}", e.toString());
            result.add(Panels.error(client.errorText(e)));
        }
    }
}
