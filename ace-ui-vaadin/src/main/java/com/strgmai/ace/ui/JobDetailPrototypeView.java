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
import com.vaadin.flow.router.RouterLink;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Job detail — the primary jobs/:jobId page. Started as a parallel, read-only UX-redesign
 * prototype at jobs/:jobId/prototype that deliberately didn't touch JobDetailView.java while this
 * explored a different information hierarchy on the same real data; now promoted to the main
 * route. JobDetailView.java moved to jobs/:jobId/classic rather than being deleted - it stays
 * fully intact as the fallback for an in-progress job (see the terminal-job guard in beforeEnter()
 * below), since this page still doesn't have a Sessions table, Recent requests, a live log tail,
 * or Restart/Resume controls.
 *
 * Still a STATIC SNAPSHOT for everything except the Plan grid's own time/generated/prefilled
 * columns: it fetches the job + plan once via the same ServiceClient the real page uses, and does
 * not keep JobDetailView's own 2s-poll/@Push machinery running for live field updates. It DOES do
 * one synchronous, one-shot SSE replay (replaySessions() below) to populate those three Plan
 * columns, reusing JobDetailView.taskStats(sessions, taskId, currentStep) directly rather than
 * re-deriving that aggregation - see replaySessions()'s own javadoc for why blocking on it here is
 * safe. Everything else session-level (the Sessions table itself, Recent requests, the live log
 * tail) is still out of scope - this page only renders TERMINAL jobs (see the
 * JobStatuses.isTerminal() guard in beforeEnter() below) and links to the classic page for
 * anything still running or queued.
 *
 * Found live, checking this against real data: PlanTask.implemented() is false for EVERY task on
 * every already-succeeded job actually in this DB (checked l2-gran-fine-4, l2-gran-medium-6) - so
 * JobDetailView.taskStatus()'s own "implemented() once no longer live" fallback (which this
 * prototype first tried calling with an empty session list, mirroring it) renders every row as the
 * same unhelpful "see Run detail" placeholder. The real page never hits this because it always
 * populates live.sessions() via an SSE replay first (JobDetailView.beforeEnter()'s own comment:
 * "the ONLY path that ever populates live.sessions()...even for an already-finished job"). Rather
 * than reproduce that machinery, this prototype instead calls the already-existing, already-
 * accurate GET /api/jobs/{id}/tasks/{taskId}/attempts per real (non-stage) task and reads its own
 * LAST attempt's reported_status/rc - real, correctly-populated data, confirmed against the same
 * job (T1: reported_status="done", rc=0). The real cost: one blocking HTTP round-trip per task,
 * sequential, on every page load - fine for this review-only route on a handful of tasks against a
 * local service, but NOT a pattern to carry into production without either a batch endpoint or
 * reusing the real page's own SSE-replay state.
 *
 * Design grounded in current (2026) dashboard/detail-page UX research, not just aesthetic
 * preference:
 * - Status and key figures first, above the fold; drill-down detail below (aufaitux.com, 925studios.co).
 * - Run flags were initially tucked behind a collapsed "Run details" panel (progressive
 *   disclosure, the dominant 2026 SaaS-dashboard pattern for secondary detail), then promoted to
 *   an always-visible card above Plan, and now live as the "Params" tile inside the stat-tile grid
 *   itself - flags are what EXPLAINS a run's own behavior, not secondary detail an operator
 *   reaches for only occasionally.
 * - 5-9 core elements per view, muted neutrals with color reserved for status specifically -
 *   deliberately NOT a "bento grid" of asymmetric tile sizes: that pattern is explicitly called
 *   out in 2026 UI-trend research as wrong for data-dense PRODUCT dashboards (orbix.studio) - it
 *   conflicts with the uniform left-to-right/top-to-bottom scanning a monitoring page like this
 *   needs. The stat-tile row below uses appreciated/grid-layout's FlexibleGridLayout with a
 *   responsive `repeat(auto-fit, minmax(180px, 1fr))` column template instead: every compact tile
 *   the SAME size, reflowing by count rather than by deliberately uneven sizing - with ONE
 *   intentional exception, the "Params" tile (the job's own CLI flags), which spans the full grid
 *   row via `grid-column: 1 / -1` because free-form argv text doesn't fit a short value span.
 */
@Route(value = "jobs/:jobId", layout = MainLayout.class)
public class JobDetailPrototypeView extends VerticalLayout implements BeforeEnterObserver {

    private final ServiceClient client;

    public JobDetailPrototypeView(final ServiceClient client) {
        this.client = client;
        setPadding(true);
    }

    @Override
    public void beforeEnter(final BeforeEnterEvent event) {
        removeAll();
        final var jobId = event.getRouteParameters().get("jobId").orElse(null);
        add(new ViewHeader("Job detail"));
        final var note = new Span("Static snapshot - no live updates, Sessions, or Recent requests here yet. "
                + "Use the classic page for an in-progress job, or to intervene on a run.");
        note.getStyle().set("font-size", "0.85em").set("opacity", "0.75");
        add(note);
        if (jobId == null) {
            add(Panels.error("No valid job id in the URL."));
            return;
        }
        try {
            final var job = client.job(jobId);
            if (!JobStatuses.isTerminal(job.status())) {
                add(stillRunningNotice(job.status(), jobId));
                return;
            }
            final var plan = client.jobPlan(jobId);
            render(job, plan.tasks() == null ? List.of() : plan.tasks(), replaySessions(jobId));
        } catch (final Exception e) {
            add(Panels.error(client.errorText(e)));
        }
    }

    /** Same chrome as Panels.warn() (padding/border-radius/margin/white-space), rebuilt locally
     *  because that helper only takes plain text (setText() would wipe out a child RouterLink) -
     *  the one case on this page where the warning itself needs a link, not just a sentence. */
    private static Div stillRunningNotice(final String status, final String jobId) {
        final var text = new Span("This page only renders finished jobs (see the class javadoc for why) "
                + "- this job is still " + status + ". ");
        final var classicLink = new RouterLink("Open the classic page", JobDetailView.class,
                new com.vaadin.flow.router.RouteParameters("jobId", jobId));
        final var notice = new Div(text, classicLink);
        notice.getStyle()
                .set("padding", "8px 12px")
                .set("border-radius", "4px")
                .set("margin", "6px 0")
                .set("white-space", "pre-wrap");
        return notice;
    }

    /** One-shot, synchronous replay of /jobs/{id}/events into a fresh JobLiveState - safe to block
     *  on here (unlike the real page, which keeps this stream open for live updates) because the
     *  job is already confirmed terminal at this point: JobEventLoop's own class doc already
     *  establishes that the server itself ends the stream once a job is terminal, after replaying
     *  its full backlog, so this returns promptly with the complete session history and never
     *  needs a background thread or @Push. Reuses the exact same JobLiveState the real page
     *  accumulates its Sessions table from, rather than re-deriving time/token totals from scratch -
     *  best-effort: a transient failure here just means the Plan grid's time/generated/prefilled
     *  columns fall back to "–" for every task, not that the whole page fails to render. */
    private JobLiveState replaySessions(final String jobId) {
        final var state = new JobLiveState();
        try {
            client.streamJobEvents(jobId, state::apply);
        } catch (final Exception e) {
            // best-effort - see javadoc above
        }
        return state;
    }

    private void render(final Api.Job job, final List<Api.PlanTask> tasks, final JobLiveState live) {
        add(new RouterLink("← Queue", JobsView.class));
        final var openClassic = new Button("Open classic page",
                e -> getUI().ifPresent(ui -> ui.navigate("jobs/" + job.id() + "/classic")));
        openClassic.addThemeVariants(ButtonVariant.TERTIARY);

        final var titleRow = new HorizontalLayout(Badges.status(job.status()), openClassic);
        titleRow.setAlignItems(FlexComponent.Alignment.CENTER);
        titleRow.setSpacing(true);
        titleRow.setPadding(false);
        final var runTitle = new Div();
        runTitle.setText(job.run_id() == null ? job.id() : job.run_id());
        runTitle.getStyle().set("font-size", "1.6em").set("font-weight", "600").set("margin", "8px 0 4px 0");
        add(runTitle, titleRow);

        // found live, testing against l2-gran-none (a real failed job): result_line is the ONE
        // place the actual failure reason lives (here, a plan dependency cycle) - a "succeeded"
        // job's own result_line is just a bland "RESULT <id> scored" confirmation (sampled 5 real
        // rows of each), so only a FAILED job's result_line earns a place above the fold.
        if ("failed".equals(job.status()) && hasResult(job)) {
            add(Panels.error(job.result_line()));
        }

        // see class javadoc: real per-task status (PlanTask.implemented() alone is false across
        // the board for every already-finished job actually in this DB), from the same
        // already-accurate attempts endpoint the real page's own per-task Restart tab uses
        final var lastAttempt = new HashMap<String, Api.TaskAttempt>();
        for (final var t : tasks) {
            if (t.stage()) continue;
            try {
                final var attempts = client.taskAttempts(job.id(), t.id());
                if (!attempts.isEmpty()) lastAttempt.put(t.id(), attempts.get(0));
            } catch (final Exception e) {
                // best-effort - that task's row falls back to statusOf()'s own "–" below
            }
        }

        final var statGrid = statTileGrid(job, tasks, lastAttempt);
        // found live via getBoundingClientRect(): the stat-tile grid's own 12px bottom margin
        // stacked with Panels.card()'s own 10px top margin, the VerticalLayout's own inter-child
        // spacing, the Plan card's own top padding, and sectionTitle()'s own 16px margin-top added
        // up to a 60px gap down to the "Plan" title - nearly double the 32px tile-row rhythm the
        // rest of this page uses. Trimmed to 4px here (locally - the grid is otherwise untouched
        // above the tiles, where this same margin still applies).
        statGrid.getStyle().set("margin", "12px 0 4px 0");
        add(statGrid);

        if (!tasks.isEmpty()) {
            final var planCard = Panels.card("Plan", buildPlanGrid(tasks, lastAttempt, live));
            // Panels.card()'s own 16px horizontal padding insets the Grid 16px in from the card's
            // outer edge on each side - zeroing it here (locally, not in the shared helper) lines
            // the Grid's own visible left/right edges up exactly with the Params tile's above it.
            // Top margin also zeroed (see the stat-tile grid's own comment above) - the 10px
            // bottom margin is kept, for spacing below the Plan grid.
            planCard.getStyle().set("padding", "12px 0").set("margin", "0 0 10px 0");
            // sectionTitle()'s own 16px margin-top (Panels.java, shared) is part of the same
            // over-tall gap found above - shrunk here to 4px, matching its own margin-bottom,
            // without touching that shared helper (card()'s only child when called with one
            // content component, as here, is always its own sectionTitle() header).
            planCard.getChildren().findFirst()
                    .ifPresent(title -> title.getElement().getStyle().set("margin-top", "4px"));
            add(planCard);
        }
    }

    private static boolean hasResult(final Api.Job job) {
        return job.result_line() != null && !job.result_line().isBlank();
    }

    /** The compact "Result" tile value - full text for everything EXCEPT a failed job, whose own
     *  result_line is already shown verbatim, full-width, right above the fold (see render()'s own
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
     *  changing that shared helper would have silently changed the real page's own stat tiles too. */
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
                        dashboardTile("Kind", job.kind() + (job.arm() != null ? " · " + job.arm() + " r" + job.repeat() : "")),
                        dashboardTile("Model", modelOf(job)),
                        dashboardTile("Priority", job.priority() == null ? "–" : String.valueOf(job.priority())),
                        dashboardTile("Enqueued", Fmt.when(job.enqueued_at())),
                        dashboardTile("Duration", durationOf(job)),
                        dashboardTile("Tasks", done + " / " + realTasks.size()),
                        dashboardTile("Result", resultTileValue(job)),
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
     *  dashes replaced, with no ": " since there's no value to separate it from. */
    private static Badge paramBadge(final String arg) {
        final var stripped = arg.startsWith("--") ? arg.substring(2) : arg;
        final var eq = stripped.indexOf('=');
        if (eq < 0) {
            return Badges.text(stripped.replace('-', ' '), Badges.CONTRAST);
        }
        final var key = stripped.substring(0, eq).replace('-', ' ');
        final var value = stripped.substring(eq + 1);
        final var theme = value.matches("-?\\d+(\\.\\d+)?") ? Badges.WARNING : Badges.CONTRAST;
        return Badges.text(key + ": " + value, theme);
    }

    /** The sort key for a raw argv entry - its key portion (before "=", or the whole flag when
     *  there's none), pre-dash-replacement so it sorts the same regardless of the display format. */
    private static String paramKey(final String arg) {
        final var stripped = arg.startsWith("--") ? arg.substring(2) : arg;
        final var eq = stripped.indexOf('=');
        return eq < 0 ? stripped : stripped.substring(0, eq);
    }

    /** Panels.statTile() plus a visible border/surface/radius, via real Aura/base tokens
     *  (--vaadin-border-color-secondary, --vaadin-radius-l, --vaadin-background-container - all
     *  confirmed against the Aura styling docs) rather than hand-picked colors - and a smaller
     *  footprint than that shared helper's own default: tighter padding here, plus the value
     *  span's own font-size shrunk from Panels.statTile()'s 1.4em default (tileShell()'s 2
     *  children are always [0]=label, [1]=value when called with no detail, as here). Also drops
     *  tileShell()'s own 140px min-width: found live via getBoundingClientRect() that it was
     *  wider than the ~133px column track 7 auto-fit columns actually compute here, so every tile
     *  overflowed into the column-gap space - the real on-screen horizontal gap was ~3px despite
     *  a declared 32px gap, while the row gap (unaffected, since it's not width-constrained) was
     *  the full 32px. Overriding it locally keeps Panels.tileShell() itself untouched. */
    private static Div dashboardTile(final String label, final String value) {
        final var tile = Panels.statTile(label, value, null);
        tile.getStyle()
                .set("border", "1px solid var(--vaadin-border-color-secondary)")
                .set("border-radius", "var(--vaadin-radius-l)")
                .set("background", "var(--vaadin-background-container)")
                .set("padding", "8px 10px")
                .set("min-width", "0");
        final var children = tile.getChildren().toList();
        if (children.size() > 1) {
            children.get(1).getElement().getStyle().set("font-size", "1.05em");
        }
        return tile;
    }

    /** The real, available signal for a terminal task's outcome - see class javadoc for why this
     *  isn't PlanTask.implemented()/reported_status() (both unreliable on a finished job here). */
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

    /** Same column set as JobDetailView.buildPlanGrid()'s own real-page grid, including the time/
     *  generated/prefilled trio - reuses JobDetailView.taskStats(sessions, taskId, currentStep)
     *  directly (package-private, same package) rather than re-deriving that aggregation here, so
     *  this stays byte-for-byte consistent with the real page's own numbers. sessions/currentStep
     *  come from replaySessions()'s one-shot replay; an empty JobLiveState (the replay's best-effort
     *  failure path) just means every task's figures read "–"/"0" via taskStats()'s own no-match
     *  default, same as the real page shows for a task with no sessions at all. */
    private Grid<Api.PlanTask> buildPlanGrid(final List<Api.PlanTask> tasks, final Map<String, Api.TaskAttempt> lastAttempt,
            final JobLiveState live) {
        final var sessions = live.sessions();
        final var currentStep = live.currentStep();
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
        grid.addColumn(t -> Fmt.daysHoursMinutes(JobDetailView.taskStats(sessions, t.id(), currentStep).wallSec()))
                .setHeader("time").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> Fmt.count(JobDetailView.taskStats(sessions, t.id(), currentStep).completionTokens()))
                .setHeader("generated").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> Fmt.count(JobDetailView.taskStats(sessions, t.id(), currentStep).promptTokens()))
                .setHeader("prefilled").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> t.attempts() <= 1 && !t.rescued() ? "" : t.attempts() + (t.rescued() ? " (rescued)" : ""))
                .setHeader("attempts").setAutoWidth(true).setFlexGrow(0);
        grid.getColumns().forEach(c -> c.setResizable(true));
        grid.setItems(tasks);
        grid.setHeight(Math.min(tasks.size() * 44 + 60, 420) + "px");
        return grid;
    }
}
