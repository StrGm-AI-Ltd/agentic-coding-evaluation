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
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
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
import java.util.List;
import java.util.Objects;

/**
 * Job detail — the Vaadin twin of the Jinja2 SSE live page. The page chrome is built
 * once per navigation; the 2 s poll of GET /api/jobs/{id} and the /jobs/{id}/events
 * SSE stream (flushed via @Push) update individual fields, so live updates never
 * rebuild the DOM, steal focus, or lose text selection.
 *
 * <p>Moved off the primary "jobs/:jobId" route to "jobs/:jobId/classic" once
 * JobDetailPrototypeView took over as the main Job detail page - kept fully intact and linked
 * from there as the fallback for an in-progress job (Sessions/Recent requests/live log tail/
 * Restart-Resume), none of which the new static-snapshot page has yet.
 */
@Route(value = "jobs/:jobId/classic", layout = MainLayout.class)
public class JobDetailView extends VerticalLayout implements BeforeEnterObserver {
    private static final Logger log = LoggerFactory.getLogger(JobDetailView.class);

    private final ServiceClient client;
    private String jobId;
    private Api.Job currentJob;
    private String lastStatus;
    private Boolean runImported; // one probe per navigation (plus on terminal transition), cached across polls
    private transient Registration pollRegistration;
    // #528 (Phase 1): constructed lazily inside buildChrome() rather than as a field initializer -
    // unlike requestsGrid/sessionsGrid before it, JobLiveSection's own constructor needs jobId,
    // which (like client) isn't known until beforeEnter() sets it; buildChrome() is already the
    // once-per-navigation place everything else waits for that.
    private JobLiveSection liveSectionComponent;

    private final Grid<Api.PlanTask> planGrid = buildPlanGrid();
    // fetched once and cached - the plan itself never changes once p1_plan has produced it; the
    // status column still refreshes every poll cycle from live.sessions(), re-rendered via setItems()
    private List<Api.PlanTask> planTasks = List.of();

    // Stable field references, updated in place.
    private final Span kindLine = new Span();
    private final Span metaLine = new Span();
    private final Div statusHolder = new Div();
    private final Div blockedHolder = new Div();
    private final Div actionsHolder = new Div();
    // #517 follow-up (design-mockup parity): tasksDoneLine (now in JobLiveSection) is pure/local
    // (planTasks + live.sessions(), no I/O); taskTurnsLine/toolErrorsLine (same) come from
    // currentTaskStats, which refresh() fetches here (one extra GET per poll, same
    // /api/jobs/{id}/tasks/{taskId}/stats the per-task dialog's own Stats tab already calls) -
    // JobLiveSection.update() only ever reads currentTaskStats, never fetches it.
    private JsonNode currentTaskStats;
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
            // #528 (Phase 1): the #499 "connect even for an already-finished job" SSE re-arming
            // this attach listener used to do directly now lives on JobLiveSection's own attach
            // listener instead - it fires naturally on this same reattach too, since Vaadin
            // delivers attach events down the whole already-present component subtree, not just
            // this view's own root.
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
            add(new ViewHeader("Job"), Panels.error("No valid job id in the URL."));
            chromeBuilt = true; // nothing else to build
            return;
        }
        try {
            final var job = client.job(jobId);
            buildChrome(job);   // once per navigation
            update(job);
        } catch (final Exception e) {
            log.warn("could not load job {}: {}", jobId, e.toString());
            add(new ViewHeader("Job #" + jobId), Panels.error(client.errorText(e)));
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

        final var header = new ViewHeader(job.run_id());
        header.getHeading().getStyle().set("margin", "4px 0").set("font-size", "1.6em");
        add(header);

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

        // #528 (Phase 1): the Plan card's own position - between plannedOrderLine and the live
        // stat tiles - is preserved exactly via JobLiveSection's own planCard constructor slot,
        // even though the grid itself (and its live-status column) stays owned by this class.
        // onLiveChanged (-> updatePlanGrid): see JobLiveSection's own constructor javadoc - the
        // original updateLive() always called updatePlanGrid() as its own first line, on every
        // live update (not just poll ticks), which this replicates now that the two live apart.
        liveSectionComponent = new JobLiveSection(client, jobId, this::refresh,
                () -> updatePlanGrid(currentJob != null && JobStatuses.isTerminal(currentJob.status())),
                Panels.card("Plan", planGrid));
        add(liveSectionComponent);
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

        getUI().ifPresent(ui -> ui.setPollInterval(terminal ? -1 : 2000));
        refreshCurrentTaskStats(terminal);
        if (job.run_id() != null && shouldProbeRun(runImported, previousStatus, job.status())) {
            runImported = isRunImported(client, job.run_id());
        }

        updateStatus(job);
        updateActions(job);
        // #528 (Phase 1): ensureSse()/stopSse() now live inside JobLiveSection.update() itself,
        // triggered off the same `job.status()` terminal check - the exact ensureSse/stopSse vs.
        // updateStatus/updateActions ordering changed (was: ensureSse/stopSse first, now: after),
        // but neither side reads state the other one touches, so this is behaviorally identical.
        // updatePlanGrid() itself now runs via the onLiveChanged callback JobLiveSection invokes
        // from inside this same update() call (see its own constructor javadoc) - no separate
        // call needed here.
        liveSectionComponent.update(job, planTasks, currentTaskStats);

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
        final var step = liveSectionComponent.live().currentStep();
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

        final var actions = buildCancelPauseRequeueRow(client, job, this::refresh);
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

    /** #528 (Phase 2): extracted out of updateActions() so JobDetailPrototypeView can offer the same
     *  Cancel/Pause/Pause-after-task/Requeue-or-Resume controls - parameterized (client/job/onChanged)
     *  rather than reading this view's own fields, same treatment Phase 1 already gave the other
     *  live-machinery pieces. The run-imported "Open run detail" hint stays out of this method and
     *  inline in updateActions() instead - a separate concern (whether this job's run has already been
     *  imported for comparison) from job-level live control, and not something the new page asked for. */
    static HorizontalLayout buildCancelPauseRequeueRow(final ServiceClient client, final Api.Job job, final Runnable onChanged) {
        final var actions = new HorizontalLayout();
        actions.setPadding(false);
        actions.setSpacing(true);
        if (JobStatuses.canCancel(job.status(), job.cancel_requested())) {
            actions.add(new Button("Cancel", e -> {
                try {
                    client.cancel(job.id());
                    onChanged.run();
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
                    onChanged.run();
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
                    onChanged.run();
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
                    onChanged.run();
                } catch (final Exception ex) {
                    log.warn("could not requeue job {}: {}", job.id(), ex.toString());
                    Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
                }
            }));
        }
        return actions;
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
        grid.addColumn(t -> taskStatus(liveSectionComponent.live().sessions(), t,
                        currentJob != null && JobStatuses.isTerminal(currentJob.status()),
                        liveSectionComponent.live().currentStep(), liveSectionComponent.live().rescuingReason(t.id())))
                .setHeader("status").setAutoWidth(true).setFlexGrow(0)
                // #262: the status word alone doesn't say WHY a task didn't just succeed - hover
                // for the agent's own self-reported detail (or the harness fallback's explanation).
                .setTooltipGenerator(t -> taskStatusReason(liveSectionComponent.live().sessions(), t,
                        liveSectionComponent.live().rescuingReason(t.id())));
        grid.addColumn(t -> Fmt.daysHoursMinutes(taskStats(liveSectionComponent.live().sessions(), t.id(), liveSectionComponent.live().currentStep()).wallSec()))
                .setHeader("time").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> Fmt.count(taskStats(liveSectionComponent.live().sessions(), t.id(), liveSectionComponent.live().currentStep()).completionTokens()))
                .setHeader("generated").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> Fmt.count(taskStats(liveSectionComponent.live().sessions(), t.id(), liveSectionComponent.live().currentStep()).promptTokens()))
                .setHeader("prefilled").setAutoWidth(true).setFlexGrow(0);
        // #337: blank for the common case (one clean attempt, never rescued) - a clean pass and a
        // rescued/retried one should read as visibly different outcomes, not the same checkmark.
        grid.addColumn(t -> t.attempts() <= 1 && !t.rescued() ? "" : t.attempts() + (t.rescued() ? " (rescued)" : ""))
                .setHeader("attempts").setAutoWidth(true).setFlexGrow(0);
        grid.getColumns().forEach(c -> c.setResizable(true));
        // #528 (Phase 1): JobTaskDialog.open() - extracted out of this class; see its own javadoc.
        grid.addItemClickListener(e -> JobTaskDialog.open(client, jobId, e.getItem(), () -> currentJob,
                liveSectionComponent.live(), this, this::refresh));
        grid.getStyle().set("cursor", "pointer");
        // #264: same scroll-cap fix sessionsGrid/requestsGrid (now inside JobLiveSection) use, at a
        // smaller scale (one row per plan task rather than per session).
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
     *  chart already uses, for the same reason (a full rebuild every poll flashes/flickers).
     *
     *  <p>#541-followup, root-caused: animations-disable (#542) was NOT the real fix. Confirmed
     *  live via direct client-side introspection against a real broken instance (Playwright
     *  evaluate() reading the Flow wrapper's own internal `w.config.series`): this chart's
     *  underlying ApexCharts.js instance mounts/renders ASYNCHRONOUSLY, and `updateSeries()` is
     *  that library's own INCREMENTAL-update API - it expects a series to already exist so it can
     *  diff against it. The OLD pattern (build this chart empty, then separately call
     *  updateRequestSpeedChart() moments later in the SAME loadStats() pass) raced that first
     *  updateSeries() call against the chart's own still-in-flight render(): if it loses the race,
     *  `w.config.series` stays permanently empty and EVERY later updateSeries() call fails the
     *  same way forever - confirmed live, it never self-heals (unlike the Sessions/Requests grids,
     *  plain Vaadin components that recover instantly on their own next setItems() call).
     *
     *  <p>The actual fix: this chart's own real data is now embedded directly in its construction-
     *  time config (withXaxis/withSeries below) instead of a separate post-construction
     *  updateSeries() call - JobTaskDialog's own on-demand loadStats() already has the real data in
     *  hand before it ever builds this chart, so there is no reason to build it empty first. The
     *  client's native Vaadin property sync (how xaxis/series reach the browser as part of normal
     *  component construction/attach) is a different code path from the explicit updateData() RPC
     *  call the race lives in - sidestepping the race entirely rather than timing around it.
     *  updateRequestSpeedChart() below still exists, for genuine SUBSEQUENT redraws of an
     *  already-successfully-mounted chart (safe: by then the client has long since finished its
     *  own first render()). */
    static ApexCharts buildRequestSpeedChart(final List<RequestSpeedPoint> points,
            final List<Integer> compactionsBeforeRequest, final List<Integer> judgeOnTrackRequests,
            final List<Integer> judgeOffTrackRequests) {
        final var chart = ApexChartsBuilder.get()
                .withChart(ChartBuilder.get().withType(Type.LINE).withBackground("transparent")
                        .withAnimations(AnimationsBuilder.get().withEnabled(false).build()).build())
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
                .withXaxis(requestSpeedXaxis(points.size()))
                .withSeries(requestSpeedSeries(points, compactionsBeforeRequest, judgeOnTrackRequests, judgeOffTrackRequests))
                .build();
        chart.setHeight("220px");
        return chart;
    }

    /** Shared by buildRequestSpeedChart()/buildRequestTimingChart() above (identical x-axis) - see
     *  their own javadoc for why embedding real data at construction, rather than a separate
     *  updateSeries() call, is the actual #541 fix. */
    private static com.github.appreciated.apexcharts.config.XAxis requestSpeedXaxis(final int pointCount) {
        final var categories = requestSpeedCategories(pointCount);
        final var xaxisLabels = new com.github.appreciated.apexcharts.config.xaxis.Labels();
        xaxisLabels.setFormatter(requestSpeedLabelFormatterJs());
        return XAxisBuilder.get().withCategories(categories).withLabels(xaxisLabels)
                .withTitle(com.github.appreciated.apexcharts.config.xaxis.builder.TitleBuilder.get()
                        .withText("request # (red: a compaction landed right before it; green/amber: a judge checkpoint landed on track/off track)").build())
                .build();
    }

    /** The 6-series array both buildRequestSpeedChart() (construction-time) and
     *  updateRequestSpeedChart() (subsequent redraws) share - extracted so both paths stay
     *  byte-for-byte consistent. Empty-safe: an empty `points` list naturally produces empty/0-peak
     *  series via the same stream/array machinery, no special-casing needed. */
    private static com.github.appreciated.apexcharts.helper.Series[] requestSpeedSeries(final List<RequestSpeedPoint> points,
            final List<Integer> compactionsBeforeRequest, final List<Integer> judgeOnTrackRequests, final List<Integer> judgeOffTrackRequests) {
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
        return new com.github.appreciated.apexcharts.helper.Series[]{speedSeries, compactionSeries, generationSeries,
                contextSeries, judgeOnTrackSeries, judgeOffTrackSeries};
    }

    static void updateRequestSpeedChart(final ApexCharts chart, final List<RequestSpeedPoint> points,
                                          final List<Integer> compactionsBeforeRequest,
                                          final List<Integer> judgeOnTrackRequests, final List<Integer> judgeOffTrackRequests) {
        if (points.isEmpty()) return;
        chart.setXaxis(requestSpeedXaxis(points.size()));
        chart.updateSeries(requestSpeedSeries(points, compactionsBeforeRequest, judgeOnTrackRequests, judgeOffTrackRequests));
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
     *  same reason - see that pair's own javadoc. #541-followup, root-caused: real data is now
     *  embedded at construction time (withXaxis/withSeries) instead of a separate post-construction
     *  updateSeries() call - see buildRequestSpeedChart()'s own javadoc for the full root-cause
     *  writeup (mount-timing race on a chart's FIRST updateSeries() call). */
    static ApexCharts buildRequestTimingChart(final List<RequestSpeedPoint> points,
            final List<Integer> compactionsBeforeRequest, final List<Integer> judgeOnTrackRequests,
            final List<Integer> judgeOffTrackRequests) {
        final var chart = ApexChartsBuilder.get()
                .withChart(ChartBuilder.get().withType(Type.LINE).withBackground("transparent")
                        .withAnimations(AnimationsBuilder.get().withEnabled(false).build()).build())
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
                .withXaxis(requestSpeedXaxis(points.size()))
                .withSeries(requestTimingSeries(points, compactionsBeforeRequest, judgeOnTrackRequests, judgeOffTrackRequests))
                .build();
        chart.setHeight("220px");
        return chart;
    }

    /** The 6-series array both buildRequestTimingChart() (construction-time) and
     *  updateRequestTimingChart() (subsequent redraws) share - mirrors requestSpeedSeries() above. */
    private static com.github.appreciated.apexcharts.helper.Series[] requestTimingSeries(final List<RequestSpeedPoint> points,
            final List<Integer> compactionsBeforeRequest, final List<Integer> judgeOnTrackRequests, final List<Integer> judgeOffTrackRequests) {
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
        return new com.github.appreciated.apexcharts.helper.Series[]{ttftSeries, totalTimeSeries, compactionSeries,
                contextSeries, judgeOnTrackSeries, judgeOffTrackSeries};
    }

    static void updateRequestTimingChart(final ApexCharts chart, final List<RequestSpeedPoint> points,
                                           final List<Integer> compactionsBeforeRequest,
                                           final List<Integer> judgeOnTrackRequests, final List<Integer> judgeOffTrackRequests) {
        if (points.isEmpty()) return;
        chart.setXaxis(requestSpeedXaxis(points.size()));
        chart.updateSeries(requestTimingSeries(points, compactionsBeforeRequest, judgeOnTrackRequests, judgeOffTrackRequests));
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
