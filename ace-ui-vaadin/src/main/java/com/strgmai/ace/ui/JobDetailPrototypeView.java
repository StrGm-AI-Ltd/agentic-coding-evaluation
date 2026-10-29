package com.strgmai.ace.ui;

import com.github.appreciated.css.grid.sizes.Flex;
import com.github.appreciated.css.grid.sizes.Length;
import com.github.appreciated.css.grid.sizes.MinMax;
import com.github.appreciated.css.grid.sizes.Repeat;
import com.github.appreciated.layout.FlexibleGridLayout;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UX-redesign prototype for the Job detail page - a parallel, read-only route
 * (jobs/:jobId/prototype) that does NOT touch JobDetailView.java, so the real live page keeps
 * working exactly as-is while this explores a different information hierarchy on the same real
 * data.
 *
 * Deliberately a STATIC SNAPSHOT, not a live reimplementation: it fetches the job + plan once via
 * the same ServiceClient the real page uses, but does not replicate JobDetailView's own 2s-poll/
 * SSE-live-state machinery (JobLiveState, JobEventLoop) - that's a lot of plumbing duplication
 * this layout exploration doesn't need to prove or disprove the new hierarchy. Session-level data
 * (live sessions/requests/log-tail) is out of scope here - this prototype targets TERMINAL jobs
 * only (see the JobStatuses.isTerminal() guard in beforeEnter() below) and would under-report a
 * still-running job's current task.
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
@Route(value = "jobs/:jobId/prototype", layout = MainLayout.class)
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
        add(new ViewHeader("Job detail — layout prototype"));
        final var note = new Span("Static snapshot for UX review - no live updates, Sessions, or Recent requests here. "
                + "See the real, live page for those.");
        note.getStyle().set("font-size", "0.85em").set("opacity", "0.75");
        add(note);
        if (jobId == null) {
            add(Panels.error("No valid job id in the URL."));
            return;
        }
        try {
            final var job = client.job(jobId);
            if (!JobStatuses.isTerminal(job.status())) {
                add(Panels.warn("This prototype only renders finished jobs (see the class javadoc for why) "
                        + "- this job is still " + job.status() + "."));
                return;
            }
            final var plan = client.jobPlan(jobId);
            render(job, plan.tasks() == null ? List.of() : plan.tasks());
        } catch (final Exception e) {
            add(Panels.error(client.errorText(e)));
        }
    }

    private void render(final Api.Job job, final List<Api.PlanTask> tasks) {
        add(new RouterLink("← Queue", JobsView.class));
        final var openLive = new Button("Open live page",
                e -> getUI().ifPresent(ui -> ui.navigate("jobs/" + job.id())));
        openLive.addThemeVariants(ButtonVariant.TERTIARY);

        final var titleRow = new HorizontalLayout(Badges.status(job.status()), openLive);
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

        add(statTileGrid(job, tasks, lastAttempt));

        if (!tasks.isEmpty()) {
            final var planCard = Panels.card("Plan", buildPlanGrid(tasks, lastAttempt));
            // Panels.card()'s own 16px horizontal padding insets the Grid 16px in from the card's
            // outer edge on each side - zeroing it here (locally, not in the shared helper) lines
            // the Grid's own visible left/right edges up exactly with the Params tile's above it.
            planCard.getStyle().set("padding", "12px 0");
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
     *  top already renders with (Badges.status()), just on the neutral CONTRAST theme rather than
     *  a status color: per this class's own design rationale above, color here is reserved for
     *  status specifically, and a param isn't a status. Spans every column via
     *  `grid-column: 1 / -1` and lands in its own row below the compact tiles through normal grid
     *  auto-placement - no explicit row index needed. */
    private static Div paramsTile(final Api.Job job) {
        final var argv = job.argv() == null ? List.<String>of() : job.argv();
        final var chips = new Div();
        chips.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "6px").set("margin-top", "2px");
        if (argv.isEmpty()) {
            chips.add(new Span("–"));
        } else {
            argv.forEach(a -> chips.add(Badges.text(formatParam(a), Badges.CONTRAST)));
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
    private static String formatParam(final String arg) {
        final var stripped = arg.startsWith("--") ? arg.substring(2) : arg;
        final var eq = stripped.indexOf('=');
        if (eq < 0) {
            return stripped.replace('-', ' ');
        }
        return stripped.substring(0, eq).replace('-', ' ') + ": " + stripped.substring(eq + 1);
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

    private Grid<Api.PlanTask> buildPlanGrid(final List<Api.PlanTask> tasks, final Map<String, Api.TaskAttempt> lastAttempt) {
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
        grid.addColumn(t -> t.attempts() <= 1 && !t.rescued() ? "" : t.attempts() + (t.rescued() ? " (rescued)" : ""))
                .setHeader("attempts").setAutoWidth(true).setFlexGrow(0);
        grid.setItems(tasks);
        grid.setHeight(Math.min(tasks.size() * 44 + 60, 420) + "px");
        return grid;
    }
}
