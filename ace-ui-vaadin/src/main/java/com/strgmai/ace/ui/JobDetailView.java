package com.strgmai.ace.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.shared.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Job detail — the Vaadin twin of the Jinja2 SSE live page. The page chrome is built
 * once per navigation; the 2 s poll of GET /api/jobs/{id} and the /jobs/{id}/events
 * SSE stream (flushed via @Push) update individual fields, so live updates never
 * rebuild the DOM, steal focus, or lose text selection.
 */
@Route(value = "jobs/:jobId", layout = MainLayout.class)
public class JobDetailView extends VerticalLayout implements BeforeEnterObserver {
    private static final Logger log = LoggerFactory.getLogger(JobDetailView.class);

    private final ServiceClient client;
    private String jobId;
    private Api.Job currentJob;
    private String lastStatus;
    private Boolean runImported; // one probe per navigation (plus on terminal transition), cached across polls
    private transient Registration pollRegistration;
    private transient Thread sseThread;
    private volatile boolean sseStopped;
    private final JobLiveState live = new JobLiveState();

    /** Built once so the user's column sorting survives the 2 s live updates. */
    private final Grid<JobLiveState.RequestRow> requestsGrid = buildRequestsGrid();
    private final Grid<JobLiveState.SessionRow> sessionsGrid = buildSessionsGrid();

    // The live-requests sort keys — typed and null-safe (the ClassCastException regression).
    static final Comparator<JobLiveState.RequestRow> REQUESTS_BY_TS =
            Fmt.comparingTime(JobLiveState.RequestRow::ts);
    static final Comparator<JobLiveState.RequestRow> REQUESTS_BY_STATUS =
            Fmt.nullsLast(JobLiveState.RequestRow::status);
    static final Comparator<JobLiveState.RequestRow> REQUESTS_BY_LATENCY =
            Fmt.nullsLast(JobLiveState.RequestRow::latencySec);
    static final Comparator<JobLiveState.RequestRow> REQUESTS_BY_TTFT =
            Fmt.nullsLast(JobLiveState.RequestRow::ttftSec);
    static final Comparator<JobLiveState.RequestRow> REQUESTS_BY_TOKENS =
            Fmt.nullsLast(JobLiveState.RequestRow::tokens);

    // Stable field references, updated in place.
    private final Span kindLine = new Span();
    private final Span metaLine = new Span();
    private final Div statusHolder = new Div();
    private final Div blockedHolder = new Div();
    private final Div actionsHolder = new Div();
    private final VerticalLayout liveSection = new VerticalLayout();
    private final Span stepLine = new Span();
    private final Span requestsLine = new Span();
    private final Div logTail = new Div();
    private final Span terminalNote = new Span("This job has finished; nothing more to stream.");
    private final Span lostNotice = new Span("live connection lost — status still updates via polling");
    private final Span errorLine = new Span();
    private boolean chromeBuilt;
    private String lastActionsSignature;
    private String lastBlockedReason;

    public JobDetailView(final ServiceClient client) {
        this.client = client;
        setPadding(true);

        addAttachListener(event -> {
            if (pollRegistration != null) { // V-7: a defensive guard against double attach
                pollRegistration.remove();
                pollRegistration = null;
            }
            final var ui = event.getUI();
            pollRegistration = ui.addPollListener(e -> refresh());
            ui.setPollInterval(JobStatuses.isTerminal(status()) ? -1 : 2000);
            if (jobId != null && !JobStatuses.isTerminal(status())) {
                ensureSse(ui);
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
    public void beforeEnter(final BeforeEnterEvent event) {
        final var raw = event.getRouteParameters().get("jobId").orElse(null);
        jobId = (raw == null || raw.isBlank()) ? null : raw;
        if (jobId == null) {
            add(new H3("Job"), Panels.error("No valid job id in the URL."));
            chromeBuilt = true; // nothing else to build
            return;
        }
        try {
            final var job = client.job(jobId);
            buildChrome(job);   // once per navigation
            update(job);
        } catch (final Exception e) {
            log.warn("could not load job {}: {}", jobId, e.toString());
            add(new H3("Job #" + jobId), Panels.error(client.errorText(e)));
            chromeBuilt = true;
        }
    }

    /** The static chrome, built once — every later cycle only updates fields. */
    private void buildChrome(final Api.Job job) {
        if (chromeBuilt) {
            return;
        }
        chromeBuilt = true;

        add(new RouterLink("← Queue", JobsView.class));

        final var title = new H2(job.run_id());
        title.getStyle().set("margin", "4px 0").set("font-size", "1.6em");
        add(title);

        statusHolder.getStyle().set("margin", "4px 0");
        add(statusHolder);
        kindLine.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(kindLine);
        metaLine.getStyle().set("color", "var(--lumo-secondary-text-color)");
        add(metaLine);
        add(blockedHolder);
        add(errorLine);
        errorLine.getStyle().set("color", "var(--lumo-error-color)").set("font-size", "0.85em");

        if (job.argv() != null && !job.argv().isEmpty()) {
            add(new Span("Run flags"));
            add(Panels.mono(String.join(" ", job.argv())));
        }

        // Urgent actions stay above the live panel, reachable as the run grows (m10).
        actionsHolder.getStyle().set("margin-top", "8px");
        add(actionsHolder);

        liveSection.setPadding(false);
        liveSection.setSpacing(false);
        liveSection.getStyle().set("margin-top", "16px");
        final var liveTitle = new H3("Live");
        liveTitle.getStyle().set("margin", "0 0 4px 0");
        liveSection.add(liveTitle, stepLine, sessionsGrid, requestsLine, lostNotice, terminalNote,
                requestsGrid, logTail);
        stepLine.getStyle().set("font-weight", "600");
        lostNotice.getStyle().set("color", "var(--lumo-warning-text-color, orange)").set("font-size", "0.85em");
        terminalNote.getStyle().set("color", "var(--lumo-secondary-text-color)");
        logTail.getStyle().set("font-family", "ui-monospace, 'SF Mono', Menlo, monospace")
                .set("font-size", "12px")
                .set("background", "var(--lumo-contrast-10pct)")
                .set("padding", "12px")
                .set("border-radius", "6px")
                .set("overflow-x", "auto")
                .set("white-space", "pre-wrap")
                .set("margin", "6px 0 0 0");
        add(liveSection);
    }

    /** Poll cycle: fetch the job row and update fields in place. */
    private void refresh() {
        if (jobId == null || !chromeBuilt) {
            return;
        }
        try {
            update(client.job(jobId));
            errorLine.setText("");
        } catch (final Exception e) {
            // transient fetch errors never kill the SSE loop (m5); the next poll retries
            log.debug("poll fetch failed for job {}: {}", jobId, e.toString());
            errorLine.setText(client.errorText(e));
        }
    }

    private void update(final Api.Job job) {
        final var previousStatus = lastStatus;
        currentJob = job;
        lastStatus = job.status();
        final var terminal = JobStatuses.isTerminal(job.status());

        getUI().ifPresent(ui -> {
            ui.setPollInterval(terminal ? -1 : 2000);
            if (terminal) {
                stopSse();
            } else {
                ensureSse(ui); // m5: restart a dead loop (transient failures) instead of staying dark
            }
        });
        if (job.run_id() != null && shouldProbeRun(runImported, previousStatus, job.status())) {
            runImported = isRunImported(client, job.run_id());
        }

        updateStatus(job);
        updateActions(job);
        updateLive();

        if (job.blocked_reason() == null || job.blocked_reason().isBlank()) {
            blockedHolder.removeAll();
            lastBlockedReason = null;
        } else if (!job.blocked_reason().equals(lastBlockedReason)) {
            blockedHolder.removeAll();
            blockedHolder.add(Panels.warn(job.blocked_reason()));
            lastBlockedReason = job.blocked_reason();
        }
    }

    private void updateStatus(final Api.Job job) {
        statusHolder.removeAll();
        final var line = new HorizontalLayout(Badges.status(job.status()));
        line.setPadding(false);
        line.setSpacing(true);
        if (job.cancel_requested() && !JobStatuses.isTerminal(job.status())) {
            line.add(new Span("cancel requested — stops at the next step boundary"));
        }
        kindLine.setText("job #" + job.id() + " · " + job.kind()
                + (job.arm() != null ? " · " + job.arm() + " r" + job.repeat() : ""));
        statusHolder.add(line);
        metaLine.setText(metaLine(job));
    }

    /** Rebuilt only when the offered actions change — focus is preserved between cycles. */
    private void updateActions(final Api.Job job) {
        final var signature = job.status() + "|" + job.cancel_requested() + "|" + runImported
                + "|" + (job.stdout_path() != null) + "|" + (job.run_id() != null);
        if (signature.equals(lastActionsSignature)) {
            return;
        }
        lastActionsSignature = signature;

        final var actions = new HorizontalLayout();
        actions.setPadding(false);
        actions.setSpacing(true);
        if (JobStatuses.canCancel(job.status(), job.cancel_requested())) {
            actions.add(new Button("Cancel", e -> {
                try {
                    client.cancel(job.id());
                    refresh();
                } catch (final Exception ex) {
                    log.warn("could not cancel job {}: {}", job.id(), ex.toString());
                    Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
                }
            }));
        }
        if (JobStatuses.canRequeue(job.status())) {
            actions.add(new Button("Requeue", e -> {
                try {
                    client.requeue(job.id());
                    refresh();
                } catch (final Exception ex) {
                    log.warn("could not requeue job {}: {}", job.id(), ex.toString());
                    Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
                }
            }));
        }
        if (job.run_id() != null && Boolean.TRUE.equals(runImported)) {
            actions.add(new Button("Open run detail", e ->
                    getUI().ifPresent(ui -> ui.navigate("runs/" + job.run_id()))));
        } else if (job.run_id() != null) {
            final var hint = new Span(runNotImportedHint(job.status()));
            hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.85em");
            actions.add(hint);
        }
        if (job.stdout_path() != null) {
            final var rawLog = new Anchor(client.baseUrl() + "/jobs/" + job.id() + "/log", "raw log");
            rawLog.getElement().setAttribute("target", "_blank");
            rawLog.getElement().setAttribute("rel", "noopener noreferrer");
            actions.add(rawLog);
        }
        actionsHolder.removeAll();
        actionsHolder.add(actions);
    }

    private void updateLive() {
        final var terminal = currentJob != null && JobStatuses.isTerminal(currentJob.status());
        lostNotice.setVisible(sseThread != null && !sseThread.isAlive()
                && !terminal && !sseStopped);
        terminalNote.setVisible(terminal);
        stepLine.setVisible(!terminal);
        sessionsGrid.setVisible(!terminal);
        requestsLine.setVisible(!terminal);

        if (terminal) {
            final var result = currentJob.result_line();
            if (result != null && !result.isBlank()) {
                logTail.setText(result);
                logTail.setVisible(true);
            } else {
                logTail.setVisible(false);
            }
            requestsGrid.setVisible(false);
            return;
        }

        stepLine.setText("current step: " + (live.currentStep() == null ? "–" : live.currentStep()));
        sessionsGrid.setItems(live.sessions());
        requestsLine.setText("requests: " + (live.requestCount() == 0 ? "–"
                : live.requestCount() + (live.lastTokens() == null ? "" : " · last completion tokens "
                + Fmt.count(live.lastTokens()))));

        final var recent = live.recentRequests();
        requestsGrid.setVisible(!recent.isEmpty());
        requestsGrid.setItems(recent);

        final var tail = live.logTail() != null ? live.logTail()
                : (currentJob != null ? currentJob.result_line() : null);
        logTail.setVisible(tail != null && !tail.isBlank());
        logTail.setText(tail == null ? "" : tail);
    }

    /** The SSE loop: events mutate the live state and update the fields under the session lock. */
    private void ensureSse(final UI ui) {
        if (sseThread != null && sseThread.isAlive()) {
            return;
        }
        sseStopped = false;
        final var loop = new JobEventLoop(client, jobId, () -> sseStopped,
                event -> ui.access(() -> {   // M1: apply + update share the session lock
                    live.apply(event);
                    updateLive();
                }),
                () -> ui.access(this::updateLive)); // give-up → show the lost notice
        sseThread = new Thread(loop, "job-" + jobId + "-live");
        sseThread.setDaemon(true);
        sseThread.start();
    }

    private void stopSse() {
        sseStopped = true; // the consumer aborts on the next delivered event (~2 s)
    }

    private static Grid<JobLiveState.RequestRow> buildRequestsGrid() {
        final var requests = new Grid<>(JobLiveState.RequestRow.class, false);
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
        requests.setVisible(false);
        return requests;
    }

    /** #47: the stage a session ended at, alongside its label - a session that outlives its own
     *  stage (still "running") shows that explicitly rather than leaving the reader to guess. */
    private static Grid<JobLiveState.SessionRow> buildSessionsGrid() {
        final var sessions = new Grid<>(JobLiveState.SessionRow.class, false);
        sessions.addColumn(JobLiveState.SessionRow::label).setHeader("session").setAutoWidth(true);
        sessions.addColumn(r -> r.endedStage() == null ? "running" : r.endedStage())
                .setHeader("ended at").setAutoWidth(true);
        sessions.setAllRowsVisible(true);
        sessions.setVisible(false);
        return sessions;
    }

    /**
     * Why there is no run link, in the job's own terms: in progress, blocked, ended
     * without a score, or (rarely) succeeded but failed to import.
     */
    static String runNotImportedHint(final String status) {
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
    static boolean isRunImported(final ServiceClient client, final String runId) {
        try {
            client.run(runId);
            return true;
        } catch (final RestClientResponseException e) {
            // a non-404 status means the run likely exists but the server errored reading it -
            // show the link anyway; the run-detail page surfaces the real error when clicked
            return e.getStatusCode().value() != 404;
        } catch (final Exception e) {
            // connectivity trouble reads as "not imported" (hides the link) rather than a wrong
            // link - a real, previously invisible false negative, so at least leave a trace
            log.warn("could not probe import status for run {}: {}", runId, e.toString());
            return false;
        }
    }

    /**
     * Probe once per navigation, and again on the transition into a terminal state —
     * a job that just finished may have had its run imported by the worker.
     */
    static boolean shouldProbeRun(final Boolean probed, final String previousStatus, final String currentStatus) {
        if (probed == null) {
            return true;
        }
        return JobStatuses.isTerminal(currentStatus) && !JobStatuses.isTerminal(previousStatus == null ? "" : previousStatus);
    }

    private String metaLine(final Api.Job job) {
        // returned via String.join, which needs Iterable<? extends CharSequence> - empty-diamond
        // under var would infer List<Object> and fail to compile there
        final List<String> parts = new ArrayList<>();
        if (job.priority() != null) parts.add("priority " + job.priority());
        if (job.pid() != null) parts.add("pid " + job.pid());
        if (job.exit_code() != null) parts.add("exit " + job.exit_code());
        parts.add("enqueued " + Fmt.when(job.enqueued_at()));
        if (job.started_at() != null) parts.add("started " + Fmt.when(job.started_at()));
        if (job.finished_at() != null) parts.add("finished " + Fmt.when(job.finished_at()));
        return String.join(" · ", parts);
    }
}
