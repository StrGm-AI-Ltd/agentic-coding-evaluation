package com.strgmai.ace.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.router.Route;

import java.util.ArrayList;
import java.util.List;

/** A/B comparison — the UI twin of POST /api/compare (poolable runs only, like the service). */
@Route(value = "compare", layout = MainLayout.class)
public class CompareView extends VerticalLayout {

    private final ServiceClient client;

    private final MultiSelectComboBox<String> groupA = new MultiSelectComboBox<>("Group A run ids");
    private final MultiSelectComboBox<String> groupB = new MultiSelectComboBox<>("Group B run ids");
    private final Select<String> metric = new Select<>();
    private final Checkbox modelAb = new Checkbox("model_ab — the groups are two models, not two configs");
    private final Checkbox allowPartial = new Checkbox("allow partial runs");
    private final Checkbox includeInvalid = new Checkbox("include invalid runs");
    private final Checkbox allowBudgetMismatch = new Checkbox("allow budget mismatch");
    private final VerticalLayout result = new VerticalLayout();

    public CompareView(final ServiceClient client) {
        this.client = client;
        setPadding(true);

        add(new H2("Compare — stats.py on two run groups"));

        groupA.setPlaceholder("pick one or more run ids");
        groupB.setPlaceholder("pick one or more run ids");

        metric.setLabel("metric");
        metric.setItems("functional", "score", "agent_result");
        metric.setValue("functional");

        final var row1 = new HorizontalLayout(groupA, groupB, metric);
        row1.getStyle().set("flex-wrap", "wrap");

        final var options = new VerticalLayout(modelAb, allowPartial, includeInvalid, allowBudgetMismatch);
        options.setPadding(false);
        options.setSpacing(false);

        final var run = new Button("Compare", e -> compare());

        add(row1, options, run, result);

        addAttachListener(e -> loadRunIds());
    }

    private void loadRunIds() {
        try {
            final var runs = client.runs(null, null, null, null, null);
            final var ids = poolableRunIds(runs);
            groupA.setItems(ids);
            groupB.setItems(ids);
        } catch (final Exception e) {
            result.removeAll();
            result.add(Panels.error(client.errorText(e)));
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
                result.add(Panels.warn("stats.py refused: " + response.refused()));
                result.add(new Details("stats.py output", Panels.mono(response.printed())));
                return;
            }
            result.add(new Details("stats.py output", Panels.mono(response.printed())));
            result.add(new Details("result JSON", Panels.mono(Fmt.json(response.result()))));
        } catch (final Exception e) {
            result.add(Panels.error(client.errorText(e)));
        }
    }
}
