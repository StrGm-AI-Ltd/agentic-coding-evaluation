package com.agentbench.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.shared.Registration;
import org.springframework.web.client.RestClientResponseException;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;

import java.util.ArrayList;
import java.util.List;

/**
 * Job detail — the UI twin of the SSE live page: status, argv, result line, actions,
 * refreshed by UI polling of GET /api/jobs/{id} while the job is not terminal.
 * The fetch happens once per navigation (V-2); the attach listener only arms polling.
 */
@Route(value = "jobs/:jobId", layout = MainLayout.class)
public class JobDetailView extends VerticalLayout implements BeforeEnterObserver {

    private final ServiceClient client;
    private long jobId = -1;
    private String lastStatus;
    private Boolean runImported; // one probe per navigation (plus on terminal transition), cached across polls
    private Registration pollRegistration;

    public JobDetailView(ServiceClient client) {
        this.client = client;
        setPadding(true);

        addAttachListener(event -> {
            if (pollRegistration != null) { // V-7: a defensive guard against double attach
                pollRegistration.remove();
                pollRegistration = null;
            }
            UI ui = event.getUI();
            pollRegistration = ui.addPollListener(e -> poll());
            ui.setPollInterval(JobStatuses.isTerminal(status()) ? -1 : 2000);
        });
        addDetachListener(event -> {
            if (pollRegistration != null) {
                pollRegistration.remove();
                pollRegistration = null;
            }
            event.getUI().setPollInterval(-1);
        });
    }

    private String status() {
        return lastStatus == null ? "" : lastStatus;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String raw = event.getRouteParameters().get("jobId").orElse(null);
        try {
            jobId = raw == null ? -1 : Long.parseLong(raw);
        } catch (NumberFormatException e) {
            jobId = -1;
        }
        render();
    }

    private void poll() {
        if (jobId >= 0) {
            render();
        }
    }

    private void render() {
        removeAll();
        if (jobId < 0) {
            add(new H3("Job"), Panels.error("No valid job id in the URL."));
            return;
        }
        Api.Job job;
        try {
            job = client.job(jobId);
        } catch (Exception e) {
            add(new H3("Job #" + jobId), Panels.error(client.errorText(e)));
            return;
        }
        render(job);
    }

    private void render(Api.Job job) {
        boolean wasTerminal = JobStatuses.isTerminal(status());
        lastStatus = job.status();
        getUI().ifPresent(ui -> ui.setPollInterval(JobStatuses.isTerminal(job.status()) ? -1 : 2000));
        if (job.run_id() != null
                && (runImported == null || (JobStatuses.isTerminal(job.status()) && !wasTerminal))) {
            runImported = isRunImported(job.run_id());
        }

        add(new RouterLink("← Queue", JobsView.class));

        H1 title = new H1(job.run_id());
        title.getStyle().set("margin", "4px 0").set("font-size", "1.6em");
        add(title);

        HorizontalLayout statusLine = new HorizontalLayout(Badges.status(job.status()));
        statusLine.setPadding(false);
        statusLine.setSpacing(true);
        statusLine.getStyle().set("margin", "4px 0");
        Span kind = new Span("job #" + job.id() + " · " + job.kind());
        if (job.arm() != null) {
            kind.setText(kind.getText() + " · " + job.arm() + " r" + job.repeat());
        }
        statusLine.add(kind);
        add(statusLine);

        Span meta = new Span(metaLine(job));
        meta.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(meta);

        if (job.blocked_reason() != null) {
            add(Panels.warn(job.blocked_reason()));
        }
        if (job.cancel_requested() && !JobStatuses.isTerminal(job.status())) {
            add(new Span("Cancel requested; the runner will stop at the next step boundary."));
        }

        if (job.argv() != null && !job.argv().isEmpty()) {
            add(new Span("Command"));
            add(Panels.mono("python3 runner/run_bench.py " + String.join(" ", job.argv())));
        }

        if (job.result_line() != null && !job.result_line().isBlank()) {
            add(new Span("Result"));
            add(Panels.mono(job.result_line()));
        }

        HorizontalLayout actions = new HorizontalLayout();
        actions.setPadding(false);
        actions.setSpacing(true);
        actions.getStyle().set("margin-top", "8px");
        if (JobStatuses.canCancel(job.status(), job.cancel_requested())) {
            actions.add(new Button("Cancel", e -> {
                try {
                    client.cancel(job.id());
                    render();
                } catch (Exception ex) {
                    Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
                }
            }));
        }
        if (JobStatuses.canRequeue(job.status())) {
            actions.add(new Button("Requeue", e -> {
                try {
                    client.requeue(job.id());
                    render();
                } catch (Exception ex) {
                    Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
                }
            }));
        }
        if (job.run_id() != null && Boolean.TRUE.equals(runImported)) {
            actions.add(new Button("Open run detail", e ->
                    getUI().ifPresent(ui -> ui.navigate("runs/" + job.run_id()))));
        } else if (job.run_id() != null) {
            Span hint = new Span("run not imported yet — it appears here once the job finishes and is scored");
            hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.85em");
            actions.add(hint);
        }
        if (job.stdout_path() != null) {
            Anchor rawLog = new Anchor(client.baseUrl() + "/jobs/" + job.id() + "/log", "raw log");
            rawLog.getElement().setAttribute("target", "_blank");
            rawLog.getElement().setAttribute("rel", "noopener");
            actions.add(rawLog);
        }
        add(actions);
    }

    /** The runs table only holds imported runs: 404 means the job has not produced a scored result yet. */
    private boolean isRunImported(String runId) {
        try {
            client.run(runId);
            return true;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value() != 404;
        } catch (Exception e) {
            return false;
        }
    }

    private String metaLine(Api.Job job) {
        List<String> parts = new ArrayList<>();
        if (job.priority() != null) parts.add("priority " + job.priority());
        if (job.pid() != null) parts.add("pid " + job.pid());
        if (job.exit_code() != null) parts.add("exit " + job.exit_code());
        parts.add("enqueued " + Fmt.when(job.enqueued_at()));
        if (job.started_at() != null) parts.add("started " + Fmt.when(job.started_at()));
        if (job.finished_at() != null) parts.add("finished " + Fmt.when(job.finished_at()));
        return String.join(" · ", parts);
    }
}
