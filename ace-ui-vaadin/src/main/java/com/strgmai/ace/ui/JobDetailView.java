package com.strgmai.ace.ui;

import com.github.appreciated.apexcharts.ApexCharts;
import com.github.appreciated.apexcharts.ApexChartsBuilder;
import com.github.appreciated.apexcharts.config.builder.ChartBuilder;
import com.github.appreciated.apexcharts.config.builder.SeriesBuilder;
import com.github.appreciated.apexcharts.config.builder.ThemeBuilder;
import com.github.appreciated.apexcharts.config.builder.XAxisBuilder;
import com.github.appreciated.apexcharts.config.builder.YAxisBuilder;
import com.github.appreciated.apexcharts.config.chart.Type;
import com.github.appreciated.apexcharts.config.theme.Mode;
import com.github.appreciated.apexcharts.config.yaxis.builder.TitleBuilder;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.data.provider.SortDirection;
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
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
    private final Grid<Api.PlanTask> planGrid = buildPlanGrid();
    // #476 PR 3/3: a job-level (not per-task) live chart, built once and updated in place via
    // ApexCharts' own updateSeries()/setXaxis() - see updateRequestLatencyChart().
    private final ApexCharts requestLatencyChart = buildRequestLatencyChart();
    // fetched once and cached - the plan itself never changes once p1_plan has produced it; the
    // status column still refreshes every poll cycle from live.sessions(), re-rendered via setItems()
    private List<Api.PlanTask> planTasks = List.of();

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

    // The live-sessions sort keys, same shape as the requests ones above - every column sortable.
    static final Comparator<JobLiveState.SessionRow> SESSIONS_BY_TS =
            Fmt.comparingTime(JobLiveState.SessionRow::ts);
    static final Comparator<JobLiveState.SessionRow> SESSIONS_BY_LABEL =
            Fmt.nullsLast(JobLiveState.SessionRow::label);
    static final Comparator<JobLiveState.SessionRow> SESSIONS_BY_DESCRIPTION =
            Fmt.nullsLast(JobLiveState.SessionRow::description);
    static final Comparator<JobLiveState.SessionRow> SESSIONS_BY_ENDED_STAGE =
            Fmt.nullsLast(JobLiveState.SessionRow::endedStage);
    static final Comparator<JobLiveState.SessionRow> SESSIONS_BY_WALL_SEC =
            Fmt.nullsLast(JobLiveState.SessionRow::wallSec);
    static final Comparator<JobLiveState.SessionRow> SESSIONS_BY_PREFILL_WALL =
            Fmt.nullsLast(JobLiveState.SessionRow::prefillWallSec);
    static final Comparator<JobLiveState.SessionRow> SESSIONS_BY_DECODE_WALL =
            Fmt.nullsLast(JobLiveState.SessionRow::decodeWallSec);
    static final Comparator<JobLiveState.SessionRow> SESSIONS_BY_PREFILL_TPS =
            Fmt.nullsLast(JobLiveState.SessionRow::avgPrefillTokPerSec);
    static final Comparator<JobLiveState.SessionRow> SESSIONS_BY_DECODE_TPS =
            Fmt.nullsLast(JobLiveState.SessionRow::avgDecodeTokPerSec);
    static final Comparator<JobLiveState.SessionRow> SESSIONS_BY_TOTAL_TOKENS =
            Fmt.nullsLast(JobLiveState.SessionRow::totalTokens);

    // Stable field references, updated in place.
    private final Span kindLine = new Span();
    private final Span metaLine = new Span();
    private final Div statusHolder = new Div();
    private final Div blockedHolder = new Div();
    private final Div actionsHolder = new Div();
    private final VerticalLayout liveSection = new VerticalLayout();
    // #476 PR 3/3: wraps the current-step/active-time stat tiles so both hide together on a
    // terminal job, instead of each tile's own inner Span hiding and leaving an empty tile shell.
    private final HorizontalLayout liveStatsRow = new HorizontalLayout();
    private final Span plannedOrderLine = new Span();
    private final Span stepLine = new Span();
    private final Span activeTimeLine = new Span();
    // #517 follow-up (design-mockup parity): tasksDoneLine is pure/local (planTasks + live.sessions(),
    // no I/O); taskTurnsLine/toolErrorsLine come from currentTaskStats, which refresh() fetches
    // (one extra GET per poll, same /api/jobs/{id}/tasks/{taskId}/stats the per-task dialog's own
    // Stats tab already calls) - updateLive() only ever reads currentTaskStats, never fetches it.
    private final Span tasksDoneLine = new Span();
    private final Span taskTurnsLine = new Span();
    private final Span toolErrorsLine = new Span();
    private JsonNode currentTaskStats;
    private final Span requestsLine = new Span();
    private final Div logTail = new Div();
    private final Span terminalNote = new Span("This job has finished; nothing more to stream.");
    private final Span lostNotice = new Span("live connection lost — status still updates via polling");
    private final Span errorLine = new Span();
    private boolean chromeBuilt;
    // #265: beforeEnter()'s catch branch used to set chromeBuilt=true even though buildChrome()
    // was never actually called - a hack to stop its own re-entry guard from firing, but it also
    // defeated refresh()'s "!chromeBuilt" early-exit, so a job id that failed to load even once
    // kept polling client.job(jobId) (hitting the same failure) and holding an SSE connection open
    // forever, for as long as the browser tab stayed open. A separate flag, checked by BOTH the
    // attach listener (never arm polling/SSE for a view whose live UI was never built) and
    // refresh() (never re-poll even if something else were to call it directly).
    private boolean loadFailed;
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
            if (loadFailed) {
                return;   // #265: the initial load already failed - never arm polling/SSE for this view
            }
            pollRegistration = ui.addPollListener(e -> refresh());
            ui.setPollInterval(JobStatuses.isTerminal(status()) ? -1 : 2000);
            // #499: connect even for an already-finished job, not just a still-running one - this is
            // the ONLY path that ever populates live.sessions() (taskStats() below reads it, never a
            // completed job's own real wall-time/token/token figures from anywhere else), and
            // BenchController.events() already replays a finished job's full history in one shot
            // before self-terminating the stream, so this costs exactly one round trip, not an
            // open-ended connection - a plain job id was the only real gate; terminal vs. running
            // never needed to be.
            if (jobId != null) {
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
            // #265: NOT chromeBuilt=true - buildChrome() genuinely never ran, so none of the live
            // components (liveSection, the grids, statusHolder) exist. loadFailed is the flag that
            // actually matters here; see its own field comment.
            loadFailed = true;
        }
    }

    /** The static chrome, built once — every later cycle only updates fields. */
    private void buildChrome(final Api.Job job) {
        if (chromeBuilt) {
            return;
        }
        chromeBuilt = true;

        add(new RouterLink("← Queue", JobsView.class));

        add(TabManual.banner(this, "Watch a single run's live progress, plan, and per-task detail.", "User-Manual-Jobs"));

        final var title = new H2(job.run_id());
        title.getStyle().set("margin", "4px 0").set("font-size", "1.6em");
        add(title);

        statusHolder.getStyle().set("margin", "4px 0");
        add(statusHolder);
        add(kindLine);
        add(metaLine);
        add(blockedHolder);
        add(errorLine);
        errorLine.getStyle().set("font-size", "0.85em");

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

        // #476 PR 3/3: current step + active time as stat tiles instead of flat text lines - both
        // toggle together via liveStatsRow itself (see updateLive()), not their own inner Spans, so
        // a terminal job's tile shells disappear along with their values rather than going blank.
        liveStatsRow.setPadding(false);
        liveStatsRow.setSpacing(true);
        liveStatsRow.getStyle().set("flex-wrap", "wrap").set("margin", "4px 0");
        liveStatsRow.add(Panels.statTile("Current step", stepLine), Panels.statTile("Active time", activeTimeLine),
                Panels.statTile("Tasks done", tasksDoneLine), Panels.statTile("Current task turns", taskTurnsLine),
                Panels.statTile("Tool errors", toolErrorsLine));

        liveSection.add(liveTitle, plannedOrderLine, Panels.card("Plan", planGrid), liveStatsRow, sessionsGrid,
                requestsLine, lostNotice, terminalNote, Panels.card("Recent requests", requestLatencyChart, requestsGrid), logTail);
        plannedOrderLine.getStyle().set("font-size", "0.9em");
        stepLine.getStyle().set("font-weight", "600");
        activeTimeLine.getStyle().set("font-size", "0.9em");
        com.vaadin.flow.component.shared.Tooltip.forComponent(activeTimeLine).setText("excludes paused/stopped periods");
        tasksDoneLine.getStyle().set("font-weight", "600");
        taskTurnsLine.getStyle().set("font-weight", "600");
        toolErrorsLine.getStyle().set("font-weight", "600");
        lostNotice.getStyle().set("font-size", "0.85em");
        logTail.getStyle().set("font-family", "ui-monospace, 'SF Mono', Menlo, monospace")
                .set("font-size", "12px")
                .set("padding", "12px")
                .set("border-radius", "6px")
                .set("overflow-x", "auto")
                .set("white-space", "pre-wrap")
                .set("margin", "6px 0 0 0");
        add(liveSection);
    }

    /** Poll cycle: fetch the job row and update fields in place. */
    private void refresh() {
        if (jobId == null || !chromeBuilt || loadFailed) {
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
        refreshCurrentTaskStats(terminal);
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

    /** The current task's own turns/tool-call stats, via the same GET /api/jobs/{id}/tasks/{taskId}/
     *  stats the per-task dialog's own Stats tab already calls - reused as-is rather than adding a
     *  new job-wide aggregate endpoint (the backend's own sessionCounts() rescans every session file
     *  in the job's directory per task; a naive all-tasks aggregate would cost O(tasks × files) on
     *  every 2s poll for no real benefit, since taskTurnsLine/toolErrorsLine are about the CURRENT
     *  task only - matching the design mockup's own "T17 turns"/"Tool errors" tiles, which named
     *  the then-running task specifically, not a job-wide total). Best-effort: a transient fetch
     *  failure leaves currentTaskStats at its last known value rather than flashing the tiles blank. */
    private void refreshCurrentTaskStats(final boolean terminal) {
        final var step = live.currentStep();
        if (terminal || step == null) {
            currentTaskStats = null;
            return;
        }
        try {
            currentTaskStats = client.taskStats(jobId, step);
        } catch (final Exception e) {
            log.debug("could not fetch current-task stats for job {} task {}: {}", jobId, step, e.toString());
        }
    }

    private void updateStatus(final Api.Job job) {
        statusHolder.removeAll();
        final var line = new HorizontalLayout(Badges.status(job.status()));
        line.setPadding(false);
        line.setSpacing(true);
        if (job.cancel_requested() && !JobStatuses.isTerminal(job.status())) {
            line.add(new Span("cancel requested — stops at the next step boundary"));
        } else if (job.pause_requested() && !JobStatuses.isTerminal(job.status())) {
            line.add(new Span("pause requested — stops at the next step boundary, resumable with Resume"));
        }
        kindLine.setText("job #" + job.id() + " · " + job.kind()
                + (job.arm() != null ? " · " + job.arm() + " r" + job.repeat() : ""));
        statusHolder.add(line);
        metaLine.setText(metaLine(job));
    }

    /** Rebuilt only when the offered actions change — focus is preserved between cycles. */
    private void updateActions(final Api.Job job) {
        final var signature = job.status() + "|" + job.cancel_requested() + "|" + job.pause_requested() + "|" + runImported
                + "|" + (job.run_id() != null);
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
        if (JobStatuses.canPause(job.status(), job.cancel_requested(), job.pause_requested())) {
            actions.add(new Button("Pause", e -> {
                try {
                    client.pause(job.id());
                    refresh();
                } catch (final Exception ex) {
                    log.warn("could not pause job {}: {}", job.id(), ex.toString());
                    Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
                }
            }));
            // the gentler alternative: for a running job, takes effect once the task currently in
            // progress actually finishes rather than immediately - "Pause" above can discard real,
            // in-progress agent work if it lands mid-task.
            final var pauseAfterTask = new Button("Pause after task", e -> {
                try {
                    client.pauseAfterTask(job.id());
                    refresh();
                    Notification.show("Will pause once the current task finishes.", 4000, Notification.Position.BOTTOM_END);
                } catch (final Exception ex) {
                    log.warn("could not request a pause-after-task for job {}: {}", job.id(), ex.toString());
                    Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
                }
            });
            pauseAfterTask.setTooltipText("Pause once the currently running task finishes, instead of interrupting it now.");
            actions.add(pauseAfterTask);
        }
        if (JobStatuses.canRequeue(job.status())) {
            // "paused" reads as Resume - same requeue endpoint either way, R18's own git-tag resume
            // mechanism in the kept workspace is what actually picks the run back up
            actions.add(new Button("paused".equals(job.status()) ? "Resume" : "Requeue", e -> {
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
            hint.getStyle().set("font-size", "0.85em");
            actions.add(hint);
        }
        // #275: a "raw log" link used to render here whenever job.stdout_path() was set, pointing
        // at <baseUrl>/jobs/{id}/log - missing the /api/ prefix every real route uses, AND there is
        // no such route anywhere in BenchController to begin with (it would 404). Latent only
        // because stdout_path is always null in practice: JobQueue.started() is always called with
        // a literal null for it (WorkerService.poll()'s in-process worker never writes a separate
        // stdout log file the way the old subprocess-based Python worker did) - removed rather than
        // pointed at a real endpoint, since there is no actual log file for it to serve.
        actionsHolder.removeAll();
        actionsHolder.add(actions);
    }

    private void updateLive() {
        final var terminal = currentJob != null && JobStatuses.isTerminal(currentJob.status());
        updatePlanGrid(terminal);
        lostNotice.setVisible(sseThread != null && !sseThread.isAlive()
                && !terminal && !sseStopped);
        terminalNote.setVisible(terminal);
        plannedOrderLine.setVisible(!terminal && !live.executionOrder().isEmpty());
        liveStatsRow.setVisible(!terminal);
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
            requestLatencyChart.setVisible(false);
            return;
        }

        if (!live.executionOrder().isEmpty()) {
            plannedOrderLine.setText("planned order: " + plannedOrderText(live.executionOrder()));
        }
        // #476 PR 3/3: no "current step: "/"active time: " prefix - each now lives inside a
        // Panels.statTile() whose own label already says that (see buildChrome()); the "excludes
        // paused/stopped periods" caveat moved to activeTimeLine's tooltip (set once, in buildChrome).
        stepLine.setText(live.currentStep() == null ? "–" : live.currentStep());
        activeTimeLine.setText(Fmt.daysHoursMinutes(live.totalActiveSeconds()));
        tasksDoneLine.setText(tasksDoneText(planTasks));
        taskTurnsLine.setText(taskTurnsText(currentTaskStats));
        toolErrorsLine.setText(toolErrorsText(currentTaskStats));
        sessionsGrid.setItems(live.sessions());
        final var recent = live.recentRequests();
        // #109: recentRequests is capped (JobLiveState.MAX_RECENT_REQUESTS); say so once the total
        // has actually exceeded it, instead of silently showing fewer rows than "requests: N" implies
        final String requestsSummary = live.requestCount() == 0 ? "–"
                : recent.size() < live.requestCount()
                        ? "showing the most recent " + recent.size() + " of " + live.requestCount() + " requests"
                        : String.valueOf(live.requestCount());
        requestsLine.setText("requests: " + requestsSummary
                + (live.lastTokens() == null ? "" : " · last completion tokens " + Fmt.count(live.lastTokens()))
                + " · total tokens generated " + Fmt.count(live.totalCompletionTokens())
                + " · total prompt tokens " + Fmt.count(live.totalPromptTokens()));

        requestsGrid.setVisible(!recent.isEmpty());
        requestsGrid.setItems(recent);
        requestLatencyChart.setVisible(!recent.isEmpty());
        updateRequestLatencyChart(recent);

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
        requests.addColumn(r -> r.abortReason() == null ? "" : r.abortReason()).setHeader("abort reason").setAutoWidth(true);
        requests.getColumns().forEach(c -> c.setResizable(true));
        // #new bug: setAllRowsVisible(true) renders every row with no internal scrollbar, which is fine
        // for a handful of rows but silently gives no way to reach anything beyond the live state's own
        // cap once there are more - a fixed height switches the grid to Vaadin's normal virtual
        // scrolling, which renders any number of rows efficiently and scrolls internally
        requests.setHeight("420px");
        requests.setVisible(false);
        return requests;
    }

    /** #476 PR 3/3: a job-level (not per-task) live chart sitting beside requestsGrid, built from
     *  the SAME live.recentRequests() data already driving that grid - avoids the "which task's
     *  chart" ambiguity the per-task dialog charts would have at this, whole-job scope. Built once;
     *  see updateRequestLatencyChart() for how it's kept current without a full rebuild. */
    private static ApexCharts buildRequestLatencyChart() {
        final var chart = ApexChartsBuilder.get()
                .withChart(ChartBuilder.get().withType(Type.LINE).withBackground("transparent").build())
                .withTheme(ThemeBuilder.get().withMode(Mode.DARK).build())
                .withXaxis(XAxisBuilder.get().withCategories(new String[0]).build())
                .withYaxis(YAxisBuilder.get().withTitle(TitleBuilder.get().withText("latency (s)").build()).build())
                .withSeries(SeriesBuilder.get().withName("latency (s)").withData(new Double[0]).build())
                .build();
        chart.setHeight("220px");
        chart.setVisible(false);
        return chart;
    }

    /** Updated in place via ApexCharts' own updateSeries()/setXaxis() rather than rebuilt from
     *  scratch - a running job polls every 2s, and a full chart re-render that often would
     *  flash/flicker the same way a full-DOM rebuild would steal focus elsewhere on this page. */
    private void updateRequestLatencyChart(final List<JobLiveState.RequestRow> recent) {
        final var categories = new String[recent.size()];
        for (int i = 0; i < recent.size(); i++) {
            categories[i] = String.valueOf(i + 1);
        }
        requestLatencyChart.setXaxis(XAxisBuilder.get().withCategories(categories).build());
        requestLatencyChart.updateSeries(SeriesBuilder.get().withName("latency (s)")
                .withData(recent.stream().map(JobLiveState.RequestRow::latencySec).toArray(Double[]::new)).build());
    }

    /** #47: the stage a session ended at, alongside its label - a session that outlives its own
     *  stage (still "running") shows that explicitly rather than leaving the reader to guess. */
    private Grid<JobLiveState.SessionRow> buildSessionsGrid() {
        final var sessions = new Grid<>(JobLiveState.SessionRow.class, false);
        final var ts = sessions.addColumn(r -> Fmt.when(r.ts())).setHeader("ts").setAutoWidth(true)
                .setComparator(SESSIONS_BY_TS);
        sessions.addColumn(JobLiveState.SessionRow::label).setHeader("session").setAutoWidth(true)
                .setComparator(SESSIONS_BY_LABEL);
        sessions.addColumn(JobLiveState.SessionRow::description).setHeader("description").setAutoWidth(true)
                .setComparator(SESSIONS_BY_DESCRIPTION);
        sessions.addColumn(r -> r.endedStage() == null ? "running" : r.endedStage())
                .setHeader("ended at").setAutoWidth(true)
                .setComparator(SESSIONS_BY_ENDED_STAGE);
        // only known once the session ends (its own transcript's start-to-end ts) - "-" while running,
        // rather than a live-ticking elapsed time
        sessions.addColumn(r -> Fmt.duration(r.wallSec())).setHeader("wall time")
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true)
                .setComparator(SESSIONS_BY_WALL_SEC);
        // summed per request: ttft_sec IS the prefill wall time, latency_sec - ttft_sec the decode wall
        // time - Fmt.seconds (not Fmt.duration) since prefill in particular is often sub-minute and an
        // hour+minute format would round it away to nothing
        sessions.addColumn(r -> Fmt.seconds(r.prefillWallSec())).setHeader("prefill wall")
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true)
                .setComparator(SESSIONS_BY_PREFILL_WALL);
        sessions.addColumn(r -> Fmt.seconds(r.decodeWallSec())).setHeader("decode wall")
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true)
                .setComparator(SESSIONS_BY_DECODE_WALL);
        // averaged across the session's requests as they arrive - oMLX-reported tok/s, not derived
        // from proxy-observed timings; "-" until the first request with real usage lands
        sessions.addColumn(r -> Fmt.num(r.avgPrefillTokPerSec())).setHeader("prefill tok/s")
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true)
                .setComparator(SESSIONS_BY_PREFILL_TPS);
        sessions.addColumn(r -> Fmt.num(r.avgDecodeTokPerSec())).setHeader("decode tok/s")
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true)
                .setComparator(SESSIONS_BY_DECODE_TPS);
        // a running sum across the session's requests - visible live, unlike wall time
        sessions.addColumn(r -> Fmt.count(r.totalTokens())).setHeader("total tokens")
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true)
                .setComparator(SESSIONS_BY_TOTAL_TOKENS);
        sessions.getColumns().forEach(c -> c.setResizable(true));
        sessions.sort(List.of(new GridSortOrder<>(ts, SortDirection.ASCENDING)));
        // #264: setAllRowsVisible(true) - the same bug buildRequestsGrid()'s own comment describes
        // and was fixed for there - renders every row with no internal scrollbar. An orchestrated
        // run produces one row per phase/task/continuation/FIX-W*/INTEGRATION session - a dozen
        // tasks easily means 30-50+ rows, growing this grid to fit all of them and pushing
        // everything below it (including the live log tail) arbitrarily far down the page with no
        // way to scroll just this table. A fixed height switches to Vaadin's normal virtual
        // scrolling instead, same fix as requestsGrid's own.
        sessions.setHeight("420px");
        sessions.setVisible(false);
        // #264: a row click opens Prompt/Changes(/Restart for the three known phases) the same way
        // planGrid's own row click already opens openTaskPromptDialog - this grid previously had no
        // click handling at all, so "Definition"/"Plan"/"Parallel plan" (no other way to inspect
        // them) just did nothing when clicked.
        sessions.addItemClickListener(e -> openSessionDialog(e.getItem()));
        sessions.getStyle().set("cursor", "pointer");
        return sessions;
    }

    /** The plan's own task table: planned order, id, description, and a live status - replaces the
     *  bare "T1 → T2" of plannedOrderLine with something a reader can actually act on. A row click
     *  opens the task's real prompt (openTaskPromptDialog). The status column closes over `live` (an
     *  instance field, not the row item), re-evaluated every time updatePlanGrid() re-sets the grid's
     *  items on the same 2 s poll cycle as everything else on this page. */
    private Grid<Api.PlanTask> buildPlanGrid() {
        final var grid = new Grid<>(Api.PlanTask.class, false);
        grid.addColumn(Api.PlanTask::order).setHeader("#").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(Api.PlanTask::id).setHeader("task").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> t.goal() == null || t.goal().isBlank()
                        ? (t.title() == null ? "" : t.title()) : t.goal())
                .setHeader("description").setFlexGrow(1);
        grid.addColumn(t -> taskStatus(live.sessions(), t,
                        currentJob != null && JobStatuses.isTerminal(currentJob.status()), live.currentStep(), live.rescuingReason(t.id())))
                .setHeader("status").setAutoWidth(true).setFlexGrow(0)
                // #262: the status word alone doesn't say WHY a task didn't just succeed - hover
                // for the agent's own self-reported detail (or the harness fallback's explanation).
                .setTooltipGenerator(t -> taskStatusReason(live.sessions(), t, live.rescuingReason(t.id())));
        grid.addColumn(t -> Fmt.daysHoursMinutes(taskStats(live.sessions(), t.id(), live.currentStep()).wallSec()))
                .setHeader("time").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> Fmt.count(taskStats(live.sessions(), t.id(), live.currentStep()).completionTokens()))
                .setHeader("generated").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> Fmt.count(taskStats(live.sessions(), t.id(), live.currentStep()).promptTokens()))
                .setHeader("prefilled").setAutoWidth(true).setFlexGrow(0);
        // #337: blank for the common case (one clean attempt, never rescued) - a clean pass and a
        // rescued/retried one should read as visibly different outcomes, not the same checkmark.
        grid.addColumn(t -> t.attempts() <= 1 && !t.rescued() ? "" : t.attempts() + (t.rescued() ? " (rescued)" : ""))
                .setHeader("attempts").setAutoWidth(true).setFlexGrow(0);
        grid.getColumns().forEach(c -> c.setResizable(true));
        grid.addItemClickListener(e -> openTaskPromptDialog(e.getItem()));
        grid.getStyle().set("cursor", "pointer");
        // #264: same scroll-cap fix as sessionsGrid/requestsGrid, at a smaller scale (one row per
        // plan task rather than per session).
        grid.setHeight("420px");
        grid.setVisible(false);
        return grid;
    }

    /** Fetches the plan once (it never changes once p1_plan has produced it - a resumed task keeps
     *  the same plan) and caches it; re-sets the grid's items every call regardless, so the status
     *  column (closed over live.sessions(), not part of Api.PlanTask itself) stays current. */
    private void updatePlanGrid(final boolean terminal) {
        if (planTasks.isEmpty() && jobId != null) {
            try {
                final var resp = client.jobPlan(jobId);
                if (resp != null && resp.tasks() != null && !resp.tasks().isEmpty()) planTasks = resp.tasks();
            } catch (final Exception e) {
                log.debug("could not fetch plan for job {}: {}", jobId, e.toString());
            }
        }
        planGrid.setItems(planTasks);
        planGrid.setVisible(!planTasks.isEmpty());
    }

    /** A plan task's status. A genuinely LIVE session (RunBench names every task session
     *  "Task &lt;id&gt;[ suffix][ — title]" - JobLiveState.sessionDescription() - anchored with a
     *  trailing space/exact match so "T1" never matches a session actually for "T10"/"T11"; the
     *  LAST matching session wins, since a resumed/retried task gets a fresh session sharing the
     *  same description prefix and that's the one whose state is current) with no endedStage yet
     *  always wins outright - nothing is more current than "it is running right now".
     *
     *  Once a task is actually implemented (its own R18 git tag, phase/{id} - resume-independent,
     *  unlike live session data), its own reported_status (Api.PlanTask - the agent's self-reported
     *  done/partial/blocked, or the harness's "unfinished" fallback) is what's shown, in preference
     *  to a merely-ENDED live session row.
     *
     *  #261, found live: implemented/handoff_done alone only say the task's session ran to
     *  completion, not whether it actually succeeded - and the live session's own endedStage can be
     *  actively MISLEADING here: "tool_execution" (the model mid-tool-call when a budget-exhausted
     *  session got cut off) read, at a glance, like the task was still doing something, for a task
     *  that had in fact already finished (one way or another) hours earlier. reported_status is the
     *  real, persistent verdict - it is read straight from the one place the harness guarantees a
     *  line always lands once a task's main session ends, win or lose: docs/PROGRESS.md.
     *
     *  #254, found live: a task completed in a PRIOR attempt is resumed straight past (R18) and so
     *  never produces a session_started/session_done event in a FRESH page's live view - session
     *  data alone read a genuinely-finished task as "not started" every time. terminal is only the
     *  very last resort, for a task none of these signals has anything on at all (e.g. the plan file
     *  itself couldn't be matched to a tag for some reason) - "not started" would be an outright
     *  wrong claim on an already-finished job there.
     *
     *  #335, found live: "nothing is more current than it is running right now" stops being true
     *  the moment a DIFFERENT task is demonstrably the live one - a pause+reorder (or any other
     *  interruption that never produces a session_done) leaves the old session permanently
     *  unclosed, and closeAbandonedAttempt() only fires when THIS SAME task is retried, which can
     *  be many tasks away. currentStep is the one live signal that's always current regardless of
     *  which task it names - an unclosed session only still counts as "running" when it's also the
     *  current step; otherwise it's treated exactly like an already-recognized abandoned attempt.
     *
     *  Found live on T14: a budget-rescue attempt reuses the task's own canonical session id, so a
     *  stray failed wrap-up sub-call's own "end" record can make endedStage non-null (and the task
     *  look finished, or "?" once #396 fixed the blank-string case) while the rescue is genuinely
     *  still working for hours afterward. rescuingReason (non-null only while RunBench.rescueStep()
     *  has a "<taskId>.start" marker written and no ".done" yet - see JobLiveState.rescuingReason())
     *  takes priority over every other branch, including "implemented", for exactly that reason. */
    static String taskStatus(final List<JobLiveState.SessionRow> sessions, final Api.PlanTask task, final boolean terminal, final String currentStep, final String rescuingReason) {
        if (rescuingReason != null) return "budget rescue in progress";
        final var last = lastMatchingSession(sessions, task.id());
        if (last != null && last.endedStage() == null && task.id().equals(currentStep)) return "running";
        final var endedStage = last == null ? null : (last.endedStage() != null ? last.endedStage() : "interrupted");
        if (task.implemented()) {
            final var reported = task.reported_status();
            final var suffix = task.handoff_done() ? "" : " (handoff pending)";
            if (reported != null) return reported + suffix;
            return task.handoff_done() ? "done" : "implemented (handoff pending)";
        }
        if (endedStage != null) return endedStage;
        return terminal ? "see Run detail" : "not started";
    }

    /** #262: the WHY behind taskStatus()'s own reported outcome - how it was verified (done),
     *  what's missing (partial), why (blocked), or the harness fallback's own fixed explanation
     *  (unfinished) - shown as a tooltip on the status cell. Mirrors taskStatus()'s own precedence
     *  exactly: null whenever that method isn't actually showing a reported outcome (still running,
     *  or not started at all), so hovering a transient state never surfaces stale detail text left
     *  over from a past, unrelated attempt. rescuingReason (see taskStatus()'s own javadoc) takes
     *  the same top priority there, naming the real reason the rescue fired in the first place. */
    static String taskStatusReason(final List<JobLiveState.SessionRow> sessions, final Api.PlanTask task, final String rescuingReason) {
        if (rescuingReason != null) return rescuingReason;
        final var last = lastMatchingSession(sessions, task.id());
        if (last != null && last.endedStage() == null) return null;
        if (!task.implemented()) return null;
        final var detail = task.reported_detail();
        return detail == null || detail.isBlank() ? null : detail;
    }

    /** A task counts as "done" for the Tasks-done tile the same way taskStatus() itself detects a
     *  clean success - implemented, and either an explicit "done" self-report or (no report at all)
     *  a finished handoff - just without taskStatus()'s own "(handoff pending)" suffix formatting,
     *  which the tile's plain X/Y count has no use for. */
    static boolean isTaskDone(final Api.PlanTask task) {
        if (!task.implemented()) return false;
        final var reported = task.reported_status();
        return reported != null ? "done".equals(reported) : task.handoff_done();
    }

    /** #517 follow-up: the "Tasks done" tile - X/Y across every plan row (stage rows like
     *  PARALLEL_PLAN/INTEGRATION/REVIEW included, same set the Plan grid itself already lists). */
    static String tasksDoneText(final List<Api.PlanTask> tasks) {
        if (tasks.isEmpty()) return "–";
        final long done = tasks.stream().filter(JobDetailView::isTaskDone).count();
        return done + " / " + tasks.size();
    }

    /** #517 follow-up: the "Current task turns" tile, from the SAME per-task stats payload the
     *  per-task dialog's Stats tab already fetches (taskStatsLines() reads "turns" the identical
     *  way) - sub-detail is the current task's own bash-call count, matching the design mockup's
     *  own "128 bash calls" sub-line under its "T17 turns" tile. */
    static String taskTurnsText(final JsonNode stats) {
        if (stats == null || !stats.hasNonNull("turns")) return "–";
        final var bashCalls = stats.path("tool_calls").path("bash").path("count").asInt(0);
        return stats.path("turns").asInt() + " · " + bashCalls + " bash calls";
    }

    /** #517 follow-up: the "Tool errors" tile - bash-tool errors specifically (not every tool's
     *  errors summed), matching the mockup's own "14% of bash calls" sub-line, which is a rate
     *  over bash calls specifically, not over every tool call the task made. */
    static String toolErrorsText(final JsonNode stats) {
        if (stats == null) return "–";
        final var bash = stats.path("tool_calls").path("bash");
        final var calls = bash.path("count").asInt(0);
        final var errors = bash.path("errors").asInt(0);
        if (calls == 0) return String.valueOf(errors);
        return errors + " · " + Math.round(100.0 * errors / calls) + "% of bash calls";
    }

    private static JobLiveState.SessionRow lastMatchingSession(final List<JobLiveState.SessionRow> sessions, final String taskId) {
        JobLiveState.SessionRow last = null;
        for (final var s : sessions) if (matchesSession(s.name(), taskId)) last = s;
        return last;
    }

    /** A session's raw label ("T21", "T21-judge-turn20", "REVIEW", "p0_definition-continue", ...)
     *  matches taskId itself or any of its own continuation/judge/handoff/fix sub-sessions - the
     *  same matching earliestOtherTaskSessionStartAfter() below already used inline before this was
     *  pulled out and shared here too. name() is the session's raw label, unlike description()'s
     *  human-rendered form ("Task T21", "Self review", ...) - matching on it directly is label-
     *  shape-agnostic, working identically for a real task id ("T21") and a pipeline stage id
     *  ("REVIEW", "p0_definition") alike, unlike the OLD "Task " + id prefix-on-description() check
     *  this replaces, which only ever matched a real task's own "Task <id>" rendering - never a
     *  stage's ("Definition", "Self review", ...), silently leaving a stage's own live time/tokens/
     *  running-status undetectable. */
    private static boolean matchesSession(final String rawLabel, final String taskId) {
        return rawLabel != null && (rawLabel.equals(taskId) || rawLabel.startsWith(taskId + "-"));
    }

    /** A task's own live time/token totals for the plan grid - unlike lastMatchingSession() (which
     *  only needs the CURRENT state), this sums EVERY session matching the task's id: a task
     *  retried or continued across multiple sessions has genuinely spent time/tokens in all of
     *  them, not just its latest one, same reasoning JobLiveState.totalActiveSeconds() already
     *  applies at the whole-run level. A still-open session for the task that's actually the
     *  current step contributes its own elapsed-so-far, so the figure ticks up live while a task
     *  is actually running.
     *
     *  #335: an unclosed session for a DIFFERENT (non-current) task was abandoned, not running -
     *  summing to "now" would grow it forever even though nothing has touched that task since.
     *  Frozen instead at the earliest moment some OTHER task's own session started after this one
     *  began - the latest point this session could still have been the live one - falling back to
     *  "now" only if nothing else has started since (shouldn't happen: that would make it the
     *  current step, the branch above). */
    record TaskStats(double wallSec, long completionTokens, long promptTokens) {
    }

    static TaskStats taskStats(final List<JobLiveState.SessionRow> sessions, final String taskId, final String currentStep) {
        var wall = 0.0;
        var completion = 0L;
        var prompt = 0L;
        for (final var s : sessions) {
            if (!matchesSession(s.name(), taskId)) continue;
            if (s.endedStage() != null) {
                if (s.wallSec() != null) wall += s.wallSec();
            } else {
                final var start = Fmt.parseTime(s.ts());
                if (start != null) {
                    final var end = taskId.equals(currentStep) ? OffsetDateTime.now()
                            : earliestOtherTaskSessionStartAfter(sessions, taskId, s.ts());
                    wall += Duration.between(start, end != null ? end : OffsetDateTime.now()).getSeconds();
                }
            }
            if (s.totalTokens() != null) completion += s.totalTokens();
            if (s.promptTokens() != null) prompt += s.promptTokens();
        }
        return new TaskStats(wall, completion, prompt);
    }

    /** The earliest start, across every session in the run, strictly after afterTs - excluding
     *  taskId's own (sub-)sessions (a judge/continuation/handoff turn for the SAME task is still
     *  that task running, not evidence something else superseded it) via the same matchesSession()
     *  lastMatchingSession() above now shares too, rather than each duplicating the same raw-label
     *  check. */
    private static OffsetDateTime earliestOtherTaskSessionStartAfter(
            final List<JobLiveState.SessionRow> sessions, final String taskId, final String afterTs) {
        final var after = Fmt.parseTime(afterTs);
        if (after == null) return null;
        OffsetDateTime earliest = null;
        for (final var s : sessions) {
            if (matchesSession(s.name(), taskId)) continue;
            final var t = Fmt.parseTime(s.ts());
            if (t != null && t.isAfter(after) && (earliest == null || t.isBefore(earliest))) earliest = t;
        }
        return earliest;
    }

    /** The task's prompt and its own git changes, on demand, as two tabs of one dialog. Polling
     *  (piggybacking the page's own 2s poll cycle - see update()'s setPollInterval) keeps BOTH tabs
     *  fresh while either still has something left to learn, and stops once both have settled:
     *  the prompt once its real, dynamic part has arrived (taskPrompt.started) or is confirmed
     *  never coming (#258 - a task finished in an earlier, since-discarded attempt); the diff once
     *  the task's own phase tag exists (taskDiff.finished) - until then the diff is the live,
     *  uncommitted working tree, which keeps changing for as long as the task keeps running. */
    private void openTaskPromptDialog(final Api.PlanTask task) {
        final var dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(task.id() + (task.title() == null || task.title().isBlank() ? "" : " — " + task.title()));
        dialog.setWidth("min(900px, 90vw)");
        dialog.setHeight("min(650px, 85vh)");

        // each body below gets the SAME explicit "width: 100%; box-sizing: border-box" fix
        // transcriptBody/interveneRow already needed and explain further down: a VerticalLayout
        // does not stretch its children to its own width by default, so without it every tab's
        // content (and the tab strip itself) only grows as wide as its own content - its right
        // edge lands wherever that happens to be instead of the dialog's own right edge, where
        // the footer's Close button sits (found live: visibly misaligned against it).
        final var promptBody = new Div();
        promptBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        final var diffBody = new Div();
        diffBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        diffBody.setVisible(false);
        final var restartBody = new Div();
        restartBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        restartBody.setVisible(false);
        final var historyBody = new Div();
        historyBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        historyBody.setVisible(false);
        final var judgeBody = new Div();
        judgeBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        judgeBody.setVisible(false);
        final var statsBody = new Div();
        statsBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        statsBody.setVisible(false);
        // statsSummaryBody (the top text lines) is cleared and rebuilt every poll, same as before -
        // statsAttemptsBody is a SIBLING, same reason transcriptBody/interveneRow are siblings below:
        // a chart inside it must survive statsSummaryBody's own removeAll() every 2s, or every chart
        // gets torn down and rebuilt from scratch on every poll tick even when no new data exists -
        // the operator asked for a running task's own graphs to redraw only when a request actually
        // finishes, not on a fixed clock. See loadStats below for how each attempt's own chart area
        // is gated.
        final var statsSummaryBody = new Div();
        final var statsAttemptsBody = new Div();
        statsBody.add(statsSummaryBody, statsAttemptsBody);
        // the operator-facing live transcript - see openTaskTranscriptTab() below for why this
        // is its own method rather than inlined like the bodies above (its content keeps
        // growing from a background thread for as long as the dialog stays open, unlike every
        // other tab here which is just rebuilt wholesale on each poll tick).
        final var transcriptBody = new Div();
        // an explicit width, not just "auto": transcriptBody is a flex item of the content
        // VerticalLayout below, which does not stretch its children to its own width by default -
        // without this, a long unbroken line in the transcript (a file path, a JSON blob) has no
        // definite containing width to wrap/scroll against, so it balloons transcriptBody AND the
        // whole dialog outward instead of scrolling horizontally within its own box, same as a
        // too-narrow interveneRow (below) would otherwise mis-size its own Send button row.
        transcriptBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        transcriptBody.setVisible(false);
        // a SIBLING of transcriptBody, never nested inside it - transcriptBody.removeAll() runs on
        // every single incoming SSE line (see openTaskTranscriptTab()), which would wipe an input
        // box (and any not-yet-sent, half-typed text) on every streaming update if it lived inside.
        final var interveneRow = new Div();
        interveneRow.getStyle().set("width", "100%").set("box-sizing", "border-box");
        interveneRow.setVisible(false);

        final var promptTab = new com.vaadin.flow.component.tabs.Tab("Prompt");
        final var changesTab = new com.vaadin.flow.component.tabs.Tab("Changes");
        final var restartTab = new com.vaadin.flow.component.tabs.Tab("Restart");
        final var historyTab = new com.vaadin.flow.component.tabs.Tab("History");
        final var judgeTab = new com.vaadin.flow.component.tabs.Tab("Judge");
        final var statsTab = new com.vaadin.flow.component.tabs.Tab("Stats");
        final var transcriptTab = new com.vaadin.flow.component.tabs.Tab("Live");
        final var tabs = new com.vaadin.flow.component.tabs.Tabs(promptTab, changesTab, restartTab, historyTab, judgeTab, statsTab, transcriptTab);
        tabs.getStyle().set("width", "100%").set("box-sizing", "border-box");
        // a pipeline stage (definition/plan/parallel-plan/self-review) has no Restart tab - the
        // per-task restart endpoint it would call assumes a "phase/" + id git tag, which doesn't
        // match any of these stages' own irregular tag names (see Api.PlanTask.stage()'s own
        // javadoc), so restarting one from here would just fail with a confusing server error
        // instead of doing nothing visibly wrong.
        restartTab.setVisible(!task.stage());
        tabs.addSelectedChangeListener(e -> {
            promptBody.setVisible(tabs.getSelectedTab() == promptTab);
            diffBody.setVisible(tabs.getSelectedTab() == changesTab);
            restartBody.setVisible(tabs.getSelectedTab() == restartTab);
            historyBody.setVisible(tabs.getSelectedTab() == historyTab);
            judgeBody.setVisible(tabs.getSelectedTab() == judgeTab);
            statsBody.setVisible(tabs.getSelectedTab() == statsTab);
            transcriptBody.setVisible(tabs.getSelectedTab() == transcriptTab);
            interveneRow.setVisible(tabs.getSelectedTab() == transcriptTab);
        });

        final var content = new VerticalLayout(tabs, promptBody, diffBody, restartBody, historyBody, judgeBody, statsBody, transcriptBody, interveneRow);
        content.setPadding(false);
        content.setSpacing(false);
        content.setSizeFull();
        dialog.add(content);
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));

        final Registration[] pollReg = new Registration[1];
        // #256, found live: Packs.taskPack() caps the whole id/title/goal/services/acceptance
        // summary block to a fixed prompt-budget size (a real, deliberate cost/context control on
        // what the MODEL receives, not a bug) - so the version embedded in the raw prompt can cut
        // "acceptance" off mid-sentence once "goal"/"services" alone already used the budget. The
        // plan's own goal text (task.goal(), from PlanParser - never capped there) is shown here in
        // full regardless, since there's no reason the ACE user watching this page should be bound
        // by the same budget the model's own prompt is deliberately held to.
        final boolean hasGoal = task.goal() != null && !task.goal().isBlank();
        final boolean[] promptSettled = {false};
        final boolean[] diffSettled = {false};

        final Runnable loadPrompt = () -> {
            try {
                final var prompt = client.taskPrompt(jobId, task.id());
                promptBody.removeAll();
                if (hasGoal) {
                    promptBody.add(new com.vaadin.flow.component.Html(
                            "<div class=\"md-body\"><h3>Full task description</h3>" + Markdown.toHtml(task.goal()) + "</div>"));
                }
                // #258, found live: a task can be genuinely done (per its own R18 git tag) while its
                // results dir never got packs/{id}.md - it finished during an earlier, since-discarded
                // attempt (cancelled before the pause fix existed, or otherwise aborted) and R18 then
                // skipped re-running it on resume, so the pack was never rewritten into the new attempt's
                // results dir either. Without this check that read as "hasn't started yet", which is
                // simply false and - worse - would poll forever, since the real prompt is never coming.
                final boolean finishedElsewhere = task.implemented() || task.handoff_done();
                if (!prompt.started()) {
                    final var note = new Span(finishedElsewhere
                            ? "This task already finished in an earlier attempt whose results were discarded "
                              + "before this one resumed, so the enriched prompt from that attempt no longer "
                              + "exists - only its fixed instruction is shown below."
                            : "This task hasn't started yet - the prompt below is its fixed "
                              + "instruction only; the rest depends on the workspace state at the moment it actually "
                              + "begins. This updates automatically once it does.");
                    note.getStyle().set("font-size", "0.85em")
                            .set("display", "block").set("margin-bottom", "8px");
                    promptBody.add(note);
                    promptSettled[0] = finishedElsewhere;
                } else {
                    promptSettled[0] = true;
                }
                promptBody.add(new com.vaadin.flow.component.Html("<div class=\"md-body\">" + Markdown.toHtml(prompt.text()) + "</div>"));
            } catch (final Exception ex) {
                promptBody.removeAll();
                promptBody.add(Panels.error(client.errorText(ex)));
                promptSettled[0] = true;
            }
        };
        // #259: the task's own git diff - the previous state of the repo vs what it changed, or is
        // still in the middle of changing. See BenchController.taskDiff() for before/after semantics.
        final Runnable loadDiff = () -> {
            try {
                final var diff = client.taskDiff(jobId, task.id());
                diffBody.removeAll();
                diffBody.add(DiffView.render(diff));
                diffSettled[0] = diff.finished();
            } catch (final Exception ex) {
                diffBody.removeAll();
                diffBody.add(Panels.error(client.errorText(ex)));
                diffSettled[0] = true;
            }
        };
        // #263: every past attempt at this task's own main session, newest first - read-only, so
        // (unlike the Restart tab's own form below) safe to rebuild on every poll tick the same way
        // Prompt/Changes already are.
        final Runnable loadHistory = () -> {
            try {
                final var attempts = client.taskAttempts(jobId, task.id());
                historyBody.removeAll();
                if (attempts.isEmpty()) {
                    historyBody.add(Panels.mono("(no attempts recorded yet)"));
                    return;
                }
                final int n = attempts.size();
                for (int i = 0; i < n; i++) {
                    final var a = attempts.get(i);
                    final var header = new com.vaadin.flow.component.html.H4(
                            "Attempt " + (n - i) + " — " + (a.attempt_started() == null ? "?" : a.attempt_started())
                                    + " → " + (a.attempt_ended() == null ? "?" : a.attempt_ended()));
                    header.getStyle().set("margin", "12px 0 4px 0").set("font-size", "0.95em");
                    historyBody.add(header);
                    final var text = new StringBuilder();
                    text.append("params: ").append(a.params()).append(a.overridden() ? " (overridden)" : " (run default)").append('\n');
                    text.append("rc: ").append(a.rc()).append("  seconds: ").append(a.seconds()).append('\n');
                    text.append("outcome: ").append(a.reported_status() == null ? "(none yet)" : a.reported_status());
                    if (a.reported_detail() != null && !a.reported_detail().isBlank()) text.append(" - ").append(a.reported_detail());
                    historyBody.add(Panels.mono(text.toString()));
                }
            } catch (final Exception ex) {
                historyBody.removeAll();
                historyBody.add(Panels.error(client.errorText(ex)));
            }
        };
        // #265: the mid-task judge's own checkpoints for this task, ascending by turn - a timeline
        // of "is this still on track", not a list of outcomes to compare like History above. The
        // recommendation is shown up front; the full prompt it was given is collapsed behind
        // Details, same as CompareView already does for its own verbose, secondary output.
        final Runnable loadJudge = () -> {
            try {
                final var checkpoints = client.taskJudge(jobId, task.id());
                judgeBody.removeAll();
                if (checkpoints.isEmpty()) {
                    judgeBody.add(Panels.mono("(no judge checkpoints yet - either the judge isn't "
                            + "enabled for this task, or it hasn't reached its first interval)"));
                    return;
                }
                for (final var c : checkpoints) {
                    final var header = new com.vaadin.flow.component.html.H4("Turn " + c.turn());
                    header.getStyle().set("margin", "12px 0 4px 0").set("font-size", "0.95em");
                    judgeBody.add(header);
                    judgeBody.add(Panels.mono(c.recommendation()));
                    judgeBody.add(new com.vaadin.flow.component.details.Details("Prompt the judge saw", Panels.mono(c.prompt())));
                }
            } catch (final Exception ex) {
                judgeBody.removeAll();
                judgeBody.add(Panels.error(client.errorText(ex)));
            }
        };
        // #355/#355-followup: full operator visibility into this task, active or completed -
        // turns/compactions/tool_errors/tool_calls are now ALWAYS read live from the task's own
        // session file by BenchController.taskStats() (never a one-time manifest.json snapshot,
        // which #358 found can be flat wrong for a timed-out attempt) - whether the task is still
        // running, in its own post-session wrap-up/rescue/handoff tail, or long since done. Only
        // the still-live WALL TIME figure for a not-yet-finished task needs a frontend-side
        // fallback at all now (state != "done"): the SAME live JobLiveState this page's own
        // Sessions grid already uses for its own time/token columns (taskStats(sessions, taskId,
        // currentStep) above) - it already sums correctly across a retried/continued task's
        // several sessions and handles a still-running one's wall time (open-ended until "now" or
        // the next task's own start), rather than reporting null until the session ends.
        // one persistent UI slot per attempt (textBody: header+body, refreshed every poll like the
        // summary lines; chartsBody: built once below - speedChart/timingChart are the SAME chart
        // instances for this attempt's whole lifetime, updated in place via updateSeries()/setXaxis()
        // (#517 B) rather than torn down and rebuilt, never touched at all for an already-finished
        // attempt once its last real update has landed).
        record AttemptChartUi(Div textBody, Div chartsBody, ApexCharts speedChart, ApexCharts timingChart) {
        }
        final List<AttemptChartUi> attemptUis = new ArrayList<>();
        // identifies which attempt owns the currently-charted data and how many requests it had
        // last time its chart was drawn - the ONLY attempt still eligible to redraw is the current
        // LAST one (every earlier attempt is already finished, so its own chart never changes once
        // drawn), and even that one redraws only when a new request has actually completed (its
        // own request_speeds array grew), never on a bare poll tick with nothing new to show.
        final int[] lastChartedAttemptIndex = {-1};
        final int[] lastChartedPointCount = {-1};
        final Runnable loadStats = () -> {
            try {
                final var stats = client.taskStats(jobId, task.id());
                statsSummaryBody.removeAll();
                if ("not_started".equals(stats.path("state").asText(null))) {
                    statsSummaryBody.add(Panels.mono("(not started yet)"));
                    statsAttemptsBody.removeAll();
                    attemptUis.clear();
                    lastChartedAttemptIndex[0] = -1;
                    lastChartedPointCount[0] = -1;
                    return;
                }
                for (final var line : taskStatsLines(stats, taskStats(live.sessions(), task.id(), live.currentStep())))
                    statsSummaryBody.add(Panels.mono(line));
                // only worth its own text breakdown once there's more than one attempt to tell
                // apart - a single-attempt task would just duplicate the summary lines just added
                // above. Every attempt that actually made a request still gets its own chart
                // regardless (the operator asked for one per attempt, not one for the whole task) -
                // a single-attempt task's own chart just doesn't need an "Attempt N" label on it.
                final var sections = attemptSections(stats);
                final var multiAttempt = sections.size() > 1;
                while (attemptUis.size() < sections.size()) {
                    final var attemptLabel = multiAttempt ? " — Attempt " + (attemptUis.size() + 1) : "";
                    final var speedChart = buildRequestSpeedChart();
                    final var timingChart = buildRequestTimingChart();
                    final var chartsBody = new Div();
                    chartsBody.add(Panels.card("Prefill speed by request" + attemptLabel, speedChart),
                            Panels.card("Request timing" + attemptLabel, timingChart));
                    final var ui = new AttemptChartUi(new Div(), chartsBody, speedChart, timingChart);
                    attemptUis.add(ui);
                    statsAttemptsBody.add(ui.textBody(), ui.chartsBody());
                }
                for (int i = 0; i < sections.size(); i++) {
                    final var section = sections.get(i);
                    final var ui = attemptUis.get(i);
                    // the compaction tables below are worth showing for every attempt, whether or
                    // not there's more than one of them - unlike the header/body text (which would
                    // just duplicate the single-attempt summary above), so textBody() is always
                    // cleared and rebuilt, not just when multiAttempt
                    ui.textBody().removeAll();
                    if (multiAttempt) {
                        // #517 (A): the card's own sectionTitle() replaces the bare H4 this used to
                        // be - same information, now wearing the rest of the app's own panel chrome
                        ui.textBody().add(Panels.card(section.header(), Panels.mono(section.body())));
                    }
                    addCompactionEffectivenessTables(section, ui.textBody());
                    final var isLast = i == sections.size() - 1;
                    final var pointCount = section.requestSpeeds().size();
                    final var needsRedraw = isLast && (i != lastChartedAttemptIndex[0] || pointCount != lastChartedPointCount[0]);
                    if (!needsRedraw) continue;
                    updateRequestSpeedChart(ui.speedChart(), section.requestSpeeds(), section.compactionsBeforeRequest(),
                            section.judgeOnTrackRequests(), section.judgeOffTrackRequests());
                    updateRequestTimingChart(ui.timingChart(), section.requestSpeeds(), section.compactionsBeforeRequest(),
                            section.judgeOnTrackRequests(), section.judgeOffTrackRequests());
                    lastChartedAttemptIndex[0] = i;
                    lastChartedPointCount[0] = pointCount;
                }
            } catch (final Exception ex) {
                statsSummaryBody.removeAll();
                statsAttemptsBody.removeAll();
                attemptUis.clear();
                lastChartedAttemptIndex[0] = -1;
                lastChartedPointCount[0] = -1;
                statsSummaryBody.add(Panels.error(client.errorText(ex)));
            }
        };
        final Runnable loadAll = () -> {
            loadPrompt.run();
            loadDiff.run();
            loadHistory.run();
            loadJudge.run();
            loadStats.run();
            if (promptSettled[0] && diffSettled[0] && pollReg[0] != null) {
                pollReg[0].remove();
                pollReg[0] = null;
            }
        };
        dialog.addOpenedChangeListener(e -> {
            if (!e.isOpened() && pollReg[0] != null) {
                pollReg[0].remove();
                pollReg[0] = null;
            }
        });
        buildRestartTab(task, dialog, restartBody);
        openTaskTranscriptTab(task, dialog, transcriptBody);
        buildInterveneRow(task, interveneRow);
        dialog.open();
        loadAll.run();
        getUI().ifPresent(ui -> pollReg[0] = ui.addPollListener(e -> loadAll.run()));
    }

    /** #355-followup: rc is the harness's own internal exit code (ReferenceAgent/RunBenchSupport,
     *  see their own javadoc for the full reasoning) - a fixed, small set of values meaningful to
     *  someone who already knows the harness's own control flow, not to an operator reading the
     *  Stats tab cold. Translates every value a task's own rc can actually take; the raw code
     *  still rides along parenthetically for cross-referencing task_attempts.jsonl or the session
     *  log directly. `finish` (the model's own last-turn finish reason) only changes the rc=0
     *  text - "length" there means the session's last visible turn was cut off at the per-call
     *  output limit, worth knowing even though the task itself still reports success overall;
     *  null/absent (the common case) or any other value reads as a plain, unqualified success. */
    static String rcText(final int rc, final String finish) {
        return switch (rc) {
            case 0 -> "length".equals(finish) ? "finished, but its last turn was cut off at the output limit" : "finished normally";
            case 1 -> "hit its turn limit without finishing (rc=1)";
            case 2 -> "a transient infrastructure error, retries exhausted (rc=2)";
            case 3 -> "ran out of its own token budget (rc=3)";
            case 4 -> "its conversation history grew too large for the context window (rc=4)";
            case 124 -> "ran out of its own wall-clock time budget - a task-wall timeout (rc=124)";
            default -> "stopped with an unrecognized exit code (rc=" + rc + ")";
        };
    }

    /** #355-followup: found live - "bash: 99 (5 errors)" names the tool but not what it DOES,
     *  the same gap rcText() closed for a bare rc number. The model's own four tools (see
     *  ReferenceAgent.toolSpecs() for the full, verbatim descriptions it is actually given) in
     *  one short phrase each - plain enough to read inline next to a count, not the full
     *  tool-call description. An unrecognized name (should not happen - these four are the
     *  model's entire tool surface) still shows itself rather than going blank. */
    static String toolLabel(final String name) {
        return switch (name) {
            case "read" -> "read (read a file)";
            case "write" -> "write (create or overwrite a file)";
            case "edit" -> "edit (replace text within a file)";
            case "bash" -> "bash (run a shell command)";
            default -> name;
        };
    }

    /** #355: the Stats tab's own text for a FINISHED task (BenchController.taskStats() found a
     *  manifest.tasks entry - "done") or still running/mid-tail ("running") - pure, tested
     *  against real-shape fixtures, same convention as speedByContextRows()/
     *  compactionOverheadPoints() in RunDetailView. turns/compactions/tool_errors/tool_calls are
     *  ALWAYS populated (BenchController.taskStats() reads them live from the session file
     *  regardless of state - see its own javadoc); everything else beyond those - rc/finish/
     *  seconds/verification/wrap-up/rescue/handoff/check_score/compaction_overhead - is OPTIONAL
     *  and only ever present once "done" (wrap-up/rescue/handoff only for some tasks even then;
     *  check_score/compaction_overhead only once the whole run has finished scoring).
     *  `liveWallTime` is this dialog's own frontend-side live aggregation (JobDetailView.
     *  taskStats(sessions, taskId, currentStep), reused from the Plan grid) - the one figure the
     *  backend has no way to report for a task that hasn't finished yet, since it has no
     *  "elapsed so far" concept of its own. */
    static List<String> taskStatsLines(final JsonNode stats, final TaskStats liveWallTime) {
        final List<String> lines = new ArrayList<>();
        final boolean done = "done".equals(stats.path("state").asText(null));
        if (done) {
            lines.add("outcome: " + rcText(stats.path("rc").asInt(Integer.MIN_VALUE), stats.path("finish").asText(null)));
            lines.add("wall time: " + stats.path("seconds").asText("?") + "s   model turns: " + stats.path("turns").asText("0"));
        } else {
            lines.add("outcome: still running");
            lines.add("wall time so far: " + Math.round(liveWallTime.wallSec()) + "s   model turns so far: " + stats.path("turns").asText("0"));
        }
        lines.add("context compactions (history trims to control context size): " + stats.path("compactions").asText("0")
                + "   failed tool calls: " + stats.path("tool_errors").asText("0"));
        final var v = stats.path("verification");
        if (v.isObject()) {
            final String verdict = v.path("green").isNull() ? "could not run" : v.path("green").asBoolean(false) ? "GREEN" : "RED";
            lines.add("harness verification: " + verdict + " (" + v.path("executed").asInt(0) + " tests, " + v.path("failed").asInt(0) + " failed)");
        }
        if (stats.hasNonNull("rescue_seconds")) {
            final boolean succeeded = stats.path("rescue_succeeded").asBoolean(false);
            lines.add("rescue attempt (" + stats.path("rescue_seconds").asText("?") + "s): "
                    + (succeeded ? "succeeded" : rcText(stats.path("rescue_rc").asInt(Integer.MIN_VALUE), null)));
        }
        if (stats.hasNonNull("wrapup_seconds")) {
            lines.add("wrap-up attempt (" + stats.path("wrapup_seconds").asText("?") + "s): "
                    + rcText(stats.path("wrapup_rc").asInt(Integer.MIN_VALUE), null));
        }
        if (stats.hasNonNull("handoff_seconds")) {
            lines.add("handoff: " + (stats.path("handoff_by_harness").asBoolean(false) ? "harness-authored fallback (#351)" : "written by the model")
                    + ", " + stats.path("handoff_chars").asText("0") + " chars");
        }
        final var tc = stats.path("tool_calls");
        if (tc.isObject() && tc.size() > 0) {
            lines.add("tool calls (the four actions the model can take in its own coding session):");
            lines.addAll(toolCallLines(tc));
        }
        final var cs = stats.path("check_score");
        if (cs.isObject()) {
            lines.add("check score: " + cs.path("score_pct").asText("?") + "% (" + cs.path("points").path(0).asText("?") + "/" + cs.path("points").path(1).asText("?") + ")");
        }
        final var co = stats.path("compaction_overhead");
        if (co.isObject()) {
            lines.add("compaction overhead: " + co.path("overhead_sec").asText("?") + "s across " + co.path("events").asText("0") + " event(s)");
        }
        return lines;
    }

    private static List<String> toolCallLines(final JsonNode toolCalls) {
        final List<String> lines = new ArrayList<>();
        final var names = new ArrayList<>(toolCalls.propertyNames());
        Collections.sort(names);
        for (final var name : names) {
            final var c = toolCalls.path(name);
            final int errors = c.path("errors").asInt(0);
            lines.add("  " + toolLabel(name) + ": " + c.path("count").asInt(0) + (errors > 0 ? " (" + errors + (errors == 1 ? " error" : " errors") + ")" : ""));
        }
        return lines;
    }

    /** #355-followup-2: found live - a raw "{"task_wall_sec":32400,...}" map dump read as noise
     *  next to the same section's own labeled "wall time"/"model turns" lines. Every knob
     *  RESTART_OVERRIDE_KEYS (BenchController) can actually carry, named the way an operator
     *  deciding whether to adjust it would recognize - same convention as toolLabel() above. An
     *  unrecognized key (should not happen - this list is kept in step with RESTART_OVERRIDE_KEYS)
     *  still shows itself rather than going blank. */
    static String paramLabel(final String key) {
        return switch (key) {
            case "task_wall_sec" -> "wall-clock budget (s)";
            case "task_tokens" -> "token budget";
            case "max_turns" -> "turn limit";
            case "wrapup_wall_sec" -> "wrap-up wall-clock budget (s)";
            case "wrapup_tokens" -> "wrap-up token budget";
            case "first_token_timeout_sec" -> "first-token timeout (s)";
            case "judge_enabled" -> "judge enabled";
            case "judge_every_n_turns" -> "judge interval (turns)";
            case "judge_model" -> "judge model";
            case "budget_rescue_enabled" -> "budget-rescue enabled";
            case "compaction_trigger" -> "compaction trigger (tokens)";
            case "max_tokens" -> "max response tokens";
            case "temperature" -> "temperature";
            case "top_p" -> "top-p";
            case "top_k" -> "top-k";
            case "repetition_penalty" -> "repetition penalty";
            case "reasoning_effort" -> "reasoning effort";
            case "rescue_wall_sec" -> "rescue wall-clock budget (s)";
            case "rescue_tokens" -> "rescue token budget";
            case "rescue_keep_recent_turns" -> "rescue: turns kept";
            case "lesson_recording_enabled" -> "cross-task lesson recording enabled";
            default -> key;
        };
    }

    private static List<String> paramLines(final JsonNode params) {
        final List<String> lines = new ArrayList<>();
        final var names = new ArrayList<>(params.propertyNames());
        Collections.sort(names);
        for (final var name : names) lines.add("  " + paramLabel(name) + ": " + params.path(name).asText());
        return lines;
    }

    /** Splits `text` on `delimiter`, but only where bracket depth - ( { [ counted together - is
     *  zero: a delimiter inside a parenthetical aside (e.g. the ", " inside "(32-hex, low-16-bits
     *  zero, ...)") is part of that aside, never a split point. */
    private static List<String> splitAtDepthZero(final String text, final String delimiter) {
        final List<String> parts = new ArrayList<>();
        int depth = 0, start = 0, i = 0;
        while (i < text.length()) {
            final char c = text.charAt(i);
            if (c == '(' || c == '{' || c == '[') { depth++; i++; }
            else if (c == ')' || c == '}' || c == ']') { depth--; i++; }
            else if (depth == 0 && text.startsWith(delimiter, i)) {
                parts.add(text.substring(start, i).trim());
                i += delimiter.length();
                start = i;
            } else i++;
        }
        parts.add(text.substring(start).trim());
        return parts;
    }

    /** a loose but deliberately narrow signal that a line is reporting a VERIFICATION outcome
     *  (test counts, build status) rather than describing what was implemented - the operator's
     *  own question ("did this work, and is there evidence?") this project's whole Stats-tab
     *  vocabulary already answers with: GREEN/RED (BenchController's own verification.green
     *  rendering), BUILD SUCCESSFUL (Gradle's own output these reports quote verbatim), test(s)/
     *  pass(ed)/passing, regression(s), failure(s). Deliberately NOT "build" alone (too generic -
     *  "build the request" reads as implementation, not verification). */
    private static final java.util.regex.Pattern VERIFICATION_SIGNAL = java.util.regex.Pattern.compile(
            "(?i)\\b(tests?|passed?|passing|verified|verify|verification|green|red|regressions?|failures?)\\b|BUILD SUCCESSFUL");

    /** Removes every top-level "(...)" aside from `text` (nested parens tracked correctly). The
     *  space immediately before a removed "(" is dropped too (found live: a blanket "strip any
     *  space before punctuation" regex is NOT safe here - this domain's own text is full of
     *  colons and periods that are not sentence punctuation at all, e.g. ":common" module paths
     *  and "./gradlew" relative paths, which a blanket regex corrupted into ":common"-glued-to-
     *  its-neighbor and "root./gradlew"). Dropping only the ONE space that sat directly against
     *  the aside being removed - "green (...)." becoming "green." - needs no such regex, and
     *  never touches text the aside removal never touched. */
    private static String stripParentheticals(final String text) {
        final StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '(') {
                if (depth == 0 && !out.isEmpty() && out.charAt(out.length() - 1) == ' ') out.setLength(out.length() - 1);
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0) {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    /** #355-followup-2: found live - the model's own free-text report (`reported_detail`) is one
     *  dense run-on paragraph: a long implementation sentence packed with parenthetical asides,
     *  then a verification sentence. Splitting on sentence boundaries first, then on top-level
     *  semicolons/commas within each sentence (never inside a parenthetical - see
     *  splitAtDepthZero()) turns that into one line per sentence, with a multi-clause sentence's
     *  own clauses listed one per line beneath it.
     *
     *  The operator asked to drop the implementation narrative entirely - "how it was built" isn't
     *  what the Stats tab needs to answer - and to drop the verbose per-suite test-count
     *  parenthetical too, keeping just the headline pass/build line. Only VERIFICATION_SIGNAL
     *  lines survive, each with its own parenthetical asides stripped. A report with no
     *  verification signal at all ends up with nothing to show - correct, not a bug: there is
     *  genuinely no verification evidence to surface. */
    private static List<String> detailLines(final String detail) {
        final List<String> lines = new ArrayList<>();
        for (final String sentence : splitAtDepthZero(detail, ". ")) {
            if (sentence.isBlank()) continue;
            final List<String> clauses = new ArrayList<>();
            for (final String semiClause : splitAtDepthZero(sentence, "; "))
                clauses.addAll(splitAtDepthZero(semiClause, ", "));
            if (clauses.size() <= 1) {
                if (VERIFICATION_SIGNAL.matcher(sentence).find()) lines.add(stripParentheticals(sentence));
            } else {
                for (final String clause : clauses)
                    if (!clause.isBlank() && VERIFICATION_SIGNAL.matcher(clause).find()) lines.add("  " + stripParentheticals(clause));
            }
        }
        return lines;
    }

    record RequestSpeedPoint(int seq, Double prefillTokPerSec, Double generationTokPerSec,
                              Double timeToFirstTokenSec, Double totalTimeSec, Integer contextTokens) {
    }

    /** One attempt's own "request_speeds" array (BenchController.taskStats()'s "attempts[].
     *  request_speeds", seq-ordered, already filtered to that attempt's own session) - pure/
     *  testable, same convention as RunDetailView.speedByContextRows(). Takes the array node
     *  directly (not the whole stats object) so it works the same whether called per-attempt
     *  (the normal case - the operator asked for a chart per attempt, not one combined chart for
     *  the whole task) or against any other array of this same shape. generation_tok_per_sec,
     *  time_to_first_token_sec and total_time_sec are all optional on the backend (a line can
     *  carry prefill speed without any of them) - null here too when absent, same as a missing
     *  prefill_tok_per_sec. contextTokens (the request's own "prompt_tokens" - how much context
     *  was occupied for this one request) is NOT optional on the backend - parseRequestSpeedEntry()
     *  already requires it to be present for a line to count at all - but is still read via the
     *  same isNumber()-guarded pattern as the others for consistency, not because it can be null
     *  in practice. */
    static List<RequestSpeedPoint> requestSpeedPoints(final JsonNode arr) {
        if (!arr.isArray() || arr.isEmpty()) return List.of();
        final List<RequestSpeedPoint> points = new ArrayList<>();
        for (final var r : arr) {
            final var prefillTps = r.path("prefill_tok_per_sec");
            final var generationTps = r.path("generation_tok_per_sec");
            final var ttft = r.path("time_to_first_token_sec");
            final var totalTime = r.path("total_time_sec");
            final var contextTokens = r.path("prompt_tokens");
            points.add(new RequestSpeedPoint(r.path("seq").asInt(0),
                    prefillTps.isNumber() ? prefillTps.doubleValue() : null,
                    generationTps.isNumber() ? generationTps.doubleValue() : null,
                    ttft.isNumber() ? ttft.doubleValue() : null,
                    totalTime.isNumber() ? totalTime.doubleValue() : null,
                    contextTokens.isNumber() ? contextTokens.intValue() : null));
        }
        return points;
    }

    /** BenchController.taskStats()'s own "attempts[].compactions_before_request" - the 1-based
     *  request ordinals a compaction landed immediately before, for this one attempt. Pure,
     *  same convention as requestSpeedPoints() just above. Generic enough (just a JSON array of
     *  numbers -> a List<Integer>) that attemptSections() below reuses it as-is for
     *  "judge_on_track_requests"/"judge_off_track_requests" too - same shape, different field. */
    static List<Integer> compactionsBeforeRequest(final JsonNode arr) {
        if (!arr.isArray() || arr.isEmpty()) return List.of();
        final List<Integer> positions = new ArrayList<>();
        for (final var p : arr) if (p.isNumber()) positions.add(p.asInt());
        return positions;
    }

    /** The x-axis is one category per request (points.size() can run past 100+ on a long task,
     *  found live on T9). Categories stay real, unique numbers ("1".."150", never blank) - a
     *  blanked-out "" would be ambiguous on hover/tooltip (shared by every thinned-out position).
     *  Label DENSITY is thinned separately, in the chart's own JS label formatter
     *  (requestSpeedLabelFormatterJs()) - that only changes what's DRAWN under each tick, never
     *  the category values the series (and the compaction column series - see
     *  compactionColumnData() below) both still address by their own shared category index. */
    static String[] requestSpeedCategories(final int count) {
        final var categories = new String[count];
        for (int i = 0; i < count; i++) categories[i] = String.valueOf(i + 1);
        return categories;
    }

    /** A label under every single request (100+ on a long task) prints as an unreadable smear -
     *  show one only on every tenth request (#10, #20, ...). A raw JS function string: this
     *  wrapper has no per-tick Java callback hook for xaxis labels (same limitation its own
     *  tooltip custom() already works around - see RunDetailView.speedByContextTooltipJs()). */
    static String requestSpeedLabelFormatterJs() {
        return "function(val) { return (parseInt(val, 10) % 10 === 0) ? val : ''; }";
    }

    /** The context-tokens axis on both charts below - raw counts read as "50000.00" (ApexCharts'
     *  own default 2-decimal numeric format) once context grows into the tens of thousands;
     *  "50k" (1 decimal only when the thousands aren't round, e.g. "52.3k") reads at a glance the
     *  same way token-budget figures already do everywhere else in this UI. */
    static String tokenCountLabelFormatterJs() {
        return """
                function(val) {
                  if (val === null || val === undefined) return '';
                  var v = Math.round(val);
                  if (Math.abs(v) >= 1000) return (Math.round(v / 100) / 10) + 'k';
                  return v.toString();
                }
                """;
    }

    /** tokenCountLabelFormatterJs() wrapped as a yaxis.Labels - a small builder-less helper since
     *  (unlike the xaxis.Labels used elsewhere here) nothing else on this Labels needs setting. */
    private static com.github.appreciated.apexcharts.config.yaxis.Labels tokenCountLabels() {
        final var labels = new com.github.appreciated.apexcharts.config.yaxis.Labels();
        labels.setFormatter(tokenCountLabelFormatterJs());
        return labels;
    }

    /** The prefill/generation tok/s and time-to-first-token/total-time axes on both charts below -
     *  ApexCharts' own default 2-decimal numeric format reads as "92.20"/"45.67" even where the
     *  operator only needs the whole-number trend at a glance (the exact figure already rides
     *  along in the tooltip, see requestSpeedTooltipJs()/requestTimingTooltipJs()); dropping the
     *  decimal places declutters a 220px-tall chart already carrying two independent left axes. */
    static String integerLabelFormatterJs() {
        return """
                function(val) {
                  if (val === null || val === undefined) return '';
                  return Math.round(val).toString();
                }
                """;
    }

    /** integerLabelFormatterJs() wrapped as a yaxis.Labels - same convention as tokenCountLabels()
     *  above. */
    private static com.github.appreciated.apexcharts.config.yaxis.Labels integerLabels() {
        final var labels = new com.github.appreciated.apexcharts.config.yaxis.Labels();
        labels.setFormatter(integerLabelFormatterJs());
        return labels;
    }

    /** One value per request ordinal (null everywhere except where a compaction landed
     *  immediately before - BenchController.taskStats()'s own "attempts[].compactions_before_request")
     *  for a SECOND, thin-column series plotted on the exact same category axis as whichever
     *  chart calls this (addRequestSpeedChart()/addRequestTimingChart() below) - not an ApexCharts
     *  x-axis annotation. ApexCharts 3.54.1's own x-axis annotation only resolves a category
     *  position when xaxis.type is the literal string "category" (confirmed by reading its
     *  source, modules/annotations/helpers.js's getX1X2()), and this addon's own
     *  XAxisType.CATEGORIES enum constant serializes to the non-standard value "categories"
     *  instead - so that path is unreachable through this addon, and the annotation silently
     *  fails to render (confirmed live against T11's real data: its SVG group mounted with zero
     *  children). A second data series sidesteps the whole annotation-resolution path: it shares
     *  the SAME category axis as the chart's own line(s), so its column lands on the correct
     *  request by construction, not by any separate coordinate math. `peak` is the caller's own
     *  choice of how tall to draw it (that chart's own series max - across ALL of that chart's
     *  own series, when it has more than one, so the column reaches the actual top of the plot
     *  rather than just one line's own max) - kept a plain parameter rather than hard-coding
     *  which RequestSpeedPoint field to read, since different charts plot different fields.
     *  Positions past requestCount are dropped rather than pointing at a request that was never
     *  plotted (the ordinal-alignment caveat BenchController's own javadoc notes: a dropped
     *  request_speeds entry can shift later ordinals by one). */
    static Double[] compactionColumnData(final List<Integer> compactionsBeforeRequest, final int requestCount, final double peak) {
        final Double[] data = new Double[requestCount];
        for (final Integer position : compactionsBeforeRequest) {
            if (position == null || position < 1 || position > requestCount) continue;
            data[position - 1] = peak;
        }
        return data;
    }

    /** This chart's own tooltip, read from series[0] (prefill tok/s), series[2] (generation
     *  tok/s) and series[3] (context tokens) - the addon's Tooltip bean has no "enabledOnSeries"
     *  (the real ApexCharts.js option for this), so a raw JS function is the only way to leave
     *  the compaction column (series[1]) and the two judge columns (series[4]/[5], added later)
     *  out of the tooltip entirely, same pattern as RunDetailView.speedByContextTooltipJs().
     *  `shared`/`intersect` stay at their defaults (shared, not intersect) so hovering anywhere
     *  near a category still shows it - these column series' own invisible-until-marked columns
     *  would otherwise need a literal hover to trigger anything. generation tok/s is optional per
     *  request (see RequestSpeedPoint's own javadoc) - its line is left out of the tooltip when
     *  absent rather than printing a bare "null". */
    static String requestSpeedTooltipJs() {
        return """
                function(struct) {
                  var i = struct.dataPointIndex;
                  var prefill = struct.series[0][i];
                  var generation = struct.series[2][i];
                  var context = struct.series[3][i];
                  if (prefill === null || prefill === undefined) return '';
                  var html = '<div style="padding:8px 12px">' +
                    '<div style="font-weight:600;margin-bottom:4px">request #' + (i + 1) + '</div>' +
                    '<div>prefill tok/s: ' + prefill + '</div>';
                  if (generation !== null && generation !== undefined) {
                    html += '<div>generation tok/s: ' + generation + '</div>';
                  }
                  if (context !== null && context !== undefined) {
                    html += '<div>context tokens: ' + context + '</div>';
                  }
                  html += '</div>';
                  return html;
                }
                """;
    }

    /** Prefill tok/s, generation tok/s, AND context size (prompt_tokens - how much context was
     *  occupied for that one request) for every request ONE attempt's own session made, in order
     *  - the operator asked for a chart per attempt that actually made a request, not one chart
     *  combining every attempt's requests together (a false start and the real redo read as two
     *  different sessions with their own separate stories, not one continuous timeline). x-axis
     *  is this attempt's own 1st/2nd/3rd/... request, not the request's run-wide seq number,
     *  which would read as an arbitrary large offset for a task that ran late in the run. A thin
     *  (1.5px) stroke on every line series - the default width reads as a solid blob once 100+
     *  points are packed into one chart. A request a compaction landed right before, or a judge
     *  checkpoint landed at, is marked by a thin column (real series - see
     *  compactionColumnData() above for why, not an annotation) - compaction red, judge green for
     *  ON TRACK and amber for OFF TRACK, so the operator can see whether speed actually dips
     *  there or correlates with the judge's own verdict. Prefill and generation each get their
     *  OWN left-hand axis, not a shared one - the operator found generation's own dynamics
     *  unreadable once squeezed onto prefill's much larger scale (it typically runs well below
     *  prefill - decode vs. prompt processing). ApexCharts supports any number of independent
     *  y-axes stacked on one side, not just one per side (confirmed via its own source,
     *  dimensions/YAxis.js's getTotalYAxisWidth(): every yaxis entry adds its own width to
     *  yAxisWidthLeft or yAxisWidthRight purely based on its own `opposite` flag, with no cap on
     *  how many share a side) - two visible, independently-scaled axes with `opposite` left unset
     *  (false) is exactly that, both landing on the left. Context tokens is a wholly different
     *  unit and a much larger raw number (growing into the tens of thousands) - it keeps its own
     *  axis on the right (yaxis[3], opposite=true), same reason it got its own axis on the
     *  request-timing chart. yaxis[1] (compaction), yaxis[4] and yaxis[5] (the two judge columns)
     *  all point their own seriesName back at "prefill tok/s" so they merge onto prefill's own
     *  scale and reach that line's own peak - ApexCharts' own yaxis.seriesName mechanism
     *  (confirmed via its source, modules/CoreUtils.js's setSeriesYAxisMappings()) merges any
     *  yaxis entries that share one seriesName into a single scale. Added to `container`
     *  directly, same as every other Stats-tab element. */
    /** #517 (B): split from the former addRequestSpeedChart() into a build-once (static config:
     *  theme/colors/stroke/plotOptions/legend/tooltip/yaxis - none of it data-dependent) half and
     *  updateRequestSpeedChart() below (the data-dependent half: categories + the 6 series) - the
     *  actively-updating attempt's chart is now updated in place via updateSeries()/setXaxis() each
     *  poll instead of torn down and rebuilt, the same technique #476/PR3's own job-level latency
     *  chart already uses, for the same reason (a full rebuild every poll flashes/flickers). */
    static ApexCharts buildRequestSpeedChart() {
        final var chart = ApexChartsBuilder.get()
                .withChart(ChartBuilder.get().withType(Type.LINE).withBackground("transparent").build())
                .withTheme(ThemeBuilder.get().withMode(Mode.DARK).build())
                .withColors("#008FFB", "#e74c3c", "#00E396", "#546E7A", "#27ae60", "#e67e22")
                .withStroke(com.github.appreciated.apexcharts.config.builder.StrokeBuilder.get()
                        // the bar's own stroke paints OUTSIDE its fill too, so even a 2px stroke
                        // on a 1px-wide column nearly triples its visual footprint - 0 here lets
                        // columnWidth below be the only thing controlling how wide it reads
                        .withWidthArray(1.5, 0.0, 1.5, 1.5, 0.0, 0.0).build())
                .withPlotOptions(com.github.appreciated.apexcharts.config.builder.PlotOptionsBuilder.get()
                        .withBar(com.github.appreciated.apexcharts.config.plotoptions.builder.BarBuilder.get()
                                // 16% of this chart's own per-category slot renders at ~1.5px wide,
                                // matching the main line's own 1.5px stroke (measured live)
                                .withColumnWidth("16%").build())
                        .build())
                .withLegend(com.github.appreciated.apexcharts.config.builder.LegendBuilder.get().withShow(false).build())
                .withTooltip(com.github.appreciated.apexcharts.config.builder.TooltipBuilder.get()
                        .withCustom(requestSpeedTooltipJs()).build())
                .withYaxis(
                        YAxisBuilder.get().withSeriesName("prefill tok/s").withLabels(integerLabels()).build(),
                        YAxisBuilder.get().withSeriesName("prefill tok/s").withShow(false).build(),
                        YAxisBuilder.get().withSeriesName("generation tok/s").withLabels(integerLabels()).build(),
                        YAxisBuilder.get().withSeriesName("context tokens").withOpposite(true)
                                .withLabels(tokenCountLabels()).build(),
                        YAxisBuilder.get().withSeriesName("prefill tok/s").withShow(false).build(),
                        YAxisBuilder.get().withSeriesName("prefill tok/s").withShow(false).build())
                .build();
        chart.setHeight("220px");
        return chart;
    }

    static void updateRequestSpeedChart(final ApexCharts chart, final List<RequestSpeedPoint> points,
                                          final List<Integer> compactionsBeforeRequest,
                                          final List<Integer> judgeOnTrackRequests, final List<Integer> judgeOffTrackRequests) {
        if (points.isEmpty()) return;
        final var categories = requestSpeedCategories(points.size());
        final var xaxisLabels = new com.github.appreciated.apexcharts.config.xaxis.Labels();
        xaxisLabels.setFormatter(requestSpeedLabelFormatterJs());
        chart.setXaxis(XAxisBuilder.get().withCategories(categories).withLabels(xaxisLabels)
                .withTitle(com.github.appreciated.apexcharts.config.xaxis.builder.TitleBuilder.get()
                        .withText("request # (red: a compaction landed right before it; green/amber: a judge checkpoint landed on track/off track)").build())
                .build());
        final var speedSeries = SeriesBuilder.get().withName("prefill tok/s")
                .withData(points.stream().map(RequestSpeedPoint::prefillTokPerSec).toArray(Double[]::new)).build();
        final var prefillPeak = points.stream().map(RequestSpeedPoint::prefillTokPerSec).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(0);
        final var compactionSeries = SeriesBuilder.get().withName("compaction")
                .withType(com.github.appreciated.apexcharts.config.series.SeriesType.COLUMN)
                .withData(compactionColumnData(compactionsBeforeRequest, points.size(), prefillPeak)).build();
        final var generationSeries = SeriesBuilder.get().withName("generation tok/s")
                .withData(points.stream().map(RequestSpeedPoint::generationTokPerSec).toArray(Double[]::new)).build();
        final var contextSeries = SeriesBuilder.get().withName("context tokens")
                .withData(points.stream().map(RequestSpeedPoint::contextTokens).toArray(Integer[]::new)).build();
        final var judgeOnTrackSeries = SeriesBuilder.get().withName("judge on track")
                .withType(com.github.appreciated.apexcharts.config.series.SeriesType.COLUMN)
                .withData(compactionColumnData(judgeOnTrackRequests, points.size(), prefillPeak)).build();
        final var judgeOffTrackSeries = SeriesBuilder.get().withName("judge off track")
                .withType(com.github.appreciated.apexcharts.config.series.SeriesType.COLUMN)
                .withData(compactionColumnData(judgeOffTrackRequests, points.size(), prefillPeak)).build();
        chart.updateSeries(speedSeries, compactionSeries, generationSeries, contextSeries, judgeOnTrackSeries, judgeOffTrackSeries);
    }

    /** This chart's own tooltip, read from series[0] (time to first token), series[1] (total
     *  time) and series[3] (context tokens) - same reason as requestSpeedTooltipJs() above: the
     *  compaction column (series[2] here) and the two judge columns (series[4]/[5], added later)
     *  have nothing meaningful to show (their value is just this chart's own peak - see
     *  compactionColumnData()'s javadoc), so a custom tooltip is the only way to leave them out
     *  via this addon's Tooltip bean (no "enabledOnSeries"). */
    static String requestTimingTooltipJs() {
        return """
                function(struct) {
                  var i = struct.dataPointIndex;
                  var ttft = struct.series[0][i];
                  var total = struct.series[1][i];
                  var context = struct.series[3][i];
                  if (ttft === null || ttft === undefined) return '';
                  var html = '<div style="padding:8px 12px">' +
                    '<div style="font-weight:600;margin-bottom:4px">request #' + (i + 1) + '</div>' +
                    '<div>time to first token (s): ' + ttft + '</div>';
                  if (total !== null && total !== undefined) {
                    html += '<div>total time (s): ' + total + '</div>';
                  }
                  if (context !== null && context !== undefined) {
                    html += '<div>context tokens: ' + context + '</div>';
                  }
                  html += '</div>';
                  return html;
                }
                """;
    }

    /** Time to first token, total request time, AND context size (prompt_tokens - how much
     *  context was occupied for that one request), same per-attempt x-axis (request #) as
     *  addRequestSpeedChart() above - a separate chart, not more series on that one (the operator
     *  asked for "a second chart", not more series on the first). Time-to-first-token and total
     *  time each get their OWN left-hand axis, not a shared one - same reason the prefill-speed
     *  chart's two tok/s series do (#389): total time can run well above time-to-first-token (it
     *  includes the whole generation phase too), so sharing one scale squashed
     *  time-to-first-token's own dynamics down near zero. ApexCharts supports any number of
     *  independent y-axes stacked on one side, not just one per side (confirmed via its own
     *  source, dimensions/YAxis.js's getTotalYAxisWidth() - see addRequestSpeedChart()'s own
     *  javadoc for the exact mechanism) - two visible axes with `opposite` left unset both land on
     *  the left. Context tokens is a wholly different unit and typically a much larger raw number
     *  (growing into the tens of thousands), so it keeps its own axis on the right (yaxis[3],
     *  opposite=true). yaxis[2] (compaction), yaxis[4] and yaxis[5] (the two judge columns) all
     *  point their own seriesName back at "time to first token (s)" so they merge onto that
     *  line's own scale and reach its own peak - ApexCharts' own yaxis.seriesName mechanism
     *  (confirmed via its source, modules/CoreUtils.js's setSeriesYAxisMappings()). Compaction and
     *  judge both marked the same way as addRequestSpeedChart() - real column series, not
     *  annotations (see compactionColumnData()'s own javadoc for why), legend hidden and tooltip
     *  custom for the same reason that chart's is. */
    /** #517 (B): same split as buildRequestSpeedChart()/updateRequestSpeedChart() above, for the
     *  same reason - see that pair's own javadoc. */
    static ApexCharts buildRequestTimingChart() {
        final var chart = ApexChartsBuilder.get()
                .withChart(ChartBuilder.get().withType(Type.LINE).withBackground("transparent").build())
                .withTheme(ThemeBuilder.get().withMode(Mode.DARK).build())
                .withColors("#775DD0", "#FEB019", "#e74c3c", "#546E7A", "#27ae60", "#e67e22")
                .withStroke(com.github.appreciated.apexcharts.config.builder.StrokeBuilder.get()
                        .withWidthArray(1.5, 1.5, 0.0, 1.5, 0.0, 0.0).build())
                .withPlotOptions(com.github.appreciated.apexcharts.config.builder.PlotOptionsBuilder.get()
                        .withBar(com.github.appreciated.apexcharts.config.plotoptions.builder.BarBuilder.get()
                                .withColumnWidth("16%").build())
                        .build())
                .withLegend(com.github.appreciated.apexcharts.config.builder.LegendBuilder.get().withShow(false).build())
                .withTooltip(com.github.appreciated.apexcharts.config.builder.TooltipBuilder.get()
                        .withCustom(requestTimingTooltipJs()).build())
                .withYaxis(
                        YAxisBuilder.get().withSeriesName("time to first token (s)").withLabels(integerLabels()).build(),
                        YAxisBuilder.get().withSeriesName("total time (s)").withLabels(integerLabels()).build(),
                        YAxisBuilder.get().withSeriesName("time to first token (s)").withShow(false).build(),
                        YAxisBuilder.get().withSeriesName("context tokens").withOpposite(true)
                                .withLabels(tokenCountLabels()).build(),
                        YAxisBuilder.get().withSeriesName("time to first token (s)").withShow(false).build(),
                        YAxisBuilder.get().withSeriesName("time to first token (s)").withShow(false).build())
                .build();
        chart.setHeight("220px");
        return chart;
    }

    static void updateRequestTimingChart(final ApexCharts chart, final List<RequestSpeedPoint> points,
                                           final List<Integer> compactionsBeforeRequest,
                                           final List<Integer> judgeOnTrackRequests, final List<Integer> judgeOffTrackRequests) {
        if (points.isEmpty()) return;
        final var categories = requestSpeedCategories(points.size());
        final var xaxisLabels = new com.github.appreciated.apexcharts.config.xaxis.Labels();
        xaxisLabels.setFormatter(requestSpeedLabelFormatterJs());
        chart.setXaxis(XAxisBuilder.get().withCategories(categories).withLabels(xaxisLabels)
                .withTitle(com.github.appreciated.apexcharts.config.xaxis.builder.TitleBuilder.get()
                        .withText("request # (red: a compaction landed right before it; green/amber: a judge checkpoint landed on track/off track)").build())
                .build());
        final var ttftSeries = SeriesBuilder.get().withName("time to first token (s)")
                .withData(points.stream().map(RequestSpeedPoint::timeToFirstTokenSec).toArray(Double[]::new)).build();
        final var totalTimeSeries = SeriesBuilder.get().withName("total time (s)")
                .withData(points.stream().map(RequestSpeedPoint::totalTimeSec).toArray(Double[]::new)).build();
        final var ttftPeak = points.stream().map(RequestSpeedPoint::timeToFirstTokenSec).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(0);
        final var compactionSeries = SeriesBuilder.get().withName("compaction")
                .withType(com.github.appreciated.apexcharts.config.series.SeriesType.COLUMN)
                .withData(compactionColumnData(compactionsBeforeRequest, points.size(), ttftPeak)).build();
        final var contextSeries = SeriesBuilder.get().withName("context tokens")
                .withData(points.stream().map(RequestSpeedPoint::contextTokens).toArray(Integer[]::new)).build();
        final var judgeOnTrackSeries = SeriesBuilder.get().withName("judge on track")
                .withType(com.github.appreciated.apexcharts.config.series.SeriesType.COLUMN)
                .withData(compactionColumnData(judgeOnTrackRequests, points.size(), ttftPeak)).build();
        final var judgeOffTrackSeries = SeriesBuilder.get().withName("judge off track")
                .withType(com.github.appreciated.apexcharts.config.series.SeriesType.COLUMN)
                .withData(compactionColumnData(judgeOffTrackRequests, points.size(), ttftPeak)).build();
        chart.updateSeries(ttftSeries, totalTimeSeries, compactionSeries, contextSeries, judgeOnTrackSeries, judgeOffTrackSeries);
    }

    /** One row of the "compaction distribution" table: which request this compaction landed right
     *  after, and how many requests elapsed since the attempt's previous one (null for the first -
     *  nothing to compare against yet). */
    record CompactionDistributionRow(int requestNumber, Integer requestsSincePrevious) {
    }

    /** One row of the "reclaimed context" table. reclaimedTokens is the REAL figure (this
     *  compaction's own promptTokensBefore minus the NEXT request's actual input tokens), not a
     *  stubbed-message count - found live (2026-10-19/T21) that the two can tell very different
     *  stories. Null when no later request exists yet in this attempt to measure against.
     *  promptTokensBefore is null for an on-load preload compaction, which has no "before" reading
     *  to report (#505 - both columns render as "–" for such a row, rather than a misleading 0 and
     *  the large negative number it used to produce once a later request's size became known). */
    record CompactionReclaimedRow(int requestNumber, Long promptTokensBefore, Long reclaimedTokens) {
    }

    /** The two per-attempt compaction-effectiveness tables the operator asked for: where compactions
     *  landed among this attempt's own requests, and how many tokens each one actually reclaimed -
     *  the REAL figure, not a stubbed-message count (see CompactionReclaimedRow's own javadoc for
     *  why that distinction matters). Built fresh every refresh, same as the charts above - cheap
     *  enough not to need their own redraw-throttling. A fixed height (not setAllRowsVisible), same
     *  reasoning as buildRequestsGrid()'s own: a long attempt can have dozens of compactions, and an
     *  unbounded-height table would dominate the page instead of scrolling internally. */
    static void addCompactionEffectivenessTables(final AttemptSection section, final Div container) {
        if (section.compactionDistribution().isEmpty()) return;
        final var distribution = new Grid<>(CompactionDistributionRow.class, false);
        distribution.addColumn(CompactionDistributionRow::requestNumber).setHeader("request #").setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        distribution.addColumn(r -> Fmt.count(r.requestsSincePrevious() == null ? null : r.requestsSincePrevious().longValue()))
                .setHeader("requests since previous").setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        distribution.setItems(section.compactionDistribution());
        distribution.setHeight("240px");
        container.add(Panels.card("Compaction distribution (" + section.compactionDistribution().size() + " compactions)", distribution));

        final var reclaimed = new Grid<>(CompactionReclaimedRow.class, false);
        reclaimed.addColumn(CompactionReclaimedRow::requestNumber).setHeader("request #").setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        reclaimed.addColumn(r -> Fmt.count(r.promptTokensBefore())).setHeader("prompt tokens before").setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        reclaimed.addColumn(r -> Fmt.count(r.reclaimedTokens())).setHeader("reclaimed tokens").setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        reclaimed.setItems(section.compactionReclaimed());
        reclaimed.setHeight("240px");
        container.add(Panels.card("Reclaimed context per compaction", reclaimed));
    }

    record AttemptSection(String header, String body, List<RequestSpeedPoint> requestSpeeds, List<Integer> compactionsBeforeRequest,
                           List<Integer> judgeOnTrackRequests, List<Integer> judgeOffTrackRequests,
                           List<CompactionDistributionRow> compactionDistribution, List<CompactionReclaimedRow> compactionReclaimed) {
    }

    /** #355-followup-2: one section per session file sharing a task's label, oldest first,
     *  matching BenchController.taskStats()'s own "attempts" array - the per-attempt params/
     *  outcome/turns/compactions/tool_calls breakdown the operator asked to see individually
     *  rather than collapsed into the single aggregate taskStatsLines() above reports. Pure and
     *  testable against real-shape fixtures, same convention as taskStatsLines() itself. */
    static List<AttemptSection> attemptSections(final JsonNode stats) {
        final List<AttemptSection> sections = new ArrayList<>();
        final var attempts = stats.path("attempts");
        if (!attempts.isArray()) return sections;
        for (int i = 0; i < attempts.size(); i++) {
            final var a = attempts.get(i);
            final var header = new StringBuilder("Attempt " + (i + 1));
            if (a.hasNonNull("started_iso")) header.append(" — ").append(a.path("started_iso").asText());
            if (a.hasNonNull("ended_iso")) header.append(" → ").append(a.path("ended_iso").asText());
            final var body = new StringBuilder();
            final var params = a.path("params");
            if (params.isObject() && params.size() > 0) {
                body.append("params (").append(a.path("overridden").asBoolean(false) ? "overridden" : "run default").append("):");
                for (final var line : paramLines(params)) body.append('\n').append(line);
                body.append('\n');
            }
            if (a.hasNonNull("reported_status")) {
                body.append("rc: ").append(a.path("attempt_rc").asText("?")).append("   outcome: ").append(a.path("reported_status").asText()).append('\n');
                final String detail = a.path("reported_detail").asText(null);
                if (detail != null && !detail.isBlank()) {
                    final var lines = detailLines(detail);
                    if (!lines.isEmpty()) {
                        body.append("detail:");
                        for (final var line : lines) body.append('\n').append(line);
                        body.append('\n');
                    }
                }
            }
            body.append("wall time: ").append(a.path("seconds").asText("?")).append("s   model turns: ").append(a.path("turns").asText("0")).append('\n');
            body.append("context compactions: ").append(a.path("compactions").asText("0"))
                    .append("   failed tool calls: ").append(a.path("tool_errors").asText("0"));
            final var tc = a.path("tool_calls");
            if (tc.isObject() && tc.size() > 0) for (final var line : toolCallLines(tc)) body.append('\n').append(line);
            final List<CompactionDistributionRow> distribution = new ArrayList<>();
            for (final var d : a.path("compaction_distribution"))
                distribution.add(new CompactionDistributionRow(d.path("request_number").asInt(),
                        d.hasNonNull("requests_since_previous") ? d.path("requests_since_previous").asInt() : null));
            final List<CompactionReclaimedRow> reclaimed = new ArrayList<>();
            for (final var r : a.path("compaction_reclaimed"))
                reclaimed.add(new CompactionReclaimedRow(r.path("request_number").asInt(),
                        r.hasNonNull("prompt_tokens_before") ? r.path("prompt_tokens_before").asLong() : null,
                        r.hasNonNull("reclaimed_tokens") ? r.path("reclaimed_tokens").asLong() : null));
            sections.add(new AttemptSection(header.toString(), body.toString(), requestSpeedPoints(a.path("request_speeds")),
                    compactionsBeforeRequest(a.path("compactions_before_request")),
                    compactionsBeforeRequest(a.path("judge_on_track_requests")),
                    compactionsBeforeRequest(a.path("judge_off_track_requests")),
                    distribution, reclaimed));
        }
        return sections;
    }

    /** The "Live" tab: a plain-text, operator-facing peek into the task's own coding session -
     *  what it's thinking, saying, and calling tools to do - streamed from
     *  /jobs/{id}/tasks/{taskId}/transcript (TaskTranscript on the server side). Unlike every
     *  other tab in this dialog, its content arrives from a background SSE thread for as long as
     *  the dialog stays open, not from the page's own 2s poll cycle - same push mechanism the
     *  main page's own ensureSse()/JobEventLoop already use (this app runs with @Push enabled),
     *  just scoped to one task's dialog instead of the whole page. Capped client-side too
     *  (CLIENT_CAP_CHARS) - the server's own initial catch-up batch is already tail-capped, but a
     *  dialog left open for hours would otherwise still grow its own buffer unboundedly. */
    private static final int TRANSCRIPT_CLIENT_CAP_CHARS = 40_000;

    private void openTaskTranscriptTab(final Api.PlanTask task, final com.vaadin.flow.component.dialog.Dialog dialog, final Div transcriptBody) {
        final var buffer = new StringBuilder();
        final var stopped = new java.util.concurrent.atomic.AtomicBoolean(false);
        transcriptBody.removeAll();
        transcriptBody.add(Panels.mono("(waiting for the task's own session to start...)"));

        final java.util.function.Consumer<SseEvent> onEvent = event -> {
            if (!"line".equals(event.payloadType())) return;
            final String text = event.data().path("text").asText("");
            if (text.isBlank()) return;
            if (!buffer.isEmpty()) buffer.append("\n\n");
            buffer.append(text);
            if (buffer.length() > TRANSCRIPT_CLIENT_CAP_CHARS) buffer.delete(0, buffer.length() - TRANSCRIPT_CLIENT_CAP_CHARS);
            transcriptBody.removeAll();
            transcriptBody.add(Panels.mono(buffer.toString()));
            transcriptBody.getElement().executeJs("this.scrollTop = this.scrollHeight");
        };
        final Runnable onLost = () -> transcriptBody.add(Panels.error("Live connection lost - reopen this dialog to retry."));

        // #310: captured here, on the UI thread (this method only ever runs on it) - the loop's own
        // callbacks run on its background thread, where a plain getUI() call would read Vaadin's
        // internal attach state without the session lock
        final var ui = getUI().orElse(null);
        if (ui == null) return; // not attached - nothing to stream into
        final var loop = new TaskTranscriptLoop(client, jobId, task.id(), stopped::get,
                event -> ui.access(() -> onEvent.accept(event)),
                () -> ui.access(onLost::run));
        final var thread = new Thread(loop, "job-" + jobId + "-task-" + task.id() + "-transcript");
        thread.setDaemon(true);
        thread.start();
        // #307: stopped on EITHER the dialog's own explicit close OR the whole view detaching
        // (navigation away, session/UI teardown) - the opened-change listener alone missed the
        // second case entirely, since a Dialog's overlay isn't torn down just because the view
        // that opened it navigates away, same reasoning the view's own stopSse() already applies
        // to the page-level SSE loop via addDetachListener. #311: an AtomicBoolean, not a plain
        // boolean[] - this flag is written here on the UI thread and read on the loop's own
        // background thread, with no other synchronization between the two.
        final var detachReg = addDetachListener(e -> stopped.set(true));
        dialog.addOpenedChangeListener(e -> { if (!e.isOpened()) { stopped.set(true); detachReg.remove(); } });
    }

    /** The Live tab's own send box: lets an operator steer the task directly, through the exact
     *  same per-turn mechanism the mid-task Judge already uses (ServiceClient.sendIntervention() -
     *  BenchController.intervene() - RunBench.buildJudgeCheckpoint()'s own mailbox check), so it
     *  reaches the agent at its own next turn boundary - whatever that message was, it's
     *  delivered as a durable, timestamped entry in the task's own session transcript, visible
     *  right here once it lands. A static form, built once - never rebuilt by this dialog's own
     *  poll loop, same reasoning buildRestartTab()'s own javadoc gives for why ITS form is static
     *  (rebuilding would wipe out whatever the operator is mid-typing). No check here that the
     *  task is actually running: a message sent to one that isn't just sits unconsumed until
     *  either it starts or this task's own next attempt clears it - a quiet no-op, not an error. */
    private void buildInterveneRow(final Api.PlanTask task, final Div interveneRow) {
        final var text = new com.vaadin.flow.component.textfield.TextArea();
        text.setPlaceholder("Steer the agent directly - delivered as its next instruction and logged in its own session transcript.");
        text.setMinHeight("70px");
        final var send = new Button("Send", e -> {
            final var value = text.getValue();
            if (value == null || value.isBlank()) return;
            try {
                client.sendIntervention(jobId, task.id(), value);
                text.clear();
                Notification.show("Sent - delivered at the agent's next turn.", 4000, Notification.Position.BOTTOM_END);
            } catch (final Exception ex) {
                Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
            }
        });
        final var row = new HorizontalLayout(text, send);
        row.setWidthFull();
        // flex-grow, not text.setWidthFull(): a plain width:100% on a flex item sets its flex-basis
        // to 100% too, which fights the Send button for the same space and (depending on the
        // layout's own cross-axis sizing) can leave text either too small or wide enough to force
        // the whole row to overflow - flex-grow alone correctly claims exactly "the row's width
        // minus the Send button's own natural width", with no fixed width to fight.
        row.setFlexGrow(1, text);
        row.getStyle().set("margin-top", "8px");
        interveneRow.removeAll();
        interveneRow.add(row);
    }

    /** #263/#321: a static form (built ONCE, never rebuilt by the polling loop above - unlike
     *  Prompt/Changes/History, rebuilding this would wipe out whatever the operator is mid-typing
     *  into the override fields). Always requires the job paused first - either flow writes
     *  jobs.task_overrides, and WorkerService only rebuilds cfg (so only re-reads that column)
     *  when a job is next claimed, so a write made while still running would silently not apply
     *  until a future pause/resume anyway.
     *
     *  Branches on whether the task has run yet: one that has gets the destructive restart flow
     *  (#263 - discards and redoes its own and every later task's work); one that hasn't gets the
     *  non-destructive #321 flow instead - same override fields, nothing discarded, no requeue()
     *  needed since the task was never going to run until the job resumes anyway. */
    private void buildRestartTab(final Api.PlanTask task, final com.vaadin.flow.component.dialog.Dialog dialog, final Div restartBody) {
        final boolean pending = !task.implemented();
        if (currentJob == null || !"paused".equals(currentJob.status())) {
            restartBody.add(Panels.mono(pending
                    ? "Pause the run first - a pending task's settings can only be set while the job is paused."
                    : "Pause the run first - a task can only be restarted while the job is paused."));
            return;
        }
        final var warning = new Span(pending
                ? "Sets " + task.id() + "'s own settings for when it runs - nothing has run yet, so nothing is discarded."
                : "Restarts " + task.id() + " and every task after it in plan order - their "
                + "current work is discarded (a backup git tag is made first in the workspace, but it is not "
                + "exposed here - recoverable only via a plain `git tag -l 'backup/*'` on the host).");
        warning.getStyle()
                .set("font-size", "0.85em").set("display", "block").set("margin-bottom", "8px");

        final var taskWall = new com.vaadin.flow.component.textfield.IntegerField("Task wall (sec)");
        final var taskTokens = new com.vaadin.flow.component.textfield.IntegerField("Task tokens");
        final var maxTurns = new com.vaadin.flow.component.textfield.IntegerField("Max turns");
        final var wrapupWall = new com.vaadin.flow.component.textfield.IntegerField("Wrap-up wall (sec)");
        final var wrapupTokens = new com.vaadin.flow.component.textfield.IntegerField("Wrap-up tokens");
        for (final var field : List.of(taskWall, taskTokens, maxTurns, wrapupWall, wrapupTokens)) {
            field.setPlaceholder("run default");
            field.setWidth("140px");
        }
        final var form = new HorizontalLayout(taskWall, taskTokens, maxTurns, wrapupWall, wrapupTokens);
        form.getStyle().set("flex-wrap", "wrap");

        // #417: compaction_trigger has been a legal restart-override key since #345 but never had
        // an editable field in this form at all - a pre-existing gap, cheap to close while already
        // touching this form. Grouped with compactionStrategies below rather than in the generic
        // `form` row above, since both configure the same "Context compaction" mechanism.
        final var compactionTrigger = new com.vaadin.flow.component.textfield.IntegerField("Compaction trigger");
        compactionTrigger.setMin(0);
        compactionTrigger.setPlaceholder("run default");
        compactionTrigger.setWidth("140px");

        // #417: which compaction strategy/strategies this task uses, and in what order - overrides
        // the run-level default (CompactionStrategyList's own getValue() is blank = "unset", which
        // setPendingTaskOverride's caller below already treats as "don't include this key").
        final var compactionStrategies = new CompactionStrategyList();
        final var compactionForm = new VerticalLayout(new Span("Context compaction"), compactionTrigger, compactionStrategies);
        compactionForm.setPadding(false);
        compactionForm.setSpacing(false);
        compactionForm.getStyle().set("margin-top", "4px");

        // #265: the mid-task judge - every judgeEveryNTurns turns, a separate LLM checks whether
        // this task is still on the most efficient path to its goal and will meet its deadline.
        // Restart-tunable so a task already stuck/drifting (the whole reason this exists) can be
        // caught on its very next attempt without waiting for a fresh run.
        final var judgeEnabled = new com.vaadin.flow.component.checkbox.Checkbox("Mid-task judge");
        final var judgeEveryNTurns = new com.vaadin.flow.component.textfield.IntegerField("Every N turns");
        final var judgeModel = new ProviderModelPicker(client, "Judge model");
        judgeEveryNTurns.setPlaceholder("run default (10)");
        judgeEveryNTurns.setWidth("140px");
        final var judgeForm = new HorizontalLayout(judgeEnabled, judgeEveryNTurns, judgeModel);
        judgeForm.getStyle().set("flex-wrap", "wrap").set("align-items", "center").set("margin-top", "4px");

        // #307: the budget-exhaustion rescue step - one extra dedicated session, with the harness's
        // own fresh verification output injected, before accepting a budget-exhausted (rc=3) task as
        // final. Only the enabled toggle is restart-overridable (same reasoning as the judge's own
        // wall/token fields staying run-level-only, see effectiveRescueCfg()'s own javadoc) - the
        // rescue session's budget/compaction knobs are an operator choice, not exposed here.
        final var rescueEnabled = new com.vaadin.flow.component.checkbox.Checkbox("Budget-exhaustion rescue");
        final var rescueForm = new HorizontalLayout(rescueEnabled);
        rescueForm.getStyle().set("flex-wrap", "wrap").set("align-items", "center").set("margin-top", "4px");

        // #332: a dedicated toggle, not a CompactionStrategies member - see Tooltips.LESSON_RECORDING_ENABLED
        final var lessonRecordingEnabled = new com.vaadin.flow.component.checkbox.Checkbox("Cross-task lesson recording");
        final var lessonRecordingForm = new HorizontalLayout(lessonRecordingEnabled);
        lessonRecordingForm.getStyle().set("flex-wrap", "wrap").set("align-items", "center").set("margin-top", "4px");

        // #266: every field above used to always render with just a "run default" placeholder -
        // no way anywhere in the UI to see what override values (if any) are already in effect for
        // this task from a PREVIOUS restart (only a direct look at jobs.task_overrides in the raw
        // DB could tell an operator that). Pre-fill from the job's own currently-active override.
        final java.util.Map<String, Object> currentOverride = currentJob.task_overrides() != null
                && currentJob.task_overrides().get(task.id()) instanceof java.util.Map<?, ?> m ? (java.util.Map<String, Object>) m : java.util.Map.of();
        if (currentOverride.get("task_wall_sec") instanceof Number n) taskWall.setValue(n.intValue());
        if (currentOverride.get("task_tokens") instanceof Number n) taskTokens.setValue(n.intValue());
        if (currentOverride.get("max_turns") instanceof Number n) maxTurns.setValue(n.intValue());
        if (currentOverride.get("wrapup_wall_sec") instanceof Number n) wrapupWall.setValue(n.intValue());
        if (currentOverride.get("wrapup_tokens") instanceof Number n) wrapupTokens.setValue(n.intValue());
        if (currentOverride.get("compaction_trigger") instanceof Number n) compactionTrigger.setValue(n.intValue());
        if (currentOverride.get("compaction_strategies") instanceof String s && !s.isBlank())
            compactionStrategies.setValue(List.of(s.split(",")));
        if (Boolean.TRUE.equals(currentOverride.get("judge_enabled"))) judgeEnabled.setValue(true);
        if (currentOverride.get("judge_every_n_turns") instanceof Number n) judgeEveryNTurns.setValue(n.intValue());
        if (currentOverride.get("judge_model") instanceof String s && !s.isBlank()) judgeModel.setValue(s);
        if (Boolean.TRUE.equals(currentOverride.get("budget_rescue_enabled"))) rescueEnabled.setValue(true);
        if (Boolean.TRUE.equals(currentOverride.get("lesson_recording_enabled"))) lessonRecordingEnabled.setValue(true);

        final var restartButton = new Button(pending ? "Save settings for " + task.id() : "Restart from " + task.id(), e -> {
            final java.util.Map<String, Object> overrides = new java.util.LinkedHashMap<>();
            if (taskWall.getValue() != null) overrides.put("task_wall_sec", taskWall.getValue());
            if (taskTokens.getValue() != null) overrides.put("task_tokens", taskTokens.getValue());
            if (maxTurns.getValue() != null) overrides.put("max_turns", maxTurns.getValue());
            if (wrapupWall.getValue() != null) overrides.put("wrapup_wall_sec", wrapupWall.getValue());
            if (wrapupTokens.getValue() != null) overrides.put("wrapup_tokens", wrapupTokens.getValue());
            if (compactionTrigger.getValue() != null) overrides.put("compaction_trigger", compactionTrigger.getValue());
            if (!compactionStrategies.getValue().isEmpty()) overrides.put("compaction_strategies", String.join(",", compactionStrategies.getValue()));
            if (judgeEnabled.getValue()) overrides.put("judge_enabled", true);
            if (judgeEveryNTurns.getValue() != null) overrides.put("judge_every_n_turns", judgeEveryNTurns.getValue());
            if (judgeModel.getValue() != null && !judgeModel.getValue().isBlank()) overrides.put("judge_model", judgeModel.getValue());
            if (rescueEnabled.getValue()) overrides.put("budget_rescue_enabled", true);
            if (lessonRecordingEnabled.getValue()) overrides.put("lesson_recording_enabled", true);
            try {
                if (pending) {
                    client.setPendingTaskOverride(jobId, task.id(), overrides);
                } else {
                    client.restartTask(jobId, task.id(), overrides);
                    client.requeue(jobId);
                }
                refresh();
                dialog.close();
            } catch (final Exception ex) {
                log.warn("could not {} task {} on job {}: {}", pending ? "set pending override for" : "restart", task.id(), jobId, ex.toString());
                Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
            }
        });
        restartButton.getStyle().set("margin-top", "8px");
        restartBody.add(warning, form, compactionForm, judgeForm, rescueForm, lessonRecordingForm, restartButton);
    }

    /** #264: the sessionsGrid counterpart of openTaskPromptDialog() - scoped to the three pre-task
     *  phases with no other way to be inspected (Definition/Plan/Parallel plan); any other row
     *  (a task id, INTEGRATION, a continuation - already covered by planGrid, or simply not
     *  restartable) still opens for Prompt/Changes, just with the backend's own graceful "nothing
     *  to show" response and no Restart tab. Deliberately simpler than the task dialog: no History
     *  (these phases are each one-shot per run - no multi-attempt comparison need). */
    private void openSessionDialog(final JobLiveState.SessionRow row) {
        final String name = row.name();
        final var restartablePhases = java.util.Set.of("p0_definition", "p1_plan", "PARALLEL_PLAN");
        final var dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(row.description() == null || row.description().isBlank() ? "Session" : row.description());
        dialog.setWidth("min(900px, 90vw)");
        dialog.setHeight("min(650px, 85vh)");

        // same explicit width fix openTaskPromptDialog() needs - see its own comment for why
        final var promptBody = new Div();
        promptBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        final var diffBody = new Div();
        diffBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        diffBody.setVisible(false);
        final var restartBody = new Div();
        restartBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        restartBody.setVisible(false);

        final var promptTab = new com.vaadin.flow.component.tabs.Tab("Prompt");
        final var changesTab = new com.vaadin.flow.component.tabs.Tab("Changes");
        final boolean showRestart = name != null && restartablePhases.contains(name);
        final var restartTab = showRestart ? new com.vaadin.flow.component.tabs.Tab("Restart") : null;
        final com.vaadin.flow.component.tabs.Tabs tabs = showRestart
                ? new com.vaadin.flow.component.tabs.Tabs(promptTab, changesTab, restartTab)
                : new com.vaadin.flow.component.tabs.Tabs(promptTab, changesTab);
        tabs.getStyle().set("width", "100%").set("box-sizing", "border-box");
        tabs.addSelectedChangeListener(e -> {
            promptBody.setVisible(tabs.getSelectedTab() == promptTab);
            diffBody.setVisible(tabs.getSelectedTab() == changesTab);
            restartBody.setVisible(restartTab != null && tabs.getSelectedTab() == restartTab);
        });

        final var content = new VerticalLayout(tabs, promptBody, diffBody, restartBody);
        content.setPadding(false);
        content.setSpacing(false);
        content.setSizeFull();
        dialog.add(content);
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));

        final Registration[] pollReg = new Registration[1];
        final boolean[] promptSettled = {false};
        final boolean[] diffSettled = {false};

        final Runnable loadPrompt = () -> {
            try {
                final var prompt = name == null ? new Api.TaskPrompt(false, "") : client.sessionPrompt(jobId, name);
                promptBody.removeAll();
                if (!prompt.started()) {
                    final var note = new Span(name == null || !restartablePhases.contains(name)
                            ? "There is no detail view for this session - only Definition, Plan, and Parallel "
                              + "plan are inspectable here (a task's own row has the same information via the "
                              + "plan table above instead)."
                            : "This phase hasn't started yet - the fixed instruction below is all there is "
                              + "until it does. This updates automatically once it does.");
                    note.getStyle().set("font-size", "0.85em")
                            .set("display", "block").set("margin-bottom", "8px");
                    promptBody.add(note);
                    promptSettled[0] = name == null || !restartablePhases.contains(name);
                } else {
                    promptSettled[0] = true;
                }
                if (!prompt.text().isBlank())
                    promptBody.add(new com.vaadin.flow.component.Html("<div class=\"md-body\">" + Markdown.toHtml(prompt.text()) + "</div>"));
            } catch (final Exception ex) {
                promptBody.removeAll();
                promptBody.add(Panels.error(client.errorText(ex)));
                promptSettled[0] = true;
            }
        };
        final Runnable loadDiff = () -> {
            try {
                final var diff = name == null ? new Api.TaskDiff(false, false, "", "", "", false, List.of(), false, null)
                        : client.sessionDiff(jobId, name);
                diffBody.removeAll();
                diffBody.add(DiffView.render(diff));
                diffSettled[0] = diff.finished();
            } catch (final Exception ex) {
                diffBody.removeAll();
                diffBody.add(Panels.error(client.errorText(ex)));
                diffSettled[0] = true;
            }
        };
        final Runnable loadAll = () -> {
            loadPrompt.run();
            loadDiff.run();
            if (promptSettled[0] && diffSettled[0] && pollReg[0] != null) {
                pollReg[0].remove();
                pollReg[0] = null;
            }
        };
        dialog.addOpenedChangeListener(e -> {
            if (!e.isOpened() && pollReg[0] != null) {
                pollReg[0].remove();
                pollReg[0] = null;
            }
        });
        if (showRestart) buildSessionRestartTab(name, dialog, restartBody);
        dialog.open();
        loadAll.run();
        getUI().ifPresent(ui -> pollReg[0] = ui.addPollListener(e -> loadAll.run()));
    }

    /** #264: the phase-session counterpart of buildRestartTab() - same static-form-built-once
     *  rule (never rebuilt by the polling loop, so mid-typed override values survive), same
     *  paused gate. No wrap-up fields: p0/p1/PARALLEL_PLAN have no wrap-up concept at all. */
    private void buildSessionRestartTab(final String name, final com.vaadin.flow.component.dialog.Dialog dialog, final Div restartBody) {
        if (currentJob == null || !"paused".equals(currentJob.status())) {
            restartBody.add(Panels.mono("Pause the run first - a session can only be restarted while the job is paused."));
            return;
        }
        final var warning = new Span("Restarts " + name + " and every later phase/task - their current work is "
                + "discarded (a backup git tag is made first in the workspace, but it is not exposed here - "
                + "recoverable only via a plain `git tag -l 'backup/*'` on the host).");
        warning.getStyle().set("font-size", "0.85em").set("display", "block").set("margin-bottom", "8px");

        final var taskWall = new com.vaadin.flow.component.textfield.IntegerField("Task wall (sec)");
        final var taskTokens = new com.vaadin.flow.component.textfield.IntegerField("Task tokens");
        final var maxTurns = new com.vaadin.flow.component.textfield.IntegerField("Max turns");
        final var firstTokenTimeout = new com.vaadin.flow.component.textfield.IntegerField("First-token timeout (sec)");
        for (final var field : List.of(taskWall, taskTokens, maxTurns, firstTokenTimeout)) {
            field.setPlaceholder("run default");
            field.setWidth("160px");
        }
        final var form = new HorizontalLayout(taskWall, taskTokens, maxTurns, firstTokenTimeout);
        form.getStyle().set("flex-wrap", "wrap");

        // #398: task granularity only has meaning for "p1_plan" - restarting p0_definition/
        // PARALLEL_PLAN with it set would be inert (neither phase reads it), so the field is only
        // shown for a p1_plan restart, same "don't offer a control that silently does nothing"
        // reasoning as the compaction fields' own task-vs-phase scoping.
        final var taskGranularity = new com.vaadin.flow.component.select.Select<String>();
        taskGranularity.setLabel("Task granularity");
        taskGranularity.setItems("", "fine", "medium", "coarse");
        taskGranularity.setItemLabelGenerator(v -> v.isEmpty() ? "—" : v);
        taskGranularity.setWidth("160px");
        taskGranularity.setTooltipText(Tooltips.TASK_GRANULARITY);

        // #266: same pre-fill as buildRestartTab() above - restartSession() stores this phase's
        // own override in the SAME jobs.task_overrides map, just keyed by phase name instead of
        // task id (see BenchController.restartSession()/doRestart()).
        final java.util.Map<String, Object> currentOverride = currentJob.task_overrides() != null
                && currentJob.task_overrides().get(name) instanceof java.util.Map<?, ?> m ? (java.util.Map<String, Object>) m : java.util.Map.of();
        if (currentOverride.get("task_wall_sec") instanceof Number n) taskWall.setValue(n.intValue());
        if (currentOverride.get("task_tokens") instanceof Number n) taskTokens.setValue(n.intValue());
        if (currentOverride.get("max_turns") instanceof Number n) maxTurns.setValue(n.intValue());
        if (currentOverride.get("first_token_timeout_sec") instanceof Number n) firstTokenTimeout.setValue(n.intValue());
        if (currentOverride.get("task_granularity") instanceof String s && !s.isBlank()) taskGranularity.setValue(s);

        final var restartButton = new Button("Restart from " + name, e -> {
            final java.util.Map<String, Object> overrides = new java.util.LinkedHashMap<>();
            if (taskWall.getValue() != null) overrides.put("task_wall_sec", taskWall.getValue());
            if (taskTokens.getValue() != null) overrides.put("task_tokens", taskTokens.getValue());
            if (maxTurns.getValue() != null) overrides.put("max_turns", maxTurns.getValue());
            if (firstTokenTimeout.getValue() != null) overrides.put("first_token_timeout_sec", firstTokenTimeout.getValue());
            if ("p1_plan".equals(name) && taskGranularity.getValue() != null && !taskGranularity.getValue().isBlank())
                overrides.put("task_granularity", taskGranularity.getValue());
            try {
                client.restartSession(jobId, name, overrides);
                client.requeue(jobId);
                refresh();
                dialog.close();
            } catch (final Exception ex) {
                log.warn("could not restart session {} on job {}: {}", name, jobId, ex.toString());
                Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
            }
        });
        restartButton.getStyle().set("margin-top", "8px");
        restartBody.add(warning, form);
        if ("p1_plan".equals(name)) restartBody.add(taskGranularity);
        restartBody.add(restartButton);
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
            case "paused" ->
                    "this job is paused — press Resume to pick up right where it left off; its "
                    + "results appear once it finishes with a score";
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

    /** "T1 → T2, T4 → T3": a wave with more than one task ran one at a time under this job's own
     *  argv (parallel=1 unless --parallel/--parallel-auto was passed), never concurrently unless the
     *  job's argv actually enabled that - shown so a later, numerically-lower task starting right
     *  after an earlier, numerically-higher one (e.g. T3 after T4, both only needing T1/T2 done) reads
     *  as the declared plan, not the runner going backwards. */
    static String plannedOrderText(final List<List<String>> waves) {
        final List<String> waveStrs = new ArrayList<>();
        for (final var wave : waves) waveStrs.add(String.join(", ", wave));
        return String.join(" → ", waveStrs);
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
