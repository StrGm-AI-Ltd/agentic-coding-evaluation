package com.strgmai.ace.ui;

import com.github.appreciated.css.grid.sizes.Flex;
import com.github.appreciated.css.grid.sizes.Length;
import com.github.appreciated.css.grid.sizes.MinMax;
import com.github.appreciated.css.grid.sizes.Repeat;
import com.github.appreciated.layout.FlexibleGridLayout;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.shared.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Job detail — the primary jobs/:jobId page. Started as a parallel, read-only UX-redesign
 * prototype at jobs/:jobId/prototype that deliberately didn't touch JobDetailView.java while this
 * explored a different information hierarchy on the same real data; now promoted to the main
 * route, and (Phase 2 of closing the live-monitoring gap - see the plan this followed) fully live:
 * a 2s poll + the same SSE-connected JobLiveSection the classic page uses, so Sessions, Recent
 * requests, a live log tail, and Cancel/Pause/Requeue now work here too, for any job regardless of
 * status - the old terminal-only guard is gone. JobDetailView.java stays at jobs/:jobId/classic
 * (kept fully intact, not deleted) as a fallback with its own older presentation, but has nothing
 * left this page can't now also do.
 *
 * <p>The dashboard tile row, Params tile, and Plan grid's own status/attempts columns still read
 * from the same per-task GET /api/jobs/{id}/tasks/{taskId}/attempts calls this view has used since
 * its read-only-prototype days (see class history below) - deliberately NOT switched to
 * JobLiveSection's own live sessions for status, even though that data is now available for free:
 * re-fetching N tasks' attempts every 2s poll is a real, known cost (same one this class's own
 * earlier javadoc already flagged for the one-time case), but switching derivations mid-refactor
 * risked a DIFFERENT, harder-to-spot regression (JobDetailView's own isTaskDone()/taskStatus() both
 * branch on PlanTask.implemented(), which this class's own research already found false for every
 * task in this DB - they only read right on the real page because of a fallback this class doesn't
 * share). Flagged as a known cost to revisit if this page sees long-running jobs with large plans,
 * not fixed here.
 *
 * <p>The Plan grid's own time/generated/prefilled columns, by contrast, DO now read live:
 * JobLiveSection's continuously-updated JobLiveState replaces this class's old one-shot
 * replaySessions() hack (same data, same JobDetailView.taskStats() reuse, just kept fresh instead
 * of captured once at page load).
 *
 * Found live, checking this against real data: PlanTask.implemented() is false for EVERY task on
 * every already-succeeded job actually in this DB (checked l2-gran-fine-4, l2-gran-medium-6) - so
 * JobDetailView.taskStatus()'s own "implemented() once no longer live" fallback (which this
 * prototype first tried calling with an empty session list, mirroring it) renders every row as the
 * same unhelpful "see Run detail" placeholder. Rather than reproduce that machinery, this prototype
 * instead calls the already-existing, already-accurate GET /api/jobs/{id}/tasks/{taskId}/attempts
 * per real (non-stage) task and reads its own LAST attempt's reported_status/rc - real, correctly-
 * populated data, confirmed against the same job (T1: reported_status="done", rc=0).
 *
 * Design grounded in current (2026) dashboard/detail-page UX research, not just aesthetic
 * preference:
 * - Status and key figures first, above the fold; drill-down detail below (aufaitux.com, 925studios.co).
 * - Run flags were initially tucked behind a collapsed "Run details" panel (progressive
 *   disclosure, the dominant 2026 SaaS-dashboard pattern for secondary detail), then promoted to
 *   an always-visible card above Plan, and now live as the "Params" tile inside the stat-tile grid
 *   itself - flags are what EXPLAINS a run's own behavior, not secondary detail an operator
 *   reaches for only occasionally. The new live sections (Sessions/Recent requests/Live output)
 *   follow the SAME always-visible principle, for every job regardless of status: unlike the
 *   classic page (which hides Sessions/Requests once a job is terminal - nothing left to watch
 *   live), this page keeps them populated from the replayed session history even for a finished
 *   job, via JobLiveSection's own alwaysShowLiveData flag - a run's own session/request history
 *   stays worth reviewing after the fact, not just while it's still running.
 * - 5-9 core elements per view, muted neutrals with color reserved for status specifically -
 *   deliberately NOT a "bento grid" of asymmetric tile sizes: that pattern is explicitly called
 *   out in 2026 UI-trend research as wrong for data-dense PRODUCT dashboards (orbix.studio) - it
 *   conflicts with the uniform left-to-right/top-to-bottom scanning a monitoring page like this
 *   needs. The stat-tile row below uses appreciated/grid-layout's FlexibleGridLayout with a
 *   responsive `repeat(auto-fit, minmax(180px, 1fr))` column template instead: every compact tile
 *   the SAME size, reflowing by count rather than by deliberately uneven sizing - with ONE
 *   intentional exception, the "Params" tile (the job's own CLI flags), which spans the full grid
 *   row via `grid-column: 1 / -1` because free-form argv text doesn't fit a short value span. The
 *   live sections carry that same bordered-tile chrome into Sessions/Recent requests/Live output,
 *   rather than the classic page's own plain, unbordered presentation of the same data.
 */
@Route(value = "jobs/:jobId", layout = MainLayout.class)
public class JobDetailPrototypeView extends VerticalLayout implements BeforeEnterObserver {
    private static final Logger log = LoggerFactory.getLogger(JobDetailPrototypeView.class);

    private final ServiceClient client;
    private String jobId;
    private Api.Job currentJob;
    private String lastStatus;
    private transient Registration pollRegistration;
    private boolean chromeBuilt;
    private boolean loadFailed;
    private String lastActionsSignature;

    private JobLiveSection liveSectionComponent;
    private final Div dashboardHolder = new Div();
    private final Div statusHolder = new Div();
    private final Div actionsHolder = new Div();
    // width:100%+box-sizing:border-box - a plain Div isn't stretched to its own parent's width by
    // a VerticalLayout by default (same gotcha Panels.card() itself documents); without this, this
    // holder shrink-wraps to its own widest child's intrinsic content (a <vaadin-grid>'s own
    // auto-width columns, unlike FlexibleGridLayout's flexible minmax() tracks, don't shrink below
    // their natural width) - found live via getBoundingClientRect() at ~1997px against the page's
    // own ~1122px before this was added.
    private final Div liveCardsHolder = new Div();
    private final Span errorLine = new Span();
    private final Grid<Api.PlanTask> planGrid = buildPlanGrid();
    private List<Api.PlanTask> planTasks = List.of();
    private final Map<String, Api.TaskAttempt> lastAttempt = new HashMap<>();

    public JobDetailPrototypeView(final ServiceClient client) {
        this.client = client;
        setPadding(true);

        addAttachListener(event -> {
            if (pollRegistration != null) { // a defensive guard against double attach
                pollRegistration.remove();
                pollRegistration = null;
            }
            if (loadFailed) {
                return; // the initial load already failed - never arm polling for this view
            }
            final var ui = event.getUI();
            pollRegistration = ui.addPollListener(e -> refresh());
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
    public void beforeEnter(final BeforeEnterEvent event) {
        final var raw = event.getRouteParameters().get("jobId").orElse(null);
        jobId = (raw == null || raw.isBlank()) ? null : raw;
        if (jobId == null) {
            add(new ViewHeader("Job detail"), Panels.error("No valid job id in the URL."));
            chromeBuilt = true;
            return;
        }
        try {
            final var job = client.job(jobId);
            buildChrome(job);
            update(job);
        } catch (final Exception e) {
            log.warn("could not load job {}: {}", jobId, e.toString());
            add(new ViewHeader("Job detail"), Panels.error(client.errorText(e)));
            loadFailed = true;
        }
    }

    /** The static chrome, built once — every later poll cycle only updates fields/rebuilds the
     *  dashboard row in place. liveSectionComponent's own default layout (plannedOrderLine, its own
     *  stat tiles, a "Recent requests" card) is never shown - see its own constructor javadoc for the
     *  reparenting pattern this uses instead: a throwaway Div as its planCard slot, immediately
     *  hidden, with its real content (sessionsGrid/requestsGrid/requestLatencyChart/logTail/
     *  requestsLine) pulled out via its new accessor methods into this page's own bordered-tile
     *  cards below. It still gets add()ed to the page (invisible) purely so its own attach/detach
     *  listener keeps driving the SSE connection. */
    private void buildChrome(final Api.Job job) {
        if (chromeBuilt) {
            return;
        }
        chromeBuilt = true;

        add(new ViewHeader("Job detail"));
        final var note = new Span("Live - updates every 2s while the job is running.");
        note.getStyle().set("font-size", "0.85em").set("opacity", "0.75");
        add(note);
        add(new RouterLink("← Queue", JobsView.class));
        add(errorLine);
        errorLine.getStyle().set("font-size", "0.85em");

        final var openClassic = new Button("Open classic page",
                e -> getUI().ifPresent(ui -> ui.navigate("jobs/" + jobId + "/classic")));
        openClassic.addThemeVariants(ButtonVariant.TERTIARY);
        final var titleRow = new HorizontalLayout(statusHolder, openClassic);
        titleRow.setAlignItems(FlexComponent.Alignment.CENTER);
        titleRow.setSpacing(true);
        titleRow.setPadding(false);
        final var runTitle = new Div();
        runTitle.setText(job.run_id() == null ? job.id() : job.run_id());
        runTitle.getStyle().set("font-size", "1.6em").set("font-weight", "600").set("margin", "8px 0 4px 0");
        add(runTitle, titleRow);

        actionsHolder.getStyle().set("margin-top", "8px");
        add(actionsHolder);

        dashboardHolder.getStyle().set("width", "100%").set("box-sizing", "border-box");
        add(dashboardHolder);

        final var planCard = Panels.card("Plan", planGrid);
        tightenCardChrome(planCard);
        add(planCard);

        // alwaysShowLiveData=true - unlike the classic page, a finished job's Sessions/Recent
        // requests/Live output stay populated here instead of blanking once terminal (see
        // JobLiveSection's own constructor javadoc for the flag). onLiveChanged re-sets the Plan
        // grid's items on every live update, not just every 2s poll tick - same reason
        // JobDetailView's own onLiveChanged does this (see its buildChrome()'s own comment): the
        // Plan grid's time/generated/prefilled columns read liveSectionComponent.live() lazily, and
        // a terminal job's one-shot replay can otherwise deliver its whole session history between
        // one poll tick and the next, leaving the grid stuck on its first (likely all-zero) render
        // until the next scheduled poll catches up.
        liveSectionComponent = new JobLiveSection(client, jobId, this::refresh,
                () -> planGrid.setItems(planTasks), new Div(), true);
        liveSectionComponent.setVisible(false);
        add(liveSectionComponent);

        liveCardsHolder.getStyle().set("width", "100%").set("box-sizing", "border-box");
        liveCardsHolder.add(
                liveTile("Sessions", liveSectionComponent.sessionsGrid()),
                liveTile("Recent requests", liveSectionComponent.requestsLine(),
                        liveSectionComponent.requestLatencyChart(), liveSectionComponent.requestsGrid()),
                liveTile("Live output", liveSectionComponent.logTail()));
        add(liveCardsHolder);
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
            log.debug("poll fetch failed for job {}: {}", jobId, e.toString());
            errorLine.setText(client.errorText(e));
        }
    }

    private void update(final Api.Job job) {
        currentJob = job;
        lastStatus = job.status();
        final var terminal = JobStatuses.isTerminal(job.status());
        getUI().ifPresent(ui -> ui.setPollInterval(terminal ? -1 : 2000));

        statusHolder.removeAll();
        statusHolder.add(Badges.status(job.status()));

        final var signature = job.status() + "|" + job.cancel_requested() + "|" + job.pause_requested();
        if (!signature.equals(lastActionsSignature)) {
            lastActionsSignature = signature;
            actionsHolder.removeAll();
            actionsHolder.add(JobDetailView.buildCancelPauseRequeueRow(client, job, this::refresh));
        }

        if (planTasks.isEmpty()) {
            try {
                final var resp = client.jobPlan(jobId);
                if (resp != null && resp.tasks() != null && !resp.tasks().isEmpty()) {
                    planTasks = resp.tasks();
                }
            } catch (final Exception e) {
                log.debug("could not fetch plan for job {}: {}", jobId, e.toString());
            }
        }
        for (final var t : planTasks) {
            if (t.stage()) continue;
            try {
                final var attempts = client.taskAttempts(job.id(), t.id());
                if (!attempts.isEmpty()) lastAttempt.put(t.id(), attempts.get(0));
            } catch (final Exception e) {
                // best-effort - that task's row falls back to statusOf()'s own "–" below
            }
        }

        dashboardHolder.removeAll();
        if ("failed".equals(job.status()) && hasResult(job)) {
            dashboardHolder.add(Panels.error(job.result_line()));
        }
        final var statGrid = statTileGrid(job, planTasks, lastAttempt);
        statGrid.getStyle().set("margin", "12px 0 4px 0");
        dashboardHolder.add(statGrid);

        planGrid.setVisible(!planTasks.isEmpty());
        planGrid.setItems(planTasks);

        liveSectionComponent.update(job, planTasks, null);
    }

    /** Panels.card()'s own 16px horizontal padding and sectionTitle()'s own 16px margin-top are
     *  both part of the same over-tall gap found live against the stat-tile row's own 32px rhythm
     *  (see the original prototype's own commit history) - zeroed/shrunk here, locally, the same
     *  way every other card on this page already is, rather than touching Panels.java itself. */
    private static void tightenCardChrome(final Div card) {
        card.getStyle().set("padding", "12px 0").set("margin", "0 0 10px 0");
        card.getChildren().findFirst().ifPresent(title -> title.getElement().getStyle().set("margin-top", "4px"));
    }

    /** A live section (Sessions / Recent requests / Live output) in the same bordered-tile chrome
     *  as the dashboard row and Params tile above, rather than the classic page's own plain,
     *  unbordered card - see class javadoc for why this got a genuine redesign pass instead of
     *  just porting the old section styling. Full-width (the live grids need the room a 1/7-column
     *  dashboard tile never would) - width:100%+box-sizing:border-box is required for that, the
     *  same fix Panels.card() already documents needing: a VerticalLayout does not stretch a plain
     *  child Div to its own width by default, so a Grid's own wide intrinsic content width (Sessions
     *  has 9 columns) pushes an unconstrained wrapper out to it instead of the other way around -
     *  found live via getBoundingClientRect() at ~1997px wide against the page's own ~1122px. */
    private static Div liveTile(final String label, final com.vaadin.flow.component.Component... content) {
        final var title = new Span(label);
        title.getStyle().set("font-size", "0.8em").set("text-transform", "uppercase").set("letter-spacing", "0.03em");
        final var tile = new Div();
        tile.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "8px")
                .set("border", "1px solid var(--vaadin-border-color-secondary)")
                .set("border-radius", "var(--vaadin-radius-l)")
                .set("background", "var(--vaadin-background-container)")
                .set("padding", "10px 14px")
                .set("margin", "16px 0 0 0")
                .set("width", "100%")
                .set("box-sizing", "border-box");
        tile.add(title);
        tile.add(content);
        return tile;
    }

    private static boolean hasResult(final Api.Job job) {
        return job.result_line() != null && !job.result_line().isBlank();
    }

    /** The compact "Result" tile value - full text for everything EXCEPT a failed job, whose own
     *  result_line is already shown verbatim, full-width, right above the fold (see update()'s own
     *  failed-job banner) - repeating it here too wrapped the whole error into the tile and broke
     *  the uniform tile height every other tile in this row keeps. The leading "RESULT " word
     *  (every real succeeded job's own result_line starts with it, confirmed against 8 real rows)
     *  is stripped - the tile's own label already reads "Result", so it was just repeating itself. */
    private static String resultTileValue(final Api.Job job) {
        if (!hasResult(job)) return "–";
        if ("failed".equals(job.status())) return "see above";
        final var line = job.result_line();
        return line.startsWith("RESULT ") ? line.substring("RESULT ".length()) : line;
    }

    /** The "above the fold" status dashboard - every tile the SAME size, in a responsive CSS grid
     *  that reflows by column COUNT (auto-fit) rather than by deliberately uneven tile sizing.
     *  Each tile gets a visible border/surface/radius here - the official Vaadin Dashboard
     *  widget's own look (vaadin.com/docs/latest/components/dashboard) - rather than through
     *  Panels.statTile() itself, which JobDetailView (the real, live page) also depends on and
     *  deliberately dropped all border/background chrome from during the Aura migration (#529);
     *  changing that shared helper would have silently changed the real page's own stat tiles too.
     *  Rebuilt wholesale every poll cycle (unlike the Plan grid, which updates its items in place) -
     *  these tiles have no interactive/focusable state worth preserving between polls, so the
     *  simpler full-rebuild costs nothing a reader would notice. */
    private FlexibleGridLayout statTileGrid(final Api.Job job, final List<Api.PlanTask> tasks,
            final Map<String, Api.TaskAttempt> lastAttempt) {
        final var realTasks = tasks.stream().filter(t -> !t.stage()).toList();
        final var done = realTasks.stream().filter(t -> {
            final var a = lastAttempt.get(t.id());
            return a != null && "done".equals(a.reported_status());
        }).count();
        final var grid = new FlexibleGridLayout()
                .withColumns(Repeat.RepeatMode.AUTO_FIT, new MinMax(new Length("130px"), new Flex(1)))
                .withAutoRows(new Length("auto"))
                .withGap(new Length("32px"))
                .withItems(
                        Panels.dashboardTile("Kind", job.kind() + (job.arm() != null ? " · " + job.arm() + " r" + job.repeat() : "")),
                        Panels.dashboardTile("Model", modelOf(job)),
                        Panels.dashboardTile("Priority", job.priority() == null ? "–" : String.valueOf(job.priority())),
                        Panels.dashboardTile("Enqueued", Fmt.when(job.enqueued_at())),
                        Panels.dashboardTile("Duration", durationOf(job)),
                        Panels.dashboardTile("Tasks", done + " / " + realTasks.size()),
                        Panels.dashboardTile("Result", resultTileValue(job)),
                        paramsTile(job));
        grid.getStyle().set("width", "100%").set("margin", "12px 0");
        return grid;
    }

    /** The "Params" tile - the job's own CLI invocation flags (job.argv()), one pill-shaped Badge
     *  per flag instead of a flat mono-space blob - the same Badge component the status pill up
     *  top already renders with (Badges.status()). Neutral CONTRAST theme by default (per this
     *  class's own design rationale above, color is reserved for status specifically, and a param
     *  isn't a status), except a purely numeric value switches to WARNING - a quick visual pick-out
     *  for the token/time budget knobs sitting among the state ("ref", "agent", "on") and
     *  freeform-text flags. Spans every column via `grid-column: 1 / -1` and lands in its own row
     *  below the compact tiles through normal grid auto-placement - no explicit row index needed. */
    private static Div paramsTile(final Api.Job job) {
        final var argv = job.argv() == null ? List.<String>of() : job.argv();
        final var chips = new Div();
        chips.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "6px").set("margin-top", "2px");
        if (argv.isEmpty()) {
            chips.add(new Span("–"));
        } else {
            argv.stream().sorted(Comparator.comparing(JobDetailPrototypeView::paramKey))
                    .forEach(a -> chips.add(paramBadge(a)));
        }
        final var tile = Panels.statTile("Params", chips);
        tile.getStyle()
                .set("border", "1px solid var(--vaadin-border-color-secondary)")
                .set("border-radius", "var(--vaadin-radius-l)")
                .set("background", "var(--vaadin-background-container)")
                .set("padding", "8px 10px")
                .set("grid-column", "1 / -1");
        return tile;
    }

    /** Strips the leading "--", then turns "param-name=value" into "param name: value" - dashes
     *  replaced with spaces in the key only (never the value, which may legitimately contain its
     *  own "-", e.g. a run id); a bare flag with no "=" (e.g. "--self-review") just gets its own
     *  dashes replaced, with no ": " since there's no value to separate it from. #551: any
     *  model-identifying flag (--model, --judge-model, --reviewer-model,
     *  --trajectory-reviewer-model - every one of them named "...-model") gets Badges.SUCCESS
     *  (green) instead, ahead of the numeric-vs-text default, so the model(s) a run actually used
     *  stand out from the rest of the params row. */
    static Badge paramBadge(final String arg) {
        final var stripped = arg.startsWith("--") ? arg.substring(2) : arg;
        final var eq = stripped.indexOf('=');
        if (eq < 0) {
            return Badges.text(stripped.replace('-', ' '), Badges.CONTRAST);
        }
        final var key = stripped.substring(0, eq).replace('-', ' ');
        final var value = stripped.substring(eq + 1);
        final var theme = key.endsWith("model") ? Badges.SUCCESS
                : value.matches("-?\\d+(\\.\\d+)?") ? Badges.WARNING : Badges.CONTRAST;
        return Badges.text(key + ": " + value, theme);
    }

    /** The sort key for a raw argv entry - its key portion (before "=", or the whole flag when
     *  there's none), pre-dash-replacement so it sorts the same regardless of the display format. */
    private static String paramKey(final String arg) {
        final var stripped = arg.startsWith("--") ? arg.substring(2) : arg;
        final var eq = stripped.indexOf('=');
        return eq < 0 ? stripped : stripped.substring(0, eq);
    }

    /** The real, available signal for a task's outcome - see class javadoc for why this isn't
     *  PlanTask.implemented()/reported_status() (both unreliable on a finished job here), nor
     *  JobDetailView's own taskStatus() (same implemented()-rooted unreliability, via a different
     *  branch). Works for a still-running task too: a task with no attempt yet reads "–", the same
     *  as before any task had ever been reached. */
    private static String statusOf(final Api.PlanTask task, final Map<String, Api.TaskAttempt> lastAttempt) {
        if (task.stage()) return "–";
        final var a = lastAttempt.get(task.id());
        if (a == null) return "–";
        if (a.reported_status() != null) return a.reported_status();
        return a.rc() == 0 ? "ok (rc 0)" : "failed (rc " + a.rc() + ")";
    }

    /** --model=<value> out of argv - the same raw source every other value on this page already
     *  comes from; no separate endpoint for it. */
    private static String modelOf(final Api.Job job) {
        if (job.argv() == null) return "–";
        return job.argv().stream()
                .filter(a -> a.startsWith("--model="))
                .map(a -> a.substring("--model=".length()))
                .findFirst().orElse("–");
    }

    private static String durationOf(final Api.Job job) {
        final var start = Fmt.parseTime(job.started_at());
        if (start == null) return "–";
        final var end = job.finished_at() != null ? Fmt.parseTime(job.finished_at()) : null;
        if (end == null) return "running…";
        return Fmt.daysHoursMinutes(Duration.between(start, end).getSeconds());
    }

    /** Built once (field initializer, before jobId/liveSectionComponent exist) - column value
     *  functions close over this.lastAttempt/this.liveSectionComponent, re-evaluated fresh every
     *  time update() calls setItems() again, same pattern JobDetailView's own buildPlanGrid()/
     *  updatePlanGrid() split already established: a fresh Grid instance every poll would discard
     *  the user's own column resize/sort state every 2s, exactly what that pattern avoids. The
     *  time/generated/prefilled trio now reads liveSectionComponent.live() - continuously kept
     *  current by its own SSE connection - replacing the old one-shot replaySessions() hack. */
    private Grid<Api.PlanTask> buildPlanGrid() {
        final var grid = new Grid<>(Api.PlanTask.class, false);
        grid.addColumn(Api.PlanTask::order).setHeader("#").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(Api.PlanTask::id).setHeader("task").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> t.goal() == null || t.goal().isBlank()
                        ? (t.title() == null ? "" : t.title()) : t.goal())
                .setHeader("description").setFlexGrow(1);
        grid.addColumn(t -> statusOf(t, lastAttempt)).setHeader("status").setAutoWidth(true).setFlexGrow(0)
                .setTooltipGenerator(t -> {
                    final var a = lastAttempt.get(t.id());
                    return a == null ? null : a.reported_detail();
                });
        grid.addColumn(t -> Fmt.daysHoursMinutes(JobDetailView.taskStats(
                        liveSectionComponent.live().sessions(), t.id(), liveSectionComponent.live().currentStep()).wallSec()))
                .setHeader("time").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> Fmt.count(JobDetailView.taskStats(
                        liveSectionComponent.live().sessions(), t.id(), liveSectionComponent.live().currentStep()).completionTokens()))
                .setHeader("generated").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> Fmt.count(JobDetailView.taskStats(
                        liveSectionComponent.live().sessions(), t.id(), liveSectionComponent.live().currentStep()).promptTokens()))
                .setHeader("prefilled").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> t.attempts() <= 1 && !t.rescued() ? "" : t.attempts() + (t.rescued() ? " (rescued)" : ""))
                .setHeader("attempts").setAutoWidth(true).setFlexGrow(0);
        grid.getColumns().forEach(c -> c.setResizable(true));
        grid.addItemClickListener(e -> JobTaskDialog.open(client, jobId, e.getItem(), () -> currentJob,
                liveSectionComponent.live(), this, this::refresh));
        grid.getStyle().set("cursor", "pointer");
        grid.setVisible(false);
        grid.setHeight("420px");
        return grid;
    }
}
