package com.strgmai.ace.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Preflight — the UI twin of GET /api/preflight (positive control in the agent's environment).
 *  #108: BenchController.preflight() is SYNCHRONOUS - one request runs every check (docker daemon,
 *  git, the pinned JDK, the model server) and returns the final result in the same call. There is
 *  no separate "start" call and nothing to poll for; the old async {running, started_at, ...}
 *  shape this view used to render was the pre-port Python/FastAPI service's contract, which this
 *  backend never implemented (its POST /api/preflight route does not exist here). */
@Route(value = "preflight", layout = MainLayout.class)
public class PreflightView extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(PreflightView.class);

    private final ServiceClient client;
    private Api.PreflightState state;
    private String error;
    private boolean running;

    public PreflightView(final ServiceClient client) {
        this.client = client;
        setPadding(true);
        addAttachListener(event -> render());
    }

    private void runPreflight() {
        running = true;
        render();
        try {
            state = client.preflight();
            error = null;
        } catch (final Exception e) {
            log.warn("preflight failed: {}", e.toString());
            error = client.errorText(e);
        } finally {
            running = false;
        }
        render();
    }

    private void render() {
        removeAll();

        final var start = new Button(running ? "Running…" : "Run preflight", VaadinIcon.PLAY.create(), e -> runPreflight());
        start.setEnabled(!running);

        add(new H2("Preflight"));
        add(new Span("Runs a real gradle test on the positive control in the agent's environment before any run."));
        add(start);

        if (error != null) {
            add(Panels.warn("Preflight failed: " + error));
        }
        if (state == null) {
            return;
        }

        final var statusLine = new HorizontalLayout(
                Badges.text(state.blocked() ? "blocked" : "runnable", state.blocked() ? Badges.CONTRAST : Badges.PRIMARY));
        statusLine.setPadding(false);
        statusLine.setSpacing(true);
        statusLine.getStyle().set("margin", "8px 0 0 0");
        add(statusLine);

        if (!state.checks().isEmpty()) {
            final var checksHeader = new H3("Checks");
            checksHeader.getStyle().set("margin", "16px 0 4px 0");
            add(checksHeader);
            for (final var check : state.checks()) {
                add(checkRow(check));
            }
        }
    }

    private Div checkRow(final Api.PreflightCheck check) {
        final var row = new Div();
        row.getStyle().set("margin", "4px 0");
        final var badge = Badges.text(check.ok() ? "ok" : check.fatal() ? "fatal" : "degraded",
                check.ok() ? Badges.PRIMARY : check.fatal() ? Badges.ERROR : Badges.CONTRAST);
        final var label = new Span(" " + check.check() + " — " + check.detail());
        row.add(badge, label);
        return row;
    }
}
