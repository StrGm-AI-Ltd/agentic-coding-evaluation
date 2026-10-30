package com.strgmai.ace.ui;

import com.github.appreciated.apexcharts.ApexCharts;
import com.github.appreciated.apexcharts.ApexChartsBuilder;
import com.github.appreciated.apexcharts.config.builder.ChartBuilder;
import com.github.appreciated.apexcharts.config.chart.builder.AnimationsBuilder;
import com.github.appreciated.apexcharts.config.builder.SeriesBuilder;
import com.github.appreciated.apexcharts.config.builder.ThemeBuilder;
import com.github.appreciated.apexcharts.config.builder.XAxisBuilder;
import com.github.appreciated.apexcharts.config.builder.YAxisBuilder;
import com.github.appreciated.apexcharts.config.chart.Type;
import com.github.appreciated.apexcharts.config.theme.Mode;
import com.github.appreciated.apexcharts.config.yaxis.builder.TitleBuilder;
import com.github.appreciated.apexcharts.config.XAxis;
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

import java.math.BigDecimal;
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
    // #588: requestLatencyChart itself is now REASSIGNABLE - updateRequestLatencyChart() swaps in
    // a brand-new ApexCharts instance (never calling the old one's own updateSeries() again) once
    // a redraw would otherwise hand it a large jump in point count, the trigger #588 found for a
    // permanent client-side TypeError. requestLatencyChartHolder is the STABLE component actually
    // added to this class's own layout (and the one JobDetailPrototypeView's own liveTile() call
    // reparents elsewhere) - callers never touch requestLatencyChart directly, so a mid-life swap
    // is invisible to everything outside updateRequestLatencyChart() itself.
    private ApexCharts requestLatencyChart = buildRequestLatencyChart();
    private final Div requestLatencyChartHolder = new Div(requestLatencyChart);
    // the point count requestLatencyChart's own series held as of the last successful redraw - 0
    // until the first one. Compared against recent.size() on the NEXT call to decide incremental
    // update vs. full rebuild - see shouldRebuildChart()'s own javadoc for why.
    private int lastChartedPointCount;

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
                Panels.card("Recent requests", requestLatencyChartHolder, requestsGrid), logTail);
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

    /** #588: returns the STABLE holder, not the chart itself - updateRequestLatencyChart() may
     *  swap the real ApexCharts instance living inside it at any time (see its own javadoc), so a
     *  caller that reparented the raw chart component (what this method returned before #588)
     *  would freeze on whichever instance happened to exist at the moment it called this, never
     *  seeing a later swap. Reparenting the holder instead - same Vaadin add()-moves-it semantics
     *  the class javadoc above already documents for sessionsGrid()/requestsGrid() - means a swap
     *  stays invisible to every caller. Renamed from requestLatencyChart() (its pre-#588 name) so
     *  the method name itself no longer promises "the chart" when it's really "the chart's home". */
    public Div requestLatencyChartHolder() {
        return requestLatencyChartHolder;
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
        renderLive(true);
    }

    /** redrawChart: true only from update()'s own poll-driven call - see the chart-redraw call
     *  site below for why. */
    private void renderLive(final boolean redrawChart) {
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
        // #541-followup: a NON-terminal job (a genuinely running one, or - found live - a PAUSED
        // one, which JobStatuses deliberately never counts as terminal: see its own class javadoc)
        // still opens a real SSE connection whose initial replay can carry dozens or HUNDREDS of
        // already-finished requests (found live: 295 on one real paused job), delivered back to
        // back with no real-inference-time gap between them - the same burst-rate problem the
        // comment above already describes for the terminal+alwaysShowLiveData case, just not
        // covered by that guard since chartable is unconditionally true here.
        //
        // A plain time-based throttle was tried first and rejected: even reduced to a handful of
        // calls, ApexCharts' own client-side updateSeries() doesn't appear to recover once a SINGLE
        // call races and throws - the chart stays permanently blank for the rest of the page's
        // life, unlike the Sessions/Requests GRIDS (plain Vaadin components, unaffected), which
        // recover instantly once their own next setItems() call lands. No fixed delay could be
        // trusted to guarantee zero races against an unknown, potentially-overloaded browser main
        // thread during a burst this large.
        //
        // redrawChart (only true from update()'s own poll-driven call, never from the raw SSE
        // event callback below) is the actual fix instead: completely decouples "how many live
        // events arrived" from "how often the chart redraws" - the chart only ever sees ONE fresh
        // snapshot per full poll tick (~2s, server-timer-driven, independent of SSE event volume),
        // never a rapid-fire sequence of back-to-back calls no matter how large the backlog. recent/
        // live themselves are still updated on every single event above, so no DATA is ever stale -
        // only the chart's own canvas redraw is deferred to the next poll tick.
        if (chartable && redrawChart) {
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
                // redrawChart=false: see renderLive()'s own comment at its chart-redraw call site -
                // the chart only ever redraws from update()'s own poll-driven call, never here
                event -> ui.access(() -> {   // M1: apply + render share the session lock
                    live.apply(event);
                    renderLive(false);
                }),
                () -> ui.access(() -> renderLive(false))); // give-up → show the lost notice
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
     *  chart" ambiguity the per-task dialog charts would have at this, whole-job scope. Built once
     *  with EMPTY data - the real series is handed to it (or a later rebuilt instance - see #588
     *  below) by updateRequestLatencyChart(); see that method for how it's kept current.
     *
     *  <p>Found live, verifying this class's own poll+SSE path against a genuinely running job for
     *  the first time (a gap flagged after Phase 2): animations disabled - with them on, two real
     *  "request" events landing closer together than the chart's own transition duration (the
     *  common case for a fast local model making several quick successive calls) threw a client-
     *  side TypeError inside ApexCharts' own animated-update code (a Promise wrapping an internal
     *  series-diff array, per the browser stack trace: ApexChartsWrapper.updateData -> an internal
     *  Promise/.map() chain reading an index off an array that the SECOND, overlapping updateSeries()
     *  call left in an inconsistent state). Pre-existing - unchanged from the original JobDetailView,
     *  reachable on the classic page too - just never caught before since nothing had tested this
     *  class's own live path against a job fast enough to trigger back-to-back calls this close
     *  together. No loss of real functionality: the chart still reflects every request, just via
     *  instant redraws instead of an animated transition between them. */
    private static ApexCharts buildRequestLatencyChart() {
        return requestLatencyChartWithData(new String[0], new Double[0]);
    }

    /** found live on job cd4c11a8 (308 requests): the x-axis categories are plain sequential
     *  request indices ("1".."308"), and with no tick limit ApexCharts renders every single one as
     *  its own label - illegible past a couple dozen requests. Capping to a fixed number of evenly
     *  spaced ticks (ApexCharts picks which categories to actually label) keeps it readable
     *  regardless of how large recent grows; both buildXaxis() call sites below share it so the
     *  rebuild and incremental update paths stay visually identical. */
    private static final BigDecimal MAX_X_AXIS_TICKS = BigDecimal.TEN;

    private static XAxis buildXaxis(final String[] categories) {
        return XAxisBuilder.get().withCategories(categories).withTickAmount(MAX_X_AXIS_TICKS).build();
    }

    /** #588: the SAME builder buildRequestLatencyChart() always used, but with the real series
     *  baked in from construction - the chart this produces has never had updateSeries() called
     *  on it at all, so there is nothing for ApexCharts' own client-side diff code to race against
     *  on its very first real paint. Used both for the initial (empty) chart and for every REBUILT
     *  one updateRequestLatencyChart() swaps in - see that method's own javadoc. */
    private static ApexCharts requestLatencyChartWithData(final String[] categories, final Double[] data) {
        final var chart = ApexChartsBuilder.get()
                .withChart(ChartBuilder.get().withType(Type.LINE).withBackground("transparent")
                        .withAnimations(AnimationsBuilder.get().withEnabled(false).build()).build())
                .withTheme(ThemeBuilder.get().withMode(Mode.DARK).build())
                .withXaxis(buildXaxis(categories))
                .withYaxis(YAxisBuilder.get().withTitle(TitleBuilder.get().withText("latency (s)").build()).build())
                .withSeries(SeriesBuilder.get().withName("latency (s)").withData(data).build())
                .build();
        chart.setHeight("220px");
        chart.setVisible(false);
        return chart;
    }

    /** #588, found live: job 44484604 (paused, 48+ sessions, hundreds of requests) threw a
     *  permanent client-side `TypeError: Cannot read properties of undefined (reading '0')` in
     *  ApexChartsWrapper.updateData on cold page load, repeating on every single poll tick for the
     *  rest of the page's life. #546 (redrawChart only from the owning view's own poll-driven
     *  update(), never from a raw SSE event) successfully caps this method to ONE call per 2s poll
     *  tick, but does nothing about the SIZE of what that first call hands to updateSeries(): on a
     *  large-backlog job, the SSE replay can deliver hundreds of already-finished requests before
     *  the very first poll tick ever fires, so that first call jumps from the chart's own
     *  just-built EMPTY series straight to hundreds of points in one shot - and ApexCharts' own
     *  client-side updateSeries()/updateData does not recover once a single call like that races
     *  and throws (see buildRequestLatencyChart()'s own javadoc on the sibling animation bug this
     *  class already worked around once before).
     *
     *  shouldRebuildChart() decides, per call, whether this jump is small enough for the existing
     *  cheap incremental path (setXaxis()+updateSeries() on the SAME instance - a running job polls
     *  every 2s, and a full chart replacement that often would flash/flicker the same way a
     *  full-DOM rebuild would steal focus elsewhere on this page) or large enough that a fresh
     *  ApexCharts instance - built WITH the real data from construction via
     *  requestLatencyChartWithData(), never calling updateSeries() on it at all - is swapped in
     *  instead. A rebuilt chart has no corrupted client-side state to inherit, so even if the
     *  SAME large-jump pattern recurs on a later poll tick (a reconnect replaying a burst mid-
     *  session), it gets a clean instance again rather than compounding on an already-broken one. */
    private void updateRequestLatencyChart(final List<JobLiveState.RequestRow> recent) {
        final var categories = new String[recent.size()];
        for (int i = 0; i < recent.size(); i++) {
            categories[i] = String.valueOf(i + 1);
        }
        final var data = recent.stream().map(JobLiveState.RequestRow::latencySec).toArray(Double[]::new);
        if (shouldRebuildChart(lastChartedPointCount, recent.size())) {
            final var fresh = requestLatencyChartWithData(categories, data);
            fresh.setVisible(requestLatencyChart.isVisible());
            requestLatencyChartHolder.removeAll();
            requestLatencyChartHolder.add(fresh);
            requestLatencyChart = fresh;
        } else {
            requestLatencyChart.setXaxis(buildXaxis(categories));
            requestLatencyChart.updateSeries(SeriesBuilder.get().withName("latency (s)").withData(data).build());
        }
        lastChartedPointCount = recent.size();
    }

    /** #588-followup, found live on job cd4c11a8 right after #588 itself deployed: the ORIGINAL
     *  TypeError still reproduced - tracing the browser's own stack trace into apexcharts-
     *  wrapper.ts (the vendored client source, com.github.appreciated:apexcharts:25.0.1) showed
     *  updateData() is reachable ONLY via an explicit server-side updateSeries() call
     *  (chartComponent.updateSeries(...)), never via the construction/attach path (firstUpdated()
     *  builds a fresh ApexCharts instance and calls its own async beginRender()/render() directly -
     *  a different method entirely, confirmed by decompiling ApexChartsBuilder.build(): it only
     *  ever calls setSeries(), never updateSeries()). So the crash was never really about DATA
     *  SIZE: it's that the chart's very first render() is async, and a chart instance's very first
     *  updateSeries()/setXaxis() call - EVEN ONE CARRYING NO DATA AT ALL - can land before that
     *  promise resolves, at which point ApexCharts' own client code reads internal state render()
     *  would have set up and throws. A job that hasn't made its first LLM request yet calls this
     *  method every ~2s poll tick with recent still empty (0,0) - an all-zero FIRST attempt at
     *  gating on "lastChartedPointCount == 0 && newPointCount > 0" missed exactly this case, since
     *  it still let the all-zero call through to the incremental (unsafe) branch.
     *
     *  <p>True whenever the CURRENT chart instance has never had a successful redraw recorded yet
     *  (lastChartedPointCount == 0, regardless of how much or little there now is to show - the
     *  one signal available server-side for "this instance's own first render() may still be in
     *  flight"), or the jump since the last successful redraw is large enough that ApexCharts' own
     *  incremental updateSeries() is a known-risky path for an unrelated reason (a reconnect
     *  replaying a burst mid-session against an instance that HAS already rendered once). False
     *  for the small, single-digit-per-tick growth a genuinely live job produces between ordinary
     *  2s poll ticks on an instance that already survived at least one update - by then its first
     *  render() is long done, so the cheap in-place path is both safe and worth keeping (no
     *  flicker, no lost scroll/hover state). The cost of rebuilding on every all-zero tick before a
     *  job's first request lands is a few harmless, invisible (setVisible(false)) DOM replacements -
     *  cheap next to a permanently broken chart for the rest of the page's life. */
    static boolean shouldRebuildChart(final int lastChartedPointCount, final int newPointCount) {
        return lastChartedPointCount == 0 || newPointCount - lastChartedPointCount > 10;
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
        //
        // #630-followup, found live: EVERY session row routed here, including a real task's own
        // ("T5", "T5-judge-turn5", ...) and every pipeline stage's (REVIEW/TRAJECTORY_REVIEW/
        // INTEGRATION/p0_definition/p1_plan/PARALLEL_PLAN) - but JobSessionDialog only has real
        // content for the three pre-task phases; every other row (a real task, REVIEW,
        // TRAJECTORY_REVIEW, INTEGRATION) landed on its own dead-end "There is no detail view for
        // this session" message, even though JobTaskDialog's Stats tab already has real, live
        // turns/tokens/wall-time data for every one of them (confirmed live: REVIEW's own Plan-grid
        // row already opens JobTaskDialog correctly and shows "974 so far" turns - only the
        // SESSIONS grid's own routing was wrong). Matches the row's own raw session label against
        // planTasks via JobDetailView.matchesSession() - the SAME matching the Plan grid's own
        // task-stats accumulation already uses - and opens JobTaskDialog whenever a PlanTask is
        // found (every id in planTasks(), stage or real, matches itself exactly); only a session
        // with genuinely no corresponding PlanTask (planTasks hasn't loaded yet, or a stray/
        // unexpected label) falls back to JobSessionDialog.
        sessions.addItemClickListener(e -> {
            final var row = e.getItem();
            final var task = matchingTask(planTasks, row);
            if (task.isPresent()) JobTaskDialog.open(client, jobId, task.get(), () -> currentJob, live, this, onChanged);
            else JobSessionDialog.open(client, jobId, () -> currentJob, row, onChanged);
        });
        sessions.getStyle().set("cursor", "pointer");
        return sessions;
    }

    /** The Sessions grid's own row-click routing decision, pulled out as its own pure, directly-
     *  testable method - the click listener itself isn't (Vaadin's click-to-dialog wiring has no
     *  unit-test harness in this codebase, and JobTaskDialog.open()/JobSessionDialog.open() both
     *  fire real network calls the moment they're invoked). Empty when no PlanTask's own id
     *  matches this row's raw label (planTasks hasn't loaded yet, or a stray/unexpected label) -
     *  the caller then falls back to JobSessionDialog instead of passing a null task through. */
    static java.util.Optional<Api.PlanTask> matchingTask(final List<Api.PlanTask> planTasks, final JobLiveState.SessionRow row) {
        return planTasks.stream().filter(t -> JobDetailView.matchesSession(row.name(), t.id())).findFirst();
    }
}
