package com.agentbench.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.shared.Registration;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Job detail — the Vaadin twin of the Jinja2 SSE live page. Two transports, like the
 * original: a 2 s poll of GET /api/jobs/{id} drives status/actions, and the same
 * /jobs/{id}/events SSE stream the old page's EventSource used feeds the live panel
 * (steps, sessions, request stats, log tail) through @Push.
 */
@Route(value = "jobs/:jobId", layout = MainLayout.class)
public class JobDetailView extends VerticalLayout implements BeforeEnterObserver {

    private final ServiceClient client;
    private long jobId = -1;
    private String lastStatus;
    private Boolean runImported; // one probe per navigation (plus on terminal transition), cached across polls
    private transient Registration pollRegistration;
    private transient Thread sseThread;
    private volatile boolean sseStopped;
    private final JobLiveState live = new JobLiveState();
    /** Built once so the user's column sorting survives the 2 s live re-renders. */
    private final Grid<JobLiveState.RequestRow> requestsGrid = buildRequestsGrid();

    // The live-requests sort keys — typed and null-safe (the ClassCastException regression).
    static final java.util.Comparator<JobLiveState.RequestRow> REQUESTS_BY_TS =
            Fmt.comparingTime(JobLiveState.RequestRow::ts);
    static final java.util.Comparator<JobLiveState.RequestRow> REQUESTS_BY_STATUS =
            Fmt.nullsLast(JobLiveState.RequestRow::status);
    static final java.util.Comparator<JobLiveState.RequestRow> REQUESTS_BY_LATENCY =
            Fmt.nullsLast(JobLiveState.RequestRow::latencySec);
    static final java.util.Comparator<JobLiveState.RequestRow> REQUESTS_BY_TTFT =
            Fmt.nullsLast(JobLiveState.RequestRow::ttftSec);
    static final java.util.Comparator<JobLiveState.RequestRow> REQUESTS_BY_TOKENS =
            Fmt.nullsLast(JobLiveState.RequestRow::tokens);

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
            if (jobId >= 0 && !JobStatuses.isTerminal(status())) {
                startSse(ui);
            }
        });
        addDetachListener(event -> {
            if (pollRegistration != null) {
                pollRegistration.remove();
                pollRegistration = null;
            }
            event.getUI().setPollInterval(-1);
            stopSse();
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

    /** The SSE loop: streams until the job ends; a dropped connection retries a few times. */
    private void startSse(UI ui) {
        if (sseThread != null && sseThread.isAlive()) {
            return;
        }
        sseStopped = false;
        sseThread = new Thread(() -> {
            int attempts = 0;
            while (!sseStopped && attempts <= 3) {
                try {
                    client.streamJobEvents(jobId, event -> {
                        if (sseStopped) {
                            throw new IllegalStateException("view detached");
                        }
                        live.apply(event);
                        ui.access(this::render); // @Push flushes it instantly
                    });
                    return; // the server ends the stream when the job is terminal
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    if (sseStopped) {
                        return;
                    }
                    attempts += 1;
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "job-" + jobId + "-live");
        sseThread.setDaemon(true);
        sseThread.start();
    }

    private void stopSse() {
        sseStopped = true; // the consumer aborts on the next delivered event (~2 s)
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
            stopSse();
            add(new H3("Job #" + jobId), Panels.error(client.errorText(e)));
            return;
        }
        render(job);
    }

    private void render(Api.Job job) {
        String previousStatus = lastStatus;
        lastStatus = job.status();
        boolean terminal = JobStatuses.isTerminal(job.status());
        getUI().ifPresent(ui -> {
            ui.setPollInterval(terminal ? -1 : 2000);
            if (terminal) {
                stopSse();
            }
        });
        if (job.run_id() != null && shouldProbeRun(runImported, previousStatus, job.status())) {
            runImported = isRunImported(client, job.run_id());
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
        if (job.cancel_requested() && !terminal) {
            add(new Span("Cancel requested; the runner will stop at the next step boundary."));
        }

        if (job.argv() != null && !job.argv().isEmpty()) {
            add(new Span("Command"));
            add(Panels.mono("python3 runner/run_bench.py " + String.join(" ", job.argv())));
        }

        addLiveSection(job);
        addActions(job);
    }

    /** The live panel, as in the Jinja page: steps, sessions, requests, log tail. */
    private void addLiveSection(Api.Job job) {
        boolean terminal = JobStatuses.isTerminal(job.status());

        H4 liveTitle = new H4("Live");
        liveTitle.getStyle().set("margin", "16px 0 4px 0");
        add(liveTitle);

        if (terminal) {
            add(new Span("This job has finished; nothing more to stream."));
            if (job.result_line() != null && !job.result_line().isBlank()) {
                add(Panels.mono(job.result_line()));
            }
            return;
        }

        Span step = new Span("current step: " + (live.currentStep() == null ? "–" : live.currentStep()));
        step.getStyle().set("font-weight", "600");
        add(step);
        add(kvLine("sessions", live.sessions().isEmpty() ? "–" : String.join(", ", live.sessions())));
        add(kvLine("requests", live.requestCount() == 0 ? "–"
                : live.requestCount() + (live.lastTokens() == null ? "" : " · last completion tokens "
                + Fmt.count(live.lastTokens()))));

        List<JobLiveState.RequestRow> recent = live.recentRequests();
        if (!recent.isEmpty()) {
            requestsGrid.setItems(recent);
            add(requestsGrid);
        }

        String tail = live.logTail() != null ? live.logTail()
                : (job.result_line() == null ? null : job.result_line());
        if (tail != null && !tail.isBlank()) {
            add(kvLine("log tail", ""));
            add(Panels.mono(tail));
        }
    }

    private static Grid<JobLiveState.RequestRow> buildRequestsGrid() {
        Grid<JobLiveState.RequestRow> requests = new Grid<>(JobLiveState.RequestRow.class, false);
        requests.addColumn(r -> Fmt.when(r.ts())).setHeader("ts").setAutoWidth(true)
                .setComparator(REQUESTS_BY_TS);
        requests.addColumn(r -> r.status() == null ? "–" : r.status()).setHeader("status").setAutoWidth(true)
                .setComparator(REQUESTS_BY_STATUS);
        requests.addColumn(r -> Fmt.num(r.latencySec())).setHeader("latency").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true)
                .setComparator(REQUESTS_BY_LATENCY);
        requests.addColumn(r -> Fmt.num(r.ttftSec())).setHeader("ttft").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true)
                .setComparator(REQUESTS_BY_TTFT);
        requests.addColumn(r -> Fmt.count(r.tokens())).setHeader("tokens").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true)
                .setComparator(REQUESTS_BY_TOKENS);
        requests.addColumn(r -> r.clientAborted() ? "yes" : "").setHeader("aborted").setAutoWidth(true);
        requests.setAllRowsVisible(true);
        return requests;
    }

    private static Span kvLine(String key, String value) {
        Span span = new Span();
        Span keySpan = new Span(key + ": ");
        keySpan.getStyle().set("color", "var(--lumo-secondary-text-color)");
        span.add(keySpan, new Span(value));
        return span;
    }

    private void addActions(Api.Job job) {
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
            Span hint = new Span(runNotImportedHint(job.status()));
            hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.85em");
            actions.add(hint);
        }
        if (job.stdout_path() != null) {
            Anchor rawLog = new Anchor(client.baseUrl() + "/jobs/" + job.id() + "/log", "raw log");
            rawLog.getElement().setAttribute("target", "_blank");
            rawLog.getElement().setAttribute("rel", "noopener noreferrer");
            actions.add(rawLog);
        }
        add(actions);
    }

    /**
     * Why there is no run link, in the job's own terms: in progress, ended without
     * a score, or (rarely) succeeded but failed to import.
     */
    static String runNotImportedHint(String status) {
        return switch (status == null ? "" : status) {
            case "queued", "waiting_lock", "running" ->
                    "the run is still in progress — its results appear here automatically "
                    + "when the job finishes with a score";
            case "blocked" ->
                    "this job is blocked — requeue it (queue or experiment page); its results "
                    + "appear once it finishes with a score";
            case "failed", "cancelled" ->
                    "this job ended without a scored result — such runs are never imported";
            case "succeeded" ->
                    "the job succeeded but its results were not imported — a Rescan (Runs page) "
                    + "may pick them up";
            default -> "the run is not imported yet";
        };
    }

    /** The runs table only holds imported runs: 404 means the job has not produced a scored result yet. */
    static boolean isRunImported(ServiceClient client, String runId) {
        try {
            client.run(runId);
            return true;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value() != 404;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Probe once per navigation, and again on the transition into a terminal state —
     * a job that just finished may have had its run imported by the worker.
     */
    static boolean shouldProbeRun(Boolean probed, String previousStatus, String currentStatus) {
        if (probed == null) {
            return true;
        }
        return JobStatuses.isTerminal(currentStatus) && !JobStatuses.isTerminal(previousStatus == null ? "" : previousStatus);
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
