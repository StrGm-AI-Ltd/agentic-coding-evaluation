package com.agentbench.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.router.Route;

/** Preflight — the UI twin of GET/POST /api/preflight (positive control in the agent's environment). */
@Route(value = "preflight", layout = MainLayout.class)
public class PreflightView extends VerticalLayout {

    private final transient ServiceClient client;
    private Registration pollRegistration;

    public PreflightView(ServiceClient client) {
        this.client = client;
        setPadding(true);

        addAttachListener(event -> {
            pollRegistration = event.getUI().addPollListener(e -> render());
            render();
        });
        addDetachListener(event -> {
            if (pollRegistration != null) {
                pollRegistration.remove();
                pollRegistration = null;
            }
            event.getUI().setPollInterval(-1);
        });
    }

    private void render() {
        removeAll();

        Button start = new Button("Run preflight", e -> {
            try {
                client.startPreflight();
            } catch (Exception ignored) {
                // the fetch below surfaces the current state / error
            }
            render();
        });

        add(new H2("Preflight"));
        add(new Span("Runs a real gradle test on the positive control in the agent's environment before any run."));

        Api.PreflightState state;
        try {
            state = client.preflight();
        } catch (Exception e) {
            add(Panels.error(client.errorText(e)));
            return;
        }

        start.setEnabled(!state.running());
        add(start);

        HorizontalLayout statusLine = new HorizontalLayout(
                Badges.text(state.running() ? "running" : "idle",
                        state.running() ? Badges.PRIMARY : Badges.CONTRAST));
        statusLine.setPadding(false);
        statusLine.setSpacing(true);
        statusLine.getStyle().set("margin", "8px 0 0 0");
        statusLine.add(new Span(
                (state.started_at() == null ? "" : "started " + Fmt.when(state.started_at()))
                        + (state.finished_at() == null ? "" : " · finished " + Fmt.when(state.finished_at()))));
        add(statusLine);

        if (state.error() != null) {
            add(Panels.error(state.error()));
        }
        if (state.results() != null && !state.results().isNull() && !state.results().isMissingNode()) {
            H3 results = new H3("Results");
            results.getStyle().set("margin", "16px 0 4px 0");
            add(results);
            add(Panels.mono(Fmt.json(state.results())));
        }

        getUI().ifPresent(ui -> ui.setPollInterval(state.running() ? 2000 : -1));
    }
}
