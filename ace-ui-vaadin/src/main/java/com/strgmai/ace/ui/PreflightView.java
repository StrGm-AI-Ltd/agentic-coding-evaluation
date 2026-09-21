package com.strgmai.ace.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.router.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Preflight — the UI twin of GET/POST /api/preflight (positive control in the agent's environment). */
@Route(value = "preflight", layout = MainLayout.class)
public class PreflightView extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(PreflightView.class);

    private final ServiceClient client;
    private String startError;
    private Registration pollRegistration;

    public PreflightView(final ServiceClient client) {
        this.client = client;
        setPadding(true);

        addAttachListener(event -> {
            if (pollRegistration != null) { // V-7: a defensive guard against double attach
                pollRegistration.remove();
                pollRegistration = null;
            }
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

        final var start = new Button("Run preflight", e -> {
            try {
                client.startPreflight();
                startError = null;
            } catch (final Exception ex) {
                startError = client.errorText(ex); // C-10: never swallow the start failure silently
            }
            render();
        });

        add(new H2("Preflight"));
        add(new Span("Runs a real gradle test on the positive control in the agent's environment before any run."));

        if (startError != null) {
            add(Panels.warn("Starting the preflight failed: " + startError));
        }

        final Api.PreflightState state;   // assigned exactly once below; a legal blank final
        try {
            state = client.preflight();
        } catch (final Exception e) {
            log.debug("poll fetch failed for preflight state: {}", e.toString());
            add(Panels.error(client.errorText(e)));
            // the early return would otherwise leave the previous interval in place; back off
            // to a slower cadence so a downed service is not hammered, but auto-recovery works
            getUI().ifPresent(ui -> ui.setPollInterval(10_000));
            return;
        }

        start.setEnabled(!state.running());
        add(start);

        final var statusLine = new HorizontalLayout(
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
            final var results = new H3("Results");
            results.getStyle().set("margin", "16px 0 4px 0");
            add(results);
            add(Panels.mono(Fmt.json(state.results())));
        }

        getUI().ifPresent(ui -> ui.setPollInterval(state.running() ? 2000 : -1));
    }
}
