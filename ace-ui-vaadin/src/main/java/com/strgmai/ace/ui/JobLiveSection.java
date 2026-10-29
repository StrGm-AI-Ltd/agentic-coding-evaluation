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
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import tools.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.List;

/** Extracted from JobDetailView (Phase 1 of closing the live-monitoring gap on the new main Job
 *  detail page, JobDetailPrototypeView - see the plan this followed for the full rationale): the
 *  SSE-connected Sessions table, Recent-requests table + latency chart, log tail, and the current-
 *  step/active-time/tasks-done/turns/tool-errors stat tiles, as a standalone, reusable Component
 *  rather than a slice of JobDetailView's own instance state. Owns its own poll-independent SSE
 *  connection (ensureSse()/stopSse()) and manages its own attach/detach lifecycle the normal Vaadin
 *  way (stopSse() on detach), so any view can just construct one, add() it, and call update() every
 *  time it has a fresh Api.Job to report.
 *
 *  <p>update()'s three inputs (job/planTasks/currentTaskStats) are cached as fields, not just used
 *  transiently, because every SSE event (not only every poll tick) needs to re-render the exact same
 *  fields this class owns - renderLive() is the shared body both update() and the SSE event callback
 *  call, always reading the LAST values update() cached rather than requiring a fresh job/planTasks/
 *  currentTaskStats on every SSE-driven redraw (events can arrive seconds apart from the owning
 *  view's own 2s poll cycle). */
public final class JobLiveSection extends VerticalLayout {

    private final ServiceClient client;
    private final String jobId;
    private final Runnable onChanged;
    private final Runnable onLiveChanged;
    private final boolean alwaysShowLiveData;
    private final JobLiveState live = new JobLiveState();

    private transient Thread sseThread;
    private volatile boolean sseStopped;

    private Api.Job currentJob;
    private List<Api.PlanTask> planTasks = List.of();
    private JsonNode currentTaskStats;

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

    private final Grid<JobLiveState.RequestRow> requestsGrid = buildRequestsGrid();
    private final Grid<JobLiveState.SessionRow> sessionsGrid = buildSessionsGrid();
    // #476 PR 3/3: a job-level (not per-task) live chart, built once and updated in place via
    // ApexCharts' own updateSeries()/setXaxis() - see updateRequestLatencyChart().
    private final ApexCharts requestLatencyChart = buildRequestLatencyChart();

    // #476 PR 3/3: wraps the current-step/active-time stat tiles so both hide together on a
    // terminal job, instead of each tile's own inner Span hiding and leaving an empty tile shell.
    private final HorizontalLayout liveStatsRow = new HorizontalLayout();
    private final Span plannedOrderLine = new Span();
    private final Span stepLine = new Span();
    private final Span activeTimeLine = new Span();
    private final Span tasksDoneLine = new Span();
    private final Span taskTurnsLine = new Span();
    private final Span toolErrorsLine = new Span();
    private final Span requestsLine = new Span();
    private final Div logTail = new Div();
    private final Span terminalNote = new Span("This job has finished; nothing more to stream.");
    private final Span lostNotice = new Span("live connection lost — status still updates via polling");

    /** planCard: the owning view's own Plan grid, already wrapped in its card chrome - accepted
     *  (rather than built here) because the grid itself, and its live-status column, stay owned by
     *  the view (it already has everything a Plan grid needs: client/jobId/currentJob). Slotted in
     *  at the exact position JobDetailView's own buildChrome() used to place it, between
     *  plannedOrderLine and the live stat tiles, so splitting it out of this class changes nothing
     *  about the page's own visual order.
     *
     *  <p>onLiveChanged: run at the end of every renderLive() - on every poll-triggered update() AND
     *  every SSE event, not just poll ticks. This matters most for a TERMINAL job: polling is off
     *  for one (JobStatuses.isTerminal() → pollInterval(-1)), so the one-shot SSE replay that
     *  populates live.sessions() for an already-finished job is the ONLY thing that ever changes
     *  after the initial render - the owning view's own Plan grid (whose status/time/generated/
     *  prefilled columns read live.sessions() lazily, at render time) needs telling to re-render
     *  as those replayed events land, the same way the original updateLive() always called
     *  updatePlanGrid() as its own first line, on every single live update it ever ran. */
    public JobLiveSection(final ServiceClient client, final String jobId, final Runnable onChanged,
            final Runnable onLiveChanged, final com.vaadin.flow.component.Component planCard) {
        this(client, jobId, onChanged, onLiveChanged, planCard, false);
    }

    /** alwaysShowLiveData: when true, a TERMINAL job's Sessions/Recent-requests/log-tail still get
     *  populated from the replayed JobLiveState instead of the early-return below that otherwise
     *  blanks them for one - JobDetailPrototypeView's own choice (a finished job's session history
     *  is still worth showing there), kept opt-in rather than the new default so JobDetailView's own
     *  existing behavior (hide them once a job is done - nothing left to watch live) is unchanged. */
    public JobLiveSection(final ServiceClient client, final String jobId, final Runnable onChanged,
            final Runnable onLiveChanged, final com.vaadin.flow.component.Component planCard,
            final boolean alwaysShowLiveData) {
        this.client = client;
        this.jobId = jobId;
        this.onChanged = onChanged;
        this.onLiveChanged = onLiveChanged;
        this.alwaysShowLiveData = alwaysShowLiveData;
        setPadding(false);
        setSpacing(false);
        getStyle().set("margin-top", "16px");

        liveStatsRow.setPadding(false);
        liveStatsRow.setSpacing(true);
        liveStatsRow.getStyle().set("flex-wrap", "wrap").set("margin", "4px 0");
        liveStatsRow.add(Panels.statTile("Current step", stepLine), Panels.statTile("Active time", activeTimeLine),
                Panels.statTile("Tasks done", tasksDoneLine), Panels.statTile("Current task turns", taskTurnsLine),
                Panels.statTile("Tool errors", toolErrorsLine));

        add(plannedOrderLine, planCard, liveStatsRow, sessionsGrid, requestsLine, lostNotice, terminalNote,
                Panels.card("Recent requests", requestLatencyChart, requestsGrid), logTail);
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

        // #499: connect even for an already-finished job, not just a still-running one - this is
        // the ONLY path that ever populates live.sessions() (JobDetailView.taskStats() reads it,
        // never a completed job's own real wall-time/token figures from anywhere else), and
        // BenchController.events() already replays a finished job's full history in one shot
        // before self-terminating the stream, so this costs exactly one round trip, not an
        // open-ended connection. Unconditional on terminal/non-terminal (unlike update()'s own
        // ensureSse/stopSse branch) specifically because a terminal job never polls again
        // (JobDetailView sets pollInterval(-1) for one) - this attach listener is the only thing
        // that re-establishes the connection on a reattach for one.
        addAttachListener(e -> getUI().ifPresent(this::ensureSse));
        addDetachListener(e -> stopSse());
    }

    /** The accumulated live state - read-only from the caller's side (JobDetailView's own Plan grid
     *  reads sessions()/currentStep() from this to stay in sync with the same SSE stream, without
     *  needing a second connection of its own). */
    public JobLiveState live() {
        return live;
    }

    /** Individual-component accessors, for an owning view that wants its own visual presentation of
     *  these pieces instead of the pre-assembled layout the constructor builds (JobDetailPrototypeView's
     *  own case - see its own javadoc). Each returned Component is still a child of this VerticalLayout
     *  until the caller add()s it elsewhere, which Vaadin reparents automatically; this class keeps
     *  updating it in place either way, since renderLive() only ever touches these fields directly, never
     *  their current parent. A caller that reparents every one of these should also set this component's
     *  own setVisible(false) - it still needs to stay add()ed SOMEWHERE for its attach/detach listener to
     *  drive the SSE connection, but has nothing left worth showing once its content moves out. */
    public Grid<JobLiveState.SessionRow> sessionsGrid() {
        return sessionsGrid;
    }

    public Grid<JobLiveState.RequestRow> requestsGrid() {
        return requestsGrid;
    }

    public ApexCharts requestLatencyChart() {
        return requestLatencyChart;
    }

    public Div logTail() {
        return logTail;
    }

    public Span requestsLine() {
        return requestsLine;
    }

    /** Called once per poll tick by the owning view with its own freshly-fetched job/planTasks/
     *  currentTaskStats - mirrors JobDetailView's own update()+updateLive() split before this class
     *  existed, just parameter-driven instead of reading instance fields implicitly. */
    public void update(final Api.Job job, final List<Api.PlanTask> planTasks, final JsonNode currentTaskStats) {
        this.currentJob = job;
        this.planTasks = planTasks;
        this.currentTaskStats = currentTaskStats;
        final var terminal = JobStatuses.isTerminal(job.status());
        getUI().ifPresent(ui -> {
            if (terminal) {
                stopSse();
            } else {
                ensureSse(ui); // m5: restart a dead loop (transient failures) instead of staying dark
            }
        });
        renderLive();
    }

    private void renderLive() {
        // first line, unconditionally (before the terminal branch's own early return below) -
        // see the constructor's own javadoc for onLiveChanged for why this must run on every
        // single live update, not just the ones that reach the end of this method.
        onLiveChanged.run();
        final var terminal = currentJob != null && JobStatuses.isTerminal(currentJob.status());
        final var hideForTerminal = terminal && !alwaysShowLiveData;
        lostNotice.setVisible(sseThread != null && !sseThread.isAlive()
                && !terminal && !sseStopped);
        terminalNote.setVisible(hideForTerminal);
        plannedOrderLine.setVisible(!terminal && !live.executionOrder().isEmpty());
        liveStatsRow.setVisible(!terminal);
        sessionsGrid.setVisible(!hideForTerminal);
        requestsLine.setVisible(!hideForTerminal);

        if (hideForTerminal) {
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
            plannedOrderLine.setText("planned order: " + JobDetailView.plannedOrderText(live.executionOrder()));
        }
        // #476 PR 3/3: no "current step: "/"active time: " prefix - each now lives inside a
        // Panels.statTile() whose own label already says that; the "excludes paused/stopped
        // periods" caveat moved to activeTimeLine's tooltip (set once, in the constructor).
        stepLine.setText(live.currentStep() == null ? "–" : live.currentStep());
        activeTimeLine.setText(Fmt.daysHoursMinutes(live.totalActiveSeconds()));
        tasksDoneLine.setText(JobDetailView.tasksDoneText(planTasks));
        taskTurnsLine.setText(JobDetailView.taskTurnsText(currentTaskStats));
        toolErrorsLine.setText(JobDetailView.toolErrorsText(currentTaskStats));
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
        // found live: a terminal job's one-shot replay (alwaysShowLiveData=true) delivers its
        // entire request history as a burst of individual events within a second or two, each one
        // calling renderLive() again - unlike a genuinely live job, where real request timing
        // naturally paces these calls seconds or minutes apart. The ApexCharts Flow wrapper's own
        // client-side updateSeries() can't keep up with that burst rate (TypeError thrown
        // repeatedly in the browser console) - skipped here for that one case; the grid/text above
        // (what alwaysShowLiveData was actually asked to restore) have no such issue.
        final var chartable = !terminal || !alwaysShowLiveData;
        requestLatencyChart.setVisible(chartable && !recent.isEmpty());
        if (chartable) {
            updateRequestLatencyChart(recent);
        }

        final var tail = live.logTail() != null ? live.logTail()
                : (currentJob != null ? currentJob.result_line() : null);
        logTail.setVisible(tail != null && !tail.isBlank());
        logTail.setText(tail == null ? "" : tail);
    }

    /** The SSE loop: events mutate the live state and re-render under the session lock. */
    private void ensureSse(final UI ui) {
        if (sseThread != null && sseThread.isAlive()) {
            return;
        }
        sseStopped = false;
        final var loop = new JobEventLoop(client, jobId, () -> sseStopped,
                event -> ui.access(() -> {   // M1: apply + render share the session lock
                    live.apply(event);
                    renderLive();
                }),
                () -> ui.access(this::renderLive)); // give-up → show the lost notice
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
        // planGrid's own row click already opens the task dialog - this grid previously had no
        // click handling at all, so "Definition"/"Plan"/"Parallel plan" (no other way to inspect
        // them) just did nothing when clicked.
        sessions.addItemClickListener(e -> JobSessionDialog.open(client, jobId, () -> currentJob, e.getItem(), onChanged));
        sessions.getStyle().set("cursor", "pointer");
        return sessions;
    }
}
